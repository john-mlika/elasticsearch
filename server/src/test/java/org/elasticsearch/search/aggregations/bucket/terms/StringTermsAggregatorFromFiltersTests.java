/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.search.aggregations.bucket.terms;

import org.apache.lucene.document.SortedSetDocValuesField;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.IndexableField;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.util.BytesRef;
import org.elasticsearch.common.lucene.search.Queries;
import org.elasticsearch.core.Strings;
import org.elasticsearch.index.mapper.KeywordFieldMapper;
import org.elasticsearch.index.mapper.KeywordFieldMapper.KeywordField;
import org.elasticsearch.index.mapper.KeywordFieldMapper.KeywordFieldType;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.search.aggregations.Aggregator;
import org.elasticsearch.search.aggregations.AggregatorFactories;
import org.elasticsearch.search.aggregations.AggregatorTestCase;
import org.elasticsearch.search.aggregations.BucketOrder;
import org.elasticsearch.search.aggregations.CardinalityUpperBound;
import org.elasticsearch.search.aggregations.support.AggregationContext;
import org.elasticsearch.search.aggregations.support.CoreValuesSourceType;
import org.elasticsearch.search.aggregations.support.ValuesSource;
import org.elasticsearch.search.aggregations.support.ValuesSourceConfig;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;

public class StringTermsAggregatorFromFiltersTests extends AggregatorTestCase {
    private static final String FIELD = "k";
    private static final String OTHER_FIELD = "o";

    private final MappedFieldType fieldType = new KeywordFieldType(FIELD, true, true, Collections.emptyMap());
    private final MappedFieldType otherFieldType = new KeywordFieldType(OTHER_FIELD, true, true, Collections.emptyMap());

    /**
     * A top level query that can be merged with each term keeps the rewrite and builds a filter for every term.
     */
    public void testAdaptsWhenTopLevelQueryMerges() throws IOException {
        int terms = between(2, 100);
        adapt(terms, Queries.ALL_DOCS_INSTANCE, null, (adapted, tested, supplied) -> {
            assertThat(adapted, notNullValue());
            assertThat(tested, equalTo(terms));
            assertThat(supplied, equalTo(1));
        });
    }

    /**
     * A top level query that can't be merged with a term makes the first filter invalidate the rewrite,
     * so we stop there rather than building a filter for every other term.
     */
    public void testStopsAtFirstInefficientUnion() throws IOException {
        int terms = between(2, 100);
        adapt(terms, new TermQuery(new Term(OTHER_FIELD, "x")), null, (adapted, tested, supplied) -> {
            assertThat(adapted, nullValue());
            assertThat(tested, equalTo(1));
            assertThat(supplied, equalTo(1));
        });
    }

    /**
     * With a parent the rewrite is invalid from the start, so we don't look at a single term.
     */
    public void testDoesNotBuildFiltersUnderParent() throws IOException {
        int terms = between(2, 100);
        adapt(terms, Queries.ALL_DOCS_INSTANCE, mock(Aggregator.class), (adapted, tested, supplied) -> {
            assertThat(adapted, nullValue());
            assertThat(tested, equalTo(0));
            assertThat(supplied, equalTo(0));
        });
    }

    private static IndexableField keyword(String field, String value) {
        return new KeywordField(field, new BytesRef(value), KeywordFieldMapper.Defaults.FIELD_TYPE);
    }

    private static IndexableField docValues(String field, String value) {
        return new SortedSetDocValuesField(field, new BytesRef(value));
    }

    @FunctionalInterface
    private interface Verify {
        void verify(StringTermsAggregatorFromFilters adapted, int acceptedOrdsTested, int valuesSupplied);
    }

    private void adapt(int terms, Query topLevelQuery, Aggregator parent, Verify verify) throws IOException {
        withIndex(iw -> {
            for (int t = 0; t < terms; t++) {
                String value = Strings.format("t%03d", t);
                iw.addDocument(List.of(keyword(FIELD, value), docValues(FIELD, value), keyword(OTHER_FIELD, "x")));
            }
        }, (IndexReader reader) -> {
            try (AggregationContext context = createAggregationContext(reader, topLevelQuery, fieldType, otherFieldType)) {
                ValuesSourceConfig config = ValuesSourceConfig.resolve(
                    context,
                    null,
                    FIELD,
                    null,
                    null,
                    null,
                    null,
                    CoreValuesSourceType.KEYWORD
                );
                ValuesSource.Bytes.WithOrdinals valuesSource = (ValuesSource.Bytes.WithOrdinals) config.getValuesSource();
                AtomicInteger tested = new AtomicInteger();
                AtomicInteger supplied = new AtomicInteger();
                StringTermsAggregatorFromFilters adapted = StringTermsAggregatorFromFilters.adaptIntoFiltersOrNull(
                    "t",
                    AggregatorFactories.EMPTY,
                    context,
                    parent,
                    false,
                    CardinalityUpperBound.ONE,
                    Map.of(),
                    config,
                    BucketOrder.count(false),
                    new TermsAggregator.BucketCountThresholds(1, 0, 10, 10),
                    ord -> {
                        tested.incrementAndGet();
                        return true;
                    },
                    () -> {
                        supplied.incrementAndGet();
                        return TermsAggregatorFactory.globalOrdsValues(context, valuesSource);
                    }
                );
                verify.verify(adapted, tested.get(), supplied.get());
            }
        });
    }
}
