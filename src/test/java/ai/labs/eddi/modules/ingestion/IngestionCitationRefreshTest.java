/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion;

import ai.labs.eddi.configs.rag.model.IngestionSource;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
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
import ai.labs.eddi.modules.ingestion.connector.WorkItem.Citation;
import ai.labs.eddi.modules.ingestion.extract.DocumentExtractors;
import ai.labs.eddi.modules.ingestion.extract.PlainTextExtractor;
import ai.labs.eddi.modules.llm.impl.EmbeddingModelFactory;
import ai.labs.eddi.modules.llm.impl.EmbeddingStoreFactory;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.request.EmbeddingInputType;
import dev.langchain4j.model.output.Response;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A document renamed at its source without a change to its content: its chunks
 * get the new title and URL as their citation, without being embedded again
 * where the store can update metadata in place, and by embedding that one
 * document again where it cannot.
 */
class IngestionCitationRefreshTest {

    private static final String KB_RESOURCE_ID = "5a8b1c2d3e4f5a6b7c8d9e0f";

    private InMemoryIngestionStateStore stateStore;
    private RecordingEmbeddingStore embeddingStore;
    private EmbeddingStoreFactory storeFactory;
    private EmbeddingModelFactory modelFactory;
    private final AtomicInteger embedCalls = new AtomicInteger();
    private Versioned connector;

    @BeforeEach
    void setUp() {
        stateStore = new InMemoryIngestionStateStore();
        embeddingStore = new RecordingEmbeddingStore();
        storeFactory = mock(EmbeddingStoreFactory.class);
        modelFactory = mock(EmbeddingModelFactory.class);
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(storeFactory.getOrCreate(anyString(), any(RagConfiguration.class))).thenAnswer(i -> embeddingStore);
        when(storeFactory.getOrCreateForIngestion(anyString(), any(RagConfiguration.class))).thenAnswer(i -> embeddingStore);
        when(modelFactory.getOrCreate(any(RagConfiguration.class), any(EmbeddingInputType.class))).thenReturn(model);
        when(model.embedAll(any())).thenAnswer(invocation -> {
            embedCalls.incrementAndGet();
            List<TextSegment> segments = invocation.getArgument(0);
            return Response.from(segments.stream().map(segment -> Embedding.from(new float[]{0.1f})).toList());
        });
        connector = new Versioned();
    }

    /**
     * One file, listed with a version token and its citation, the way the Drive and
     * upload connectors list a stored file.
     */
    private static class Versioned implements SourceConnector {
        String name = "notes.txt";
        String link = "https://drive.example/notes";
        String version = "md5:aaa";
        boolean withCitation = true;
        final List<String> fetched = new ArrayList<>();

        @Override
        public String type() {
            return IngestionSource.TYPE_WEB;
        }

        @Override
        public List<Partition> listPartitions(SourceContext context) {
            return List.of(Partition.IMPLICIT);
        }

        @Override
        public DiscoveryResult discover(DiscoveryContext context) {
            context.accept(WorkItem.upsert("f1", version, context.partition(), Map.of(),
                    withCitation ? new Citation(name, link) : null));
            return new DiscoveryResult(StopReason.COMPLETED, Coverage.FULL_LISTING, null, null, null);
        }

        @Override
        public SourceDocument fetch(WorkItem item, SourceContext context) {
            fetched.add(item.documentId());
            return new SourceDocument(item.documentId(),
                    new Content.FileBytes("Travel is booked through the portal.".getBytes(StandardCharsets.UTF_8),
                            "text/plain"),
                    item.versionToken(), null, false,
                    Map.of(SourceMetadataKeys.TITLE, name, SourceMetadataKeys.CITATION_URL, link));
        }
    }

    private IngestionPipeline pipeline(SourceConnector sourceConnector) {
        return new IngestionPipeline(SourceConnectors.of(sourceConnector), new HtmlToMarkdownConverter(), stateStore,
                new DocumentExtractors(List.of(new PlainTextExtractor())), modelFactory, storeFactory,
                new SimpleMeterRegistry());
    }

    private static RagConfiguration knowledgeBase() {
        RagConfiguration config = new RagConfiguration();
        config.setName("kb");
        config.setChunkSize(400);
        config.setChunkOverlap(0);
        return config;
    }

    private static IngestionSource source() {
        var web = new IngestionSource.WebSource();
        web.setStartUrl("https://example.com/");
        var source = new IngestionSource();
        source.setId("src-1");
        source.setName("files");
        source.setWeb(web);
        return source;
    }

    private IngestionReport run(SourceConnector sourceConnector, Mode mode) {
        return pipeline(sourceConnector).run(KB_RESOURCE_ID, knowledgeBase(), source(), mode);
    }

    private IngestionReport run() {
        return run(connector, Mode.INGEST);
    }

    private String key() {
        return IngestionPipeline.stateKey(KB_RESOURCE_ID, source());
    }

    private TextSegment onlyChunk() {
        List<TextSegment> chunks = embeddingStore.segmentsOf("f1");
        assertEquals(1, chunks.size(), "one document, one chunk, no duplicate");
        return chunks.getFirst();
    }

    @Test
    @DisplayName("a renamed file with the same bytes gets its new title and link, unfetched and unembedded")
    void renameUpdatesTheCitationInPlace() {
        assertEquals(1, run().documentsIngested());
        String firstRun = onlyChunk().metadata().getString(IngestionPipeline.METADATA_RUN_ID);
        connector.name = "policy.txt";
        connector.link = "https://drive.example/policy";

        IngestionReport renamed = run();

        assertEquals(0, renamed.documentsIngested());
        assertEquals(1, renamed.documentsUnchanged());
        assertEquals(1, connector.fetched.size(), "fetched once, on the first run only");
        assertEquals(1, embedCalls.get(), "embedded once, on the first run only");
        TextSegment chunk = onlyChunk();
        assertEquals("policy.txt", chunk.metadata().getString(IngestionPipeline.METADATA_TITLE));
        assertEquals("https://drive.example/policy", chunk.metadata().getString(IngestionPipeline.METADATA_URL));
        // The orphan sweep and a removed source's cleanup both key on the run id.
        assertEquals(firstRun, chunk.metadata().getString(IngestionPipeline.METADATA_RUN_ID));
        assertEquals(1, embeddingStore.metadataUpdates());

        run();
        assertEquals(1, embeddingStore.metadataUpdates(), "a recorded citation is not written again");
    }

    @Test
    @DisplayName("an unchanged file costs no metadata write, run after run")
    void unchangedCostsNothing() {
        run();
        run();
        run();
        assertEquals(0, embeddingStore.metadataUpdates());
        assertEquals(1, embedCalls.get());
    }

    @Test
    @DisplayName("a store that cannot update metadata embeds the renamed document once more, then rests")
    void fallbackReembedsThatDocument() {
        embeddingStore.withoutMetadataUpdates();
        run();
        connector.name = "policy.txt";

        IngestionReport renamed = run();

        assertEquals(1, renamed.documentsIngested());
        assertEquals(2, connector.fetched.size());
        assertEquals("policy.txt", onlyChunk().metadata().getString(IngestionPipeline.METADATA_TITLE));

        IngestionReport after = run();
        assertEquals(0, after.documentsIngested());
        assertEquals(2, connector.fetched.size(), "the new citation was recorded with the re-embedding");
    }

    @Test
    @DisplayName("a row from before citations were recorded adopts the current one instead of rewriting")
    void legacyRowIsAdopted() {
        String runId = stateStore.startRun(key()).orElseThrow();
        stateStore.recordIngested(key(), "f1", "md5:aaa", null, null, runId);
        stateStore.finishRun(new IIngestionStateStore.IngestionRun(runId, key(),
                IIngestionStateStore.IngestionRun.Status.COMPLETED, null, null, 0, 0, 0, 0, 0, 0, 0.0, null));
        assertNull(stateStore.lookup(key(), "f1").orElseThrow().citationHash());

        IngestionReport report = run();

        assertEquals(1, report.documentsUnchanged());
        assertEquals(0, embeddingStore.metadataUpdates());
        assertEquals(ContentHashes.citation("notes.txt", "https://drive.example/notes"),
                stateStore.lookup(key(), "f1").orElseThrow().citationHash());
    }

    @Test
    @DisplayName("a connector that does not know the citation before fetching is left as it was")
    void noCitationNoChange() {
        connector.withCitation = false;
        run();
        connector.name = "policy.txt";

        IngestionReport second = run();

        assertEquals(1, second.documentsUnchanged());
        assertEquals(1, connector.fetched.size());
        assertEquals(0, embeddingStore.metadataUpdates());
    }

    @Test
    @DisplayName("a preview reports a rename as unchanged and writes nothing")
    void previewWritesNothing() {
        run();
        connector.name = "policy.txt";

        IngestionReport preview = run(connector, Mode.PREVIEW);

        assertEquals(1, preview.documentsUnchanged());
        assertEquals(0, embeddingStore.metadataUpdates());
        assertEquals("notes.txt", onlyChunk().metadata().getString(IngestionPipeline.METADATA_TITLE));
    }

    @Test
    @DisplayName("fetched content that is unchanged but carries a new title updates the citation too")
    void unchangedContentWithNewTitle() {
        var pages = new Versioned() {
            @Override
            public DiscoveryResult discover(DiscoveryContext context) {
                context.accept(WorkItem.prefetched(new SourceDocument("f1", new Content.Text("Same body"), null, null,
                        false, Map.of(SourceMetadataKeys.TITLE, name, SourceMetadataKeys.CITATION_URL, link)),
                        context.partition()));
                return new DiscoveryResult(StopReason.COMPLETED, Coverage.FULL_LISTING, null, null, null);
            }
        };
        run(pages, Mode.INGEST);
        pages.name = "A page with a new <title>";

        IngestionReport second = run(pages, Mode.INGEST);

        assertEquals(0, second.documentsIngested());
        assertEquals(1, embedCalls.get());
        assertEquals("A page with a new <title>", onlyChunk().metadata().getString(IngestionPipeline.METADATA_TITLE));
    }

    @Test
    @DisplayName("a title longer than the cap is compared as stored, so it is not rewritten every run")
    void longTitleIsComparedAsStored() {
        connector.name = "x".repeat(500);
        run();
        run();
        run();
        assertEquals(0, embeddingStore.metadataUpdates());
    }
}
