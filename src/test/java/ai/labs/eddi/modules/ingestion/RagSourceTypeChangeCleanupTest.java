/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion;

import ai.labs.eddi.configs.rag.IRagStore;
import ai.labs.eddi.configs.rag.model.IngestionSource;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.modules.ingestion.IIngestionStateStore.IngestionRun;
import ai.labs.eddi.modules.ingestion.IngestionPipeline.IngestionReport;
import ai.labs.eddi.modules.ingestion.IngestionPipeline.Mode;
import ai.labs.eddi.modules.ingestion.connector.Coverage;
import ai.labs.eddi.modules.ingestion.connector.DiscoveryContext;
import ai.labs.eddi.modules.ingestion.connector.DiscoveryResult;
import ai.labs.eddi.modules.ingestion.connector.Partition;
import ai.labs.eddi.modules.ingestion.connector.SourceConnector;
import ai.labs.eddi.modules.ingestion.connector.SourceConnectors;
import ai.labs.eddi.modules.ingestion.connector.SourceContext;
import ai.labs.eddi.modules.ingestion.connector.SourceDocument;
import ai.labs.eddi.modules.ingestion.connector.SourceDocument.Content;
import ai.labs.eddi.modules.ingestion.connector.SourceMetadataKeys;
import ai.labs.eddi.modules.ingestion.connector.StopReason;
import ai.labs.eddi.modules.ingestion.connector.WorkItem;
import ai.labs.eddi.modules.ingestion.extract.DocumentExtractors;
import ai.labs.eddi.modules.ingestion.extract.PlainTextExtractor;
import ai.labs.eddi.modules.ingestion.files.InMemoryIngestedFileStore;
import ai.labs.eddi.modules.llm.impl.EmbeddingModelFactory;
import ai.labs.eddi.modules.llm.impl.EmbeddingStoreFactory;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.request.EmbeddingInputType;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A source that changes type while a run of the old type is still going, and
 * the cleanup of that run interleaved with the first run of the new type.
 *
 * <p>
 * The save clears the source's state, which frees its run slot, so the new
 * type's first run can start before the old run has stopped. The old run's
 * cleanup used to forget everything under the shared source key — the new run's
 * chunks and its running row included. Real pipeline, real state store, and a
 * vector store that evaluates delete filters, so the test sees which chunks
 * survive rather than which method was called.
 */
class RagSourceTypeChangeCleanupTest {

    private static final String KB_ID = "5a8b1c2d3e4f5a6b7c8d9e0f";
    private static final String SOURCE_ID = "src-1";

    private InMemoryIngestionStateStore stateStore;
    private RecordingEmbeddingStore chunks;
    private HookedStore embeddingStore;
    private IRagStore ragStore;
    private Scripted connector;
    private IngestionPipeline pipeline;
    private RagSourceIngestionService service;

    @BeforeEach
    void setUp() throws Exception {
        stateStore = new InMemoryIngestionStateStore();
        chunks = new RecordingEmbeddingStore();
        embeddingStore = new HookedStore(chunks);
        var storeFactory = mock(EmbeddingStoreFactory.class);
        when(storeFactory.getOrCreate(anyString(), any(RagConfiguration.class))).thenReturn(embeddingStore);
        when(storeFactory.getOrCreateForIngestion(anyString(), any(RagConfiguration.class))).thenReturn(embeddingStore);
        var modelFactory = mock(EmbeddingModelFactory.class);
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(modelFactory.getOrCreate(any(RagConfiguration.class), any(EmbeddingInputType.class))).thenReturn(model);
        when(model.embedAll(any())).thenAnswer(invocation -> {
            List<TextSegment> segments = invocation.getArgument(0);
            return Response.from(segments.stream().map(segment -> Embedding.from(new float[]{0.1f})).toList());
        });
        connector = new Scripted();
        pipeline = new IngestionPipeline(SourceConnectors.of(connector), new HtmlToMarkdownConverter(), stateStore,
                new DocumentExtractors(List.of(new PlainTextExtractor())), modelFactory, storeFactory,
                new SimpleMeterRegistry());
        ragStore = mock(IRagStore.class);
        service = new RagSourceIngestionService(pipeline, stateStore, mock(IScheduleStore.class), ragStore,
                new InMemoryIngestedFileStore());
        when(ragStore.getCurrentResourceId(KB_ID)).thenReturn(resourceId(2));
        when(ragStore.read(KB_ID, 2)).thenReturn(knowledgeBase(newTypeSource()));
    }

    /** The new type's connector: one document per run, named by the test. */
    private static final class Scripted implements SourceConnector {
        Consumer<DiscoveryContext> discovery = context -> {
        };

        @Override
        public String type() {
            return IngestionSource.TYPE_UPLOAD;
        }

        @Override
        public List<Partition> listPartitions(SourceContext context) {
            return List.of(Partition.IMPLICIT);
        }

        @Override
        public DiscoveryResult discover(DiscoveryContext context) {
            discovery.accept(context);
            return new DiscoveryResult(StopReason.COMPLETED, Coverage.FULL_LISTING, null, null, null);
        }

        @Override
        public SourceDocument fetch(WorkItem item, SourceContext context) {
            throw new UnsupportedOperationException("documents are prefetched");
        }
    }

    /** Delegates to the recording store, running a hook before every delete. */
    private static final class HookedStore implements EmbeddingStore<TextSegment> {
        private final RecordingEmbeddingStore delegate;
        Runnable beforeRemove = () -> {
        };

        HookedStore(RecordingEmbeddingStore delegate) {
            this.delegate = delegate;
        }

        @Override
        public String add(Embedding embedding) {
            return delegate.add(embedding);
        }

        @Override
        public void add(String id, Embedding embedding) {
            delegate.add(id, embedding);
        }

        @Override
        public String add(Embedding embedding, TextSegment segment) {
            return delegate.add(embedding, segment);
        }

        @Override
        public List<String> addAll(List<Embedding> embeddings) {
            return delegate.addAll(embeddings);
        }

        @Override
        public List<String> addAll(List<Embedding> embeddings, List<TextSegment> segments) {
            return delegate.addAll(embeddings, segments);
        }

        @Override
        public void removeAll(Filter filter) {
            beforeRemove.run();
            delegate.removeAll(filter);
        }

        @Override
        public void removeAll(Collection<String> ids) {
            delegate.removeAll(ids);
        }

        @Override
        public void removeAll() {
            delegate.removeAll();
        }

        @Override
        public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest request) {
            return delegate.search(request);
        }
    }

    private static IngestionSource oldTypeSource() {
        var web = new IngestionSource.WebSource();
        web.setStartUrl("https://example.com/");
        var source = new IngestionSource();
        source.setId(SOURCE_ID);
        source.setName("handbooks");
        source.setWeb(web);
        return source;
    }

    private static IngestionSource newTypeSource() {
        var source = new IngestionSource();
        source.setId(SOURCE_ID);
        source.setName("handbooks");
        source.setType(IngestionSource.TYPE_UPLOAD);
        return source;
    }

    private static RagConfiguration knowledgeBase(IngestionSource source) {
        var config = new RagConfiguration();
        config.setName("product-docs");
        config.setChunkSize(400);
        config.setChunkOverlap(0);
        config.setSources(List.of(source));
        return config;
    }

    private static String key() {
        return IngestionPipeline.stateKey(KB_ID, oldTypeSource());
    }

    private static SourceDocument document(String id) {
        return new SourceDocument(id, new Content.Text("Text of " + id), null, null, false,
                Map.of(SourceMetadataKeys.TITLE, id, SourceMetadataKeys.CITATION_URL, "doc:" + id));
    }

    /** A chunk as the old run's pipeline would have written it. */
    private void chunkWrittenBy(String runId, String documentId) {
        Metadata metadata = Metadata.from(IngestionPipeline.METADATA_DOCUMENT_ID, documentId)
                .put(IngestionPipeline.METADATA_SOURCE_KEY, key())
                .put(IngestionPipeline.METADATA_RUN_ID, runId);
        chunks.addAll(List.of(Embedding.from(new float[]{0.1f})), List.of(TextSegment.from("old " + documentId, metadata)));
    }

    /**
     * The old run up to the save and one document past it: it claimed the source,
     * the save that changed the type removed what it had and freed its slot, and it
     * embedded and recorded one more document before it noticed.
     */
    private String oldRunOutlivesTheSave() {
        String oldRun = stateStore.startRun(key()).orElseThrow();
        chunkWrittenBy(oldRun, "https://example.com/before");
        stateStore.recordIngested(key(), "https://example.com/before", "h0", null, null, oldRun);

        service.discardRemovedSources(KB_ID, knowledgeBase(oldTypeSource()), knowledgeBase(newTypeSource()));

        chunkWrittenBy(oldRun, "https://example.com/late");
        stateStore.recordIngested(key(), "https://example.com/late", "h1", null, null, oldRun);
        return oldRun;
    }

    private IngestionReport runNewType(Consumer<DiscoveryContext> discovery) {
        connector.discovery = discovery;
        return pipeline.run(KB_ID, knowledgeBase(newTypeSource()), newTypeSource(), Mode.INGEST);
    }

    @Test
    @DisplayName("cleanup while the new type's run holds the source keeps that run's chunks, state and claim")
    void cleanupDuringTheNewRunKeepsItsChunks() {
        String oldRun = oldRunOutlivesTheSave();
        List<String> newRuns = new ArrayList<>();

        IngestionReport report = runNewType(context -> {
            newRuns.add(stateStore.activeRun(key()).orElseThrow().runId());
            context.accept(WorkItem.prefetched(document("file-1"), context.partition()));
            // The old run stops here, while the new one is between two documents.
            service.cleanUpAfterRun(KB_ID, knowledgeBase(oldTypeSource()), oldTypeSource(), oldRun);
            context.accept(WorkItem.prefetched(document("file-2"), context.partition()));
        });

        assertEquals(IngestionReport.Outcome.COMPLETED, report.outcome(), String.valueOf(report.message()));
        assertEquals(2, report.documentsIngested(), "the new run was not stopped half way");
        assertEquals(1, chunks.segmentsOf("file-1").size(), "the new run's chunk written before the cleanup survives");
        assertEquals(1, chunks.segmentsOf("file-2").size());
        assertTrue(chunks.segmentsOf("https://example.com/late").isEmpty(), "what the old run wrote late is gone");
        assertTrue(stateStore.lookup(key(), "file-2").isPresent(), "the new run's state is still there");
        assertEquals(IngestionRun.Status.COMPLETED, stateStore.listRuns(key(), 5).getFirst().status());
        assertEquals(newRuns.getFirst(), stateStore.listRuns(key(), 5).getFirst().runId());
        // The old run's late row can have been taken over by the new run; its content
        // is forgotten so nothing can call it unchanged over the chunks just removed.
        assertTrue(stateStore.lookup(key(), "https://example.com/late").orElseThrow().hasChanged("h1"));
    }

    @Test
    @DisplayName("cleanup after the new type's run has finished still keeps that run's chunks")
    void cleanupAfterTheNewRunKeepsItsChunks() {
        String oldRun = oldRunOutlivesTheSave();
        IngestionReport report = runNewType(
                context -> context.accept(WorkItem.prefetched(document("file-1"), context.partition())));
        assertEquals(IngestionReport.Outcome.COMPLETED, report.outcome());

        // The slot is free now, so the cleanup's claim succeeds — which alone used to
        // be read as "nobody else has this source".
        service.cleanUpAfterRun(KB_ID, knowledgeBase(oldTypeSource()), oldTypeSource(), oldRun);

        assertEquals(1, chunks.segmentsOf("file-1").size());
        assertTrue(chunks.segmentsOf("https://example.com/late").isEmpty());
        assertTrue(stateStore.lookup(key(), "file-1").isPresent());
        assertTrue(stateStore.activeRun(key()).isEmpty(), "the cleanup's claim is released");
        assertEquals(Optional.of(report.runId()), stateStore.listRuns(key(), 5).stream().findFirst().map(IngestionRun::runId),
                "the new run's history is kept");
    }

    @Test
    @DisplayName("with no run since, the whole source is forgotten, and a run trying to start meanwhile is refused")
    void noRunSinceForgetsEverything() {
        String oldRun = oldRunOutlivesTheSave();
        List<Optional<String>> attempts = new ArrayList<>();
        embeddingStore.beforeRemove = () -> attempts.add(pipeline.reserveRun(KB_ID, newTypeSource()));

        service.cleanUpAfterRun(KB_ID, knowledgeBase(oldTypeSource()), oldTypeSource(), oldRun);

        assertTrue(chunks.segments().isEmpty(), "everything under the key was the old run's");
        assertTrue(stateStore.listDocuments(key(), 10).isEmpty());
        assertEquals(List.of(Optional.empty()), attempts, "the cleanup holds the slot while it deletes");
        assertTrue(stateStore.activeRun(key()).isEmpty(), "and gives it back afterwards");
        assertTrue(pipeline.reserveRun(KB_ID, newTypeSource()).isPresent());
    }

    @Test
    @DisplayName("a cleanup whose vector delete fails does not leave the source blocked")
    void aFailedDeleteReleasesTheClaim() {
        String oldRun = oldRunOutlivesTheSave();
        embeddingStore.beforeRemove = () -> {
            throw new IllegalStateException("vector store is unwell");
        };

        service.cleanUpAfterRun(KB_ID, knowledgeBase(oldTypeSource()), oldTypeSource(), oldRun);

        assertTrue(stateStore.activeRun(key()).isEmpty());
    }

    private static IResourceStore.IResourceId resourceId(int version) {
        return new IResourceStore.IResourceId() {
            @Override
            public String getId() {
                return KB_ID;
            }

            @Override
            public Integer getVersion() {
                return version;
            }
        };
    }
}
