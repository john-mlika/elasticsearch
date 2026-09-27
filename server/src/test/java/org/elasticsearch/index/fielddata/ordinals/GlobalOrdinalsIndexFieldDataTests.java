/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */
package org.elasticsearch.index.fielddata.ordinals;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.SortedSetDocValuesField;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.FilterSortedDocValues;
import org.apache.lucene.index.FilterSortedSetDocValues;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.index.NoMergePolicy;
import org.apache.lucene.index.OrdinalMap;
import org.apache.lucene.index.SortedDocValues;
import org.apache.lucene.index.SortedSetDocValues;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.store.Directory;
import org.apache.lucene.util.Accountable;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.packed.PackedInts;
import org.elasticsearch.core.Strings;
import org.elasticsearch.core.TimeValue;
import org.elasticsearch.index.fielddata.IndexOrdinalsFieldData;
import org.elasticsearch.index.fielddata.LeafOrdinalsFieldData;
import org.elasticsearch.index.fielddata.plain.AbstractLeafOrdinalsFieldData;
import org.elasticsearch.search.aggregations.support.CoreValuesSourceType;
import org.elasticsearch.test.ESTestCase;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;

public class GlobalOrdinalsIndexFieldDataTests extends ESTestCase {

    public void testTermsEnumIsCreatedOnFirstLookupInItsSegment() throws IOException {
        // single valued segments go through SingletonGlobalOrdinalMapping, multi valued ones through GlobalOrdinalMapping
        assertTermsEnumIsCreatedOnFirstLookupInItsSegment(false);
        assertTermsEnumIsCreatedOnFirstLookupInItsSegment(true);
    }

    private void assertTermsEnumIsCreatedOnFirstLookupInItsSegment(boolean multiValued) throws IOException {
        TreeSet<String> terms = new TreeSet<>();
        try (Directory dir = newDirectory()) {
            try (IndexWriter writer = new IndexWriter(dir, newIndexWriterConfig().setMergePolicy(NoMergePolicy.INSTANCE))) {
                // every term is unique, so no segment has as many ordinals as the whole reader
                for (int s = between(2, 5); s > 0; s--) {
                    int docs = between(1, 20);
                    for (int d = 0; d < docs; d++) {
                        Document doc = new Document();
                        int values = multiValued && d == 0 ? 2 : 1;
                        for (int v = 0; v < values; v++) {
                            String term = Strings.format("s%d-%02d-%d", s, d, v);
                            terms.add(term);
                            doc.add(new SortedSetDocValuesField("field", new BytesRef(term)));
                        }
                        writer.addDocument(doc);
                    }
                    writer.commit();
                }
            }
            List<String> sortedTerms = new ArrayList<>(terms);
            try (DirectoryReader reader = DirectoryReader.open(dir)) {
                int segments = reader.leaves().size();
                AtomicInteger termsEnums = new AtomicInteger();
                LeafOrdinalsFieldData[] segmentAfd = new LeafOrdinalsFieldData[segments];
                SortedSetDocValues[] segmentValues = new SortedSetDocValues[segments];
                for (int s = 0; s < segments; s++) {
                    segmentAfd[s] = countingTermsEnums(reader.leaves().get(s), termsEnums);
                    segmentValues[s] = segmentAfd[s].getOrdinalsValues();
                }
                OrdinalMap ordinalMap = OrdinalMap.build(null, segmentValues, PackedInts.DEFAULT);
                GlobalOrdinalsIndexFieldData fieldData = new GlobalOrdinalsIndexFieldData(
                    "field",
                    CoreValuesSourceType.KEYWORD,
                    segmentAfd,
                    ordinalMap,
                    0,
                    null,
                    TimeValue.ZERO
                );
                termsEnums.set(0);

                // collecting global ordinals doesn't need a single term
                IndexOrdinalsFieldData consumer = fieldData.newConsumer(reader);
                for (LeafReaderContext leaf : reader.leaves()) {
                    SortedSetDocValues values = consumer.load(leaf).getOrdinalsValues();
                    for (int doc = 0; doc < leaf.reader().maxDoc(); doc++) {
                        assertTrue(values.advanceExact(doc));
                        for (int i = 0; i < values.docValueCount(); i++) {
                            values.nextOrd();
                        }
                    }
                }
                assertThat(termsEnums.get(), equalTo(0));

                // resolving an ordinal only creates the TermsEnum of the segment the ordinal comes from
                SortedSetDocValues values = consumer.load(reader.leaves().get(0)).getOrdinalsValues();
                if (multiValued) {
                    assertThat(values, instanceOf(GlobalOrdinalMapping.class));
                } else {
                    assertThat(DocValues.unwrapSingleton(values), instanceOf(SingletonGlobalOrdinalMapping.class));
                }
                int ord = between(0, sortedTerms.size() - 1);
                assertThat(values.lookupOrd(ord).utf8ToString(), equalTo(sortedTerms.get(ord)));
                assertThat(values.lookupOrd(ord).utf8ToString(), equalTo(sortedTerms.get(ord)));
                assertThat(termsEnums.get(), equalTo(1));

                for (ord = 0; ord < sortedTerms.size(); ord++) {
                    assertThat(values.lookupOrd(ord).utf8ToString(), equalTo(sortedTerms.get(ord)));
                }
                assertThat(termsEnums.get(), equalTo(segments));
            }
        }
    }

    private static LeafOrdinalsFieldData countingTermsEnums(LeafReaderContext leaf, AtomicInteger termsEnums) {
        return new AbstractLeafOrdinalsFieldData(null) {
            @Override
            public SortedSetDocValues getOrdinalsValues() {
                SortedSetDocValues values;
                try {
                    values = DocValues.getSortedSet(leaf.reader(), "field");
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
                SortedDocValues singleValues = DocValues.unwrapSingleton(values);
                if (singleValues != null) {
                    return DocValues.singleton(new FilterSortedDocValues(singleValues) {
                        @Override
                        public TermsEnum termsEnum() throws IOException {
                            termsEnums.incrementAndGet();
                            return super.termsEnum();
                        }
                    });
                }
                return new FilterSortedSetDocValues(values) {
                    @Override
                    public TermsEnum termsEnum() throws IOException {
                        termsEnums.incrementAndGet();
                        return super.termsEnum();
                    }
                };
            }

            @Override
            public long ramBytesUsed() {
                return 0;
            }

            @Override
            public Collection<Accountable> getChildResources() {
                return List.of();
            }
        };
    }
}
