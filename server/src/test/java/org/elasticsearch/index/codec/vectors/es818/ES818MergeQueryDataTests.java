/*
 * Copyright Elasticsearch B.V. and/or licensed to Elasticsearch B.V. under one
 * or more contributor license agreements. Licensed under the "Elastic License
 * 2.0", the "GNU Affero General Public License v3.0 only", and the "Server Side
 * Public License v 1"; you may not use this file except in compliance with, at
 * your election, the "Elastic License 2.0", the "GNU Affero General Public
 * License v3.0 only", or the "Server Side Public License, v 1".
 */

package org.elasticsearch.index.codec.vectors.es818;

import org.apache.lucene.codecs.CodecUtil;
import org.apache.lucene.codecs.KnnVectorsFormat;
import org.apache.lucene.codecs.KnnVectorsReader;
import org.apache.lucene.codecs.KnnVectorsWriter;
import org.apache.lucene.codecs.hnsw.FlatVectorsFormat;
import org.apache.lucene.codecs.hnsw.FlatVectorsWriter;
import org.apache.lucene.codecs.hnsw.HnswGraphProvider;
import org.apache.lucene.codecs.lucene99.Lucene99HnswVectorsReader;
import org.apache.lucene.codecs.lucene99.Lucene99HnswVectorsWriter;
import org.apache.lucene.codecs.perfield.PerFieldKnnVectorsFormat;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.index.CodecReader;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.FieldInfo;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.MergeState;
import org.apache.lucene.index.SegmentInfos;
import org.apache.lucene.index.SegmentWriteState;
import org.apache.lucene.index.SerialMergeScheduler;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.KnnFloatVectorQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FilterDirectory;
import org.apache.lucene.store.FilterIndexInput;
import org.apache.lucene.store.FilterIndexOutput;
import org.apache.lucene.store.IOContext;
import org.apache.lucene.store.IndexInput;
import org.apache.lucene.store.IndexOutput;
import org.apache.lucene.tests.store.BaseDirectoryWrapper;
import org.apache.lucene.tests.util.LuceneTestCase;
import org.apache.lucene.tests.util.TestUtil;
import org.apache.lucene.util.VectorUtil;
import org.apache.lucene.util.hnsw.HnswGraph;
import org.apache.lucene.util.quantization.QuantizedByteVectorValues;
import org.elasticsearch.common.logging.LogConfigurator;
import org.elasticsearch.index.codec.vectors.BQVectorUtils;
import org.elasticsearch.index.codec.vectors.es816.BinaryQuantizer;
import org.elasticsearch.index.codec.vectors.es93.ES93HnswBinaryQuantizedVectorsFormat;
import org.elasticsearch.index.mapper.vectors.DenseVectorFieldMapper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntPredicate;

/**
 * A BBQ HNSW merge must not read the merged raw vectors back to build the graph: the flat writer writes the
 * query-side records in the same pass as the index-side ones and hands them to the graph build. These tests
 * pin that, and that the hand-off never outlives the merge.
 */
public class ES818MergeQueryDataTests extends LuceneTestCase {

    static {
        LogConfigurator.configureESLogging(); // native vector scoring requires logging to be initialized
    }

    // not a multiple of 64, so the packed records carry padding
    private static final int DIMS = 100;
    private static final int DOCS_PER_SEGMENT = 200;
    /** Two fields share the merged raw vector file. */
    private static final long MERGED_RAW_BYTES = 2L * 2 * DOCS_PER_SEGMENT * DIMS * Float.BYTES;
    private static final int PACKED_QUERY_BYTES = BQVectorUtils.discretize(DIMS, 64) / 8 * BinaryQuantizer.B_QUERY;
    /** A 4-bit query-side record: the packed bits, three float corrections and the component sum as a short. */
    private static final int QUERY_RECORD_BYTES = PACKED_QUERY_BYTES + 3 * Integer.BYTES + Short.BYTES;
    /** The temp-file suffix of the query-side records, from the writer or from the reader fallback. */
    private static final String QUERIES = "queries";

    private static KnnVectorsFormat bbqHnsw(int graphThreshold, DenseVectorFieldMapper.ElementType elementType) {
        return new ES93HnswBinaryQuantizedVectorsFormat(16, 100, elementType, false, 1, null, graphThreshold, false);
    }

    public void testMergeDoesNotReadMergedRawVectorsBack() throws IOException {
        for (DenseVectorFieldMapper.ElementType elementType : List.of(
            DenseVectorFieldMapper.ElementType.FLOAT,
            DenseVectorFieldMapper.ElementType.BFLOAT16
        )) {
            for (VectorSimilarityFunction similarity : VectorSimilarityFunction.values()) {
                // threshold 0: the merge always builds a graph, so the merge scorer is always requested
                long readBack = runMerge(bbqHnsw(0, elementType), true, similarity);
                // opening the merged segment reads the raw file's header and footer, but must not stream the vectors
                assertTrue(
                    "the merge read the merged raw vectors back: " + readBack + " bytes for " + elementType + "/" + similarity,
                    readBack < (long) DIMS * Float.BYTES
                );
            }
        }
        // positive control: without the hand-off, the reader builds the merge scorer from the merged raw vectors
        long readBack = runMerge(new ReadBackFormat(), true, VectorSimilarityFunction.EUCLIDEAN);
        assertTrue("the reader fallback did not read the merged raw vectors back: " + readBack, readBack >= MERGED_RAW_BYTES);
    }

    public void testNoQueryDataWhenNoGraphIsBuilt() throws IOException {
        // a threshold the merge does not reach: no graph, so the writer must not write query-side records at all
        runMerge(bbqHnsw(Integer.MAX_VALUE, DenseVectorFieldMapper.ElementType.FLOAT), false, VectorSimilarityFunction.EUCLIDEAN);
    }

    /** The second field fails before the first field's graph is built, so the first field's hand-off is never consumed. */
    public void testPhaseOneFailureLeavesNoQueryData() throws IOException {
        try (Directory dir = new FilterDirectory(newDirectory()) {
            private final AtomicBoolean first = new AtomicBoolean(true);

            @Override
            public IndexOutput createTempOutput(String prefix, String suffix, IOContext context) throws IOException {
                if (QUERIES.equals(suffix) && first.compareAndSet(true, false) == false) {
                    throw new IOException("simulated failure creating the second hand-off");
                }
                return super.createTempOutput(prefix, suffix, context);
            }
        }) {
            assertMergeFailsWithoutLeftovers(dir, false);
        }
    }

    /** The writer has written the records but fails before it returns the hand-off. */
    public void testMetaFailureLeavesNoQueryData() throws IOException {
        try (Directory dir = new FilterDirectory(newDirectory()) {
            private final AtomicBoolean recordsWritten = new AtomicBoolean();

            @Override
            public IndexOutput createTempOutput(String prefix, String suffix, IOContext context) throws IOException {
                IndexOutput out = super.createTempOutput(prefix, suffix, context);
                if (QUERIES.equals(suffix)) {
                    recordsWritten.set(true);
                }
                return out;
            }

            @Override
            public IndexOutput createOutput(String name, IOContext context) throws IOException {
                IndexOutput out = super.createOutput(name, context);
                if (name.endsWith("." + ES818BinaryQuantizedVectorsFormat.META_EXTENSION) == false) {
                    return out;
                }
                return new FilterIndexOutput("failing meta", name, out) {
                    @Override
                    public void writeByte(byte b) throws IOException {
                        failIfRecordsWritten();
                        super.writeByte(b);
                    }

                    @Override
                    public void writeBytes(byte[] b, int offset, int length) throws IOException {
                        failIfRecordsWritten();
                        super.writeBytes(b, offset, length);
                    }

                    private void failIfRecordsWritten() throws IOException {
                        if (recordsWritten.get()) {
                            throw new IOException("simulated failure writing the meta");
                        }
                    }
                };
            }
        }) {
            assertMergeFailsWithoutLeftovers(dir, true);
        }
    }

    /** The graph build asks for the scorer, and opening the records fails. */
    public void testPhaseTwoFailureLeavesNoQueryData() throws IOException {
        try (Directory dir = new FilterDirectory(newDirectory()) {
            @Override
            public IndexInput openInput(String name, IOContext context) throws IOException {
                if (name.contains(QUERIES)) {
                    throw new IOException("simulated failure opening the hand-off");
                }
                return super.openInput(name, context);
            }
        }) {
            assertMergeFailsWithoutLeftovers(dir, true);
        }
    }

    /** Merges two segments, expects the simulated failure, and checks that no temp file is left behind. */
    private void assertMergeFailsWithoutLeftovers(Directory dir, boolean singleField) throws IOException {
        IndexWriterConfig config = new IndexWriterConfig().setCodec(
            TestUtil.alwaysKnnVectorsFormat(bbqHnsw(0, DenseVectorFieldMapper.ElementType.FLOAT))
        );
        // merge on the calling thread, so the failure surfaces from forceMerge rather than from a merge thread
        config.setMergeScheduler(new SerialMergeScheduler());
        IndexWriter writer = new IndexWriter(dir, config);
        try {
            for (int i = 0; i < 2 * DOCS_PER_SEGMENT; i++) {
                Document doc = new Document();
                doc.add(new KnnFloatVectorField("v", randomVector(DIMS), VectorSimilarityFunction.EUCLIDEAN));
                if (singleField == false) {
                    doc.add(new KnnFloatVectorField("w", randomVector(DIMS), VectorSimilarityFunction.EUCLIDEAN));
                }
                writer.addDocument(doc);
                if (i == DOCS_PER_SEGMENT - 1) {
                    writer.commit();
                }
            }
            writer.commit();
            Exception failure = expectThrows(Exception.class, () -> writer.forceMerge(1));
            boolean simulated = false;
            for (Throwable t = failure; t != null && simulated == false; t = t.getCause()) {
                simulated = String.valueOf(t.getMessage()).contains("simulated failure");
            }
            assertTrue("unexpected merge failure: " + failure, simulated);
            // check before rollback, which can sweep unreferenced temp files and hide a cleanup failure
            for (String file : dir.listAll()) {
                assertFalse("a temp file survived the failed merge: " + file, file.endsWith(".tmp"));
            }
        } finally {
            try {
                writer.rollback();
            } catch (Exception e) {
                // the writer may already be unusable after the failed merge
            }
        }
    }

    /**
     * Merges two segments of two vector fields into one and checks the result. Returns how many bytes of the merged
     * raw vector file the merge read.
     */
    private long runMerge(KnnVectorsFormat format, boolean expectGraph, VectorSimilarityFunction similarity) throws IOException {
        float[][] vectors = new float[DOCS_PER_SEGMENT * 2][];
        for (int i = 0; i < vectors.length; i++) {
            vectors[i] = randomVector(DIMS);
            if (similarity == VectorSimilarityFunction.DOT_PRODUCT) {
                VectorUtil.l2normalize(vectors[i]);
            }
        }
        IndexWriterConfig config = new IndexWriterConfig().setCodec(TestUtil.alwaysKnnVectorsFormat(format));
        // keep the raw vector file visible as its own file, not hidden inside a .cfs
        config.setUseCompoundFile(false);
        config.getMergePolicy().setNoCFSRatio(0.0);
        BaseDirectoryWrapper inner = newDirectory();
        if (similarity == VectorSimilarityFunction.COSINE) {
            // cosine vectors stay unnormalized so the writer normalizes them, but CheckIndex would search with them,
            // and the BBQ scorer requires unit-length cosine queries
            inner.setCheckIndexOnClose(false);
        }
        try (ReadCountingDirectory dir = new ReadCountingDirectory(inner); IndexWriter writer = new IndexWriter(dir, config)) {
            for (int i = 0; i < vectors.length; i++) {
                Document doc = new Document();
                doc.add(new KnnFloatVectorField("v", vectors[i], similarity));
                // a second field of the same format: its own hand-off, its own cleanup
                doc.add(new KnnFloatVectorField("w", vectors[vectors.length - 1 - i], similarity));
                writer.addDocument(doc);
                if (i == DOCS_PER_SEGMENT - 1) {
                    writer.commit();
                }
            }
            writer.commit();
            SegmentInfos before = SegmentInfos.readLatestCommit(dir);
            assertEquals(2, before.size());
            writer.forceMerge(1);
            writer.commit();

            SegmentInfos after = SegmentInfos.readLatestCommit(dir);
            assertEquals(1, after.size());
            String mergedRaw = null;
            for (String file : after.info(0).files()) {
                if (file.endsWith(".vec")) {
                    mergedRaw = file;
                }
            }
            assertNotNull("merged segment has no raw vector file", mergedRaw);
            for (String file : dir.listAll()) {
                assertFalse("a temp file outlived the merge: " + file, file.endsWith(".tmp"));
            }
            // one hand-off per field exactly when the merge builds a graph, and the graph build opens it
            assertEquals("query-side record files: " + dir.queryFiles(), expectGraph ? 2 : 0, dir.queryFiles().size());
            for (String queries : dir.queryFiles()) {
                assertTrue("the graph build never opened " + queries, dir.opened.contains(queries));
                assertQueryRecordSums(dir.queryBytes.get(queries).toByteArray());
            }
            long readBack = dir.bytesRead(mergedRaw);

            try (DirectoryReader reader = DirectoryReader.open(writer)) {
                assertEquals(vectors.length, reader.numDocs());
                float[] query = vectors[0].clone();
                if (similarity == VectorSimilarityFunction.COSINE) {
                    VectorUtil.l2normalize(query);
                }
                TopDocs topDocs = new IndexSearcher(reader).search(new KnnFloatVectorQuery("v", query, 5), 5);
                assertEquals(5, topDocs.scoreDocs.length);
                KnnVectorsReader vectorsReader = ((CodecReader) getOnlyLeafReader(reader)).getVectorReader();
                if (vectorsReader instanceof PerFieldKnnVectorsFormat.FieldsReader perField) {
                    vectorsReader = perField.getFieldReader("v");
                }
                HnswGraph graph = ((HnswGraphProvider) vectorsReader).getGraph("v");
                assertEquals("graph presence", expectGraph, graph != null && graph.size() > 0);
                ES818BinaryQuantizedVectorsReader flatReader =
                    (ES818BinaryQuantizedVectorsReader) ((Lucene99HnswVectorsReader) vectorsReader).getFlatVectorsReader();
                assertIndexRecordSums(flatReader.getQuantizedVectorValues("v"));
                assertIndexRecordSums(flatReader.getQuantizedVectorValues("w"));
            }
            return readBack;
        }
    }

    /**
     * Each 1-bit index-side record's component sum must be the number of bits set, so each record carries the
     * corrections of its own bit width. An exact integer check, unlike comparing floating-point results.
     */
    private static void assertIndexRecordSums(QuantizedByteVectorValues values) throws IOException {
        assertEquals(2 * DOCS_PER_SEGMENT, values.size());
        for (int ord = 0; ord < values.size(); ord++) {
            int bits = 0;
            for (byte b : values.vectorValue(ord)) {
                bits += Integer.bitCount(b & 0xFF);
            }
            assertEquals("index record " + ord, bits, values.getCorrectiveTerms(ord).quantizedComponentSum());
        }
    }

    /**
     * Each 4-bit query-side record's component sum must be the sum of its packed values, whose four bit planes follow
     * each other in the record.
     */
    private static void assertQueryRecordSums(byte[] file) {
        int records = (file.length - CodecUtil.footerLength()) / QUERY_RECORD_BYTES;
        assertEquals("query-side records", 2 * DOCS_PER_SEGMENT, records);
        assertEquals(file.length - CodecUtil.footerLength(), records * QUERY_RECORD_BYTES);
        int planeBytes = PACKED_QUERY_BYTES / BinaryQuantizer.B_QUERY;
        for (int r = 0; r < records; r++) {
            int offset = r * QUERY_RECORD_BYTES;
            int sum = 0;
            for (int i = 0; i < PACKED_QUERY_BYTES; i++) {
                sum += Integer.bitCount(file[offset + i] & 0xFF) << (i / planeBytes);
            }
            int stored = (file[offset + QUERY_RECORD_BYTES - 2] & 0xFF) | (file[offset + QUERY_RECORD_BYTES - 1] & 0xFF) << 8;
            assertEquals("query record " + r, sum, stored);
        }
    }

    /** The ES818 HNSW BBQ test format with a flat writer that prepares nothing, so every graph build takes the read-back. */
    private static final class ReadBackFormat extends ES818HnswBinaryQuantizedRWVectorsFormat {
        private static final FlatVectorsFormat FLAT_FORMAT = new ES818BinaryQuantizedRWVectorsFormat();

        @Override
        public KnnVectorsWriter fieldsWriter(SegmentWriteState state) throws IOException {
            FlatVectorsWriter flatWriter = new ES818BinaryQuantizedVectorsWriter(
                ES818BinaryQuantizedVectorsFormat.scorer,
                ES818BinaryQuantizedVectorsFormat.rawVectorFormat.fieldsWriter(state),
                state
            ) {
                @Override
                public MergeScorerData mergeOneFlatVectorFieldForMergeScorer(
                    FieldInfo fieldInfo,
                    MergeState mergeState,
                    IntPredicate needsMergeScorer
                ) throws IOException {
                    mergeOneFlatVectorField(fieldInfo, mergeState);
                    return null;
                }
            };
            return new Lucene99HnswVectorsWriter(state, maxConn, beamWidth, FLAT_FORMAT, flatWriter, numMergeWorkers, mergeExec, 0);
        }
    }

    private static float[] randomVector(int dims) {
        float[] vector = new float[dims];
        for (int i = 0; i < dims; i++) {
            vector[i] = random().nextFloat();
        }
        return vector;
    }

    /**
     * Counts the bytes read from raw vector files, including through clones and slices, and records the files
     * opened and the query-side files created, with a copy of their bytes. Only raw vector files are wrapped: the
     * native BBQ scorer needs direct access to the inputs it scores.
     */
    private static class ReadCountingDirectory extends FilterDirectory {
        private final Map<String, AtomicLong> bytesRead = new ConcurrentHashMap<>();
        private final List<String> queryFiles = new CopyOnWriteArrayList<>();
        private final Set<String> opened = ConcurrentHashMap.newKeySet();
        private final Map<String, ByteArrayOutputStream> queryBytes = new ConcurrentHashMap<>();

        ReadCountingDirectory(Directory in) {
            super(in);
        }

        @Override
        public IndexInput openInput(String name, IOContext context) throws IOException {
            opened.add(name);
            IndexInput in = super.openInput(name, context);
            return name.endsWith(".vec") ? new CountingIndexInput(in, bytesRead.computeIfAbsent(name, n -> new AtomicLong())) : in;
        }

        @Override
        public IndexOutput createTempOutput(String prefix, String suffix, IOContext context) throws IOException {
            IndexOutput out = super.createTempOutput(prefix, suffix, context);
            if (QUERIES.equals(suffix) == false) {
                return out;
            }
            queryFiles.add(out.getName());
            ByteArrayOutputStream copy = new ByteArrayOutputStream();
            queryBytes.put(out.getName(), copy);
            return new FilterIndexOutput("copying " + out, out.getName(), out) {
                @Override
                public void writeByte(byte b) throws IOException {
                    copy.write(b);
                    super.writeByte(b);
                }

                @Override
                public void writeBytes(byte[] b, int offset, int length) throws IOException {
                    copy.write(b, offset, length);
                    super.writeBytes(b, offset, length);
                }
            };
        }

        long bytesRead(String name) {
            AtomicLong count = bytesRead.get(name);
            return count == null ? 0 : count.get();
        }

        List<String> queryFiles() {
            return queryFiles;
        }
    }

    private static class CountingIndexInput extends FilterIndexInput {
        private final AtomicLong counter;

        CountingIndexInput(IndexInput in, AtomicLong counter) {
            super(in.toString(), in);
            this.counter = counter;
        }

        @Override
        public byte readByte() throws IOException {
            counter.incrementAndGet();
            return in.readByte();
        }

        @Override
        public void readBytes(byte[] b, int offset, int len) throws IOException {
            counter.addAndGet(len);
            in.readBytes(b, offset, len);
        }

        @Override
        public IndexInput slice(String sliceDescription, long offset, long length) throws IOException {
            return new CountingIndexInput(in.slice(sliceDescription, offset, length), counter);
        }

        @Override
        public IndexInput clone() {
            return new CountingIndexInput(in.clone(), counter);
        }
    }
}
