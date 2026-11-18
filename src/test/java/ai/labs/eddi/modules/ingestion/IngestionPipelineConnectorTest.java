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
import ai.labs.eddi.modules.ingestion.connector.SourceError;
import ai.labs.eddi.modules.ingestion.connector.SourceException;
import ai.labs.eddi.modules.ingestion.connector.SourceMetadataKeys;
import ai.labs.eddi.modules.ingestion.connector.StopReason;
import ai.labs.eddi.modules.ingestion.connector.WorkItem;
import ai.labs.eddi.modules.ingestion.crawl.WebCrawler;
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
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The connector seam of {@link IngestionPipeline}: what the framework does with
 * each kind of work item, content and coverage — the paths the two built-in
 * sources do not exercise. The built-in sources' behaviour is pinned by
 * {@code IngestionPipelineTest}, unchanged.
 */
class IngestionPipelineConnectorTest {

    private static final String KB_RESOURCE_ID = "5a8b1c2d3e4f5a6b7c8d9e0f";

    private InMemoryIngestionStateStore stateStore;
    private RecordingEmbeddingStore embeddingStore;
    private EmbeddingStoreFactory storeFactory;
    private EmbeddingModelFactory modelFactory;

    @BeforeEach
    void setUp() {
        stateStore = new InMemoryIngestionStateStore();
        embeddingStore = new RecordingEmbeddingStore();
        storeFactory = mock(EmbeddingStoreFactory.class);
        modelFactory = mock(EmbeddingModelFactory.class);
        EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
        when(storeFactory.getOrCreate(anyString(), any(RagConfiguration.class))).thenReturn(embeddingStore);
        when(storeFactory.getOrCreateForIngestion(anyString(), any(RagConfiguration.class)))
                .thenReturn(embeddingStore);
        when(modelFactory.getOrCreate(any(RagConfiguration.class), any(EmbeddingInputType.class)))
                .thenReturn(embeddingModel);
        when(embeddingModel.embedAll(any())).thenAnswer(invocation -> {
            List<TextSegment> segments = invocation.getArgument(0);
            return Response.from(segments.stream().map(segment -> Embedding.from(new float[]{0.1f})).toList());
        });
    }

    /**
     * A scripted connector. Registered as the {@code web} type only because a
     * source must pass validation; the framework never looks at the type again.
     */
    private static class Scripted implements SourceConnector {
        Consumer<DiscoveryContext> discovery = context -> {
        };
        Coverage coverage = Coverage.FULL_LISTING;
        List<Partition> partitions = List.of(Partition.IMPLICIT);
        SourceException discoveryFailure;
        SourceException fetchFailure;
        final List<String> fetched = new ArrayList<>();

        @Override
        public String type() {
            return IngestionSource.TYPE_WEB;
        }

        @Override
        public List<Partition> listPartitions(SourceContext context) {
            return partitions;
        }

        @Override
        public DiscoveryResult discover(DiscoveryContext context) throws SourceException {
            if (discoveryFailure != null) {
                throw discoveryFailure;
            }
            discovery.accept(context);
            return DiscoveryResult.of(StopReason.COMPLETED, coverage);
        }

        @Override
        public SourceDocument fetch(WorkItem item, SourceContext context) throws SourceException {
            fetched.add(item.documentId());
            if (fetchFailure != null) {
                throw fetchFailure;
            }
            return new SourceDocument(item.documentId(),
                    new Content.FileBytes(("Text of " + item.documentId()).getBytes(StandardCharsets.UTF_8),
                            "text/plain"),
                    item.versionToken(), null, false,
                    Map.of(SourceMetadataKeys.TITLE, "Title " + item.documentId(),
                            SourceMetadataKeys.CITATION_URL, "doc:" + item.documentId()));
        }
    }

    private IngestionPipeline pipeline(SourceConnector connector) {
        return new IngestionPipeline(SourceConnectors.of(connector), new HtmlToMarkdownConverter(), stateStore,
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
        source.setName("scripted");
        source.setWeb(web);
        var settings = new IngestionSource.IngestionSettings();
        settings.setTombstoneAfterMissedRuns(1);
        source.setSettings(settings);
        return source;
    }

    private static SourceDocument text(String id, String body) {
        return new SourceDocument(id, new Content.Text(body), null, null, false,
                Map.of(SourceMetadataKeys.TITLE, "T-" + id, SourceMetadataKeys.CITATION_URL, "doc:" + id));
    }

    private IngestionReport run(Scripted connector) {
        return pipeline(connector).run(KB_RESOURCE_ID, knowledgeBase(), source(), Mode.INGEST);
    }

    @Test
    @DisplayName("prefetched text is embedded with its title and citation, and is unchanged next time")
    void prefetchedText() {
        var connector = new Scripted();
        connector.discovery = context -> context.accept(WorkItem.prefetched(text("a", "Alpha body"),
                context.partition()));

        IngestionReport first = run(connector);
        assertEquals(1, first.documentsIngested());
        TextSegment segment = embeddingStore.segmentsOf("a").get(0);
        assertEquals("T-a", segment.metadata().getString(IngestionPipeline.METADATA_TITLE));
        assertEquals("doc:a", segment.metadata().getString(IngestionPipeline.METADATA_URL));

        IngestionReport second = run(connector);
        assertEquals(0, second.documentsIngested());
        assertEquals(1, second.documentsUnchanged());
        assertTrue(connector.fetched.isEmpty(), "a prefetched document is never fetched");
    }

    @Test
    @DisplayName("blank text is skipped, not failed")
    void blankTextSkipped() {
        var connector = new Scripted();
        connector.discovery = context -> context.accept(WorkItem.prefetched(text("a", "  "), context.partition()));

        IngestionReport report = run(connector);
        assertEquals(1, report.documentsSkipped());
        assertEquals(0, report.documentsFailed());
    }

    @Test
    @DisplayName("a matching version token skips the fetch entirely")
    void versionTokenSkipsFetch() {
        var connector = new Scripted();
        connector.discovery = context -> context.accept(WorkItem.upsert("f", "rev-1", context.partition(), null));

        assertEquals(1, run(connector).documentsIngested());
        assertEquals(List.of("f"), connector.fetched);

        IngestionReport second = run(connector);
        assertEquals(1, second.documentsUnchanged());
        assertEquals(List.of("f"), connector.fetched, "not fetched again");

        connector.discovery = context -> context.accept(WorkItem.upsert("f", "rev-2", context.partition(), null));
        assertEquals(1, run(connector).documentsIngested());
        assertEquals(List.of("f", "f"), connector.fetched);
    }

    @Test
    @DisplayName("a document gone by fetch time is skipped silently; a transient failure keeps its chunks")
    void fetchFailures() {
        var connector = new Scripted();
        connector.discovery = context -> context.accept(WorkItem.upsert("f", "rev-1", context.partition(), null));
        run(connector);

        connector.discovery = context -> context.accept(WorkItem.upsert("f", "rev-2", context.partition(), null));
        connector.fetchFailure = new SourceException(new SourceError.NotFound("gone"));
        // Partial, so this run's miss is not reconciled: what is pinned is that
        // nothing is counted.
        connector.coverage = Coverage.PARTIAL;
        IngestionReport vanished = run(connector);
        assertEquals(0, vanished.documentsFailed());
        assertEquals(0, vanished.documentsSeen());

        connector.fetchFailure = new SourceException(new SourceError.Transient("timeout"));
        // A complete listing: the document would be tombstoned as missed if the
        // failure did not count as having looked at it.
        connector.coverage = Coverage.FULL_LISTING;
        IngestionReport failed = run(connector);
        assertEquals(1, failed.documentsFailed());
        assertEquals(0, failed.documentsTombstoned(), "could not look is not gone");
        assertFalse(embeddingStore.segmentsOf("f").isEmpty());
        assertFalse(stateStore.lookup(IngestionPipeline.stateKey(KB_RESOURCE_ID, source()), "f").orElseThrow()
                .tombstoned());
    }

    @Test
    @DisplayName("a DELETE item removes the document's chunks and tombstones it at once")
    void explicitDelete() {
        var connector = new Scripted();
        connector.discovery = context -> context.accept(WorkItem.prefetched(text("a", "Alpha"), context.partition()));
        run(connector);
        assertFalse(embeddingStore.segmentsOf("a").isEmpty());

        connector.coverage = Coverage.INCREMENTAL;
        connector.discovery = context -> context.accept(WorkItem.delete("a", context.partition()));
        IngestionReport report = run(connector);

        assertEquals(1, report.documentsTombstoned());
        assertTrue(embeddingStore.segmentsOf("a").isEmpty());
        assertTrue(stateStore.lookup(IngestionPipeline.stateKey(KB_RESOURCE_ID, source()), "a").orElseThrow()
                .tombstoned());
    }

    @Test
    @DisplayName("under INCREMENTAL coverage absence means nothing, and nothing is reported skipped")
    void incrementalNeverReconcilesAbsence() {
        var connector = new Scripted();
        connector.discovery = context -> context.accept(WorkItem.prefetched(text("a", "Alpha"), context.partition()));
        run(connector);

        connector.coverage = Coverage.INCREMENTAL;
        connector.discovery = context -> {
        };
        IngestionReport report = run(connector);

        assertEquals(0, report.documentsTombstoned());
        assertFalse(report.tombstoningSkipped());
        assertFalse(embeddingStore.segmentsOf("a").isEmpty());
    }

    @Test
    @DisplayName("a FULL_LISTING without the document tombstones it; PARTIAL does not")
    void fullListingReconciles() {
        var connector = new Scripted();
        connector.discovery = context -> context.accept(WorkItem.prefetched(text("a", "Alpha"), context.partition()));
        run(connector);

        connector.discovery = context -> {
        };
        connector.coverage = Coverage.PARTIAL;
        IngestionReport partial = run(connector);
        assertTrue(partial.tombstoningSkipped());
        assertEquals(0, partial.documentsTombstoned());

        connector.coverage = Coverage.FULL_LISTING;
        IngestionReport full = run(connector);
        assertEquals(1, full.documentsTombstoned());
        assertTrue(embeddingStore.segmentsOf("a").isEmpty());
    }

    @Test
    @DisplayName("a request the provider rejects as made keeps the document, even under a complete listing")
    void rejectedRequestKeepsTheDocument() {
        // A malformed request is a fault in the connector, not an answer about the
        // document. Were it definitive, a connector bug or an API change would
        // tombstone every document it touched on the next complete listing.
        var connector = new Scripted();
        connector.discovery = context -> context.accept(WorkItem.upsert("f", "rev-1", context.partition(), null));
        run(connector);

        connector.discovery = context -> context.accept(WorkItem.upsert("f", "rev-2", context.partition(), null));
        connector.fetchFailure = new SourceException(new SourceError.Rejected("400 invalid field mask"));
        connector.coverage = Coverage.FULL_LISTING;
        IngestionReport report = run(connector);

        assertEquals(1, report.documentsFailed());
        assertEquals(0, report.documentsTombstoned());
        assertFalse(embeddingStore.segmentsOf("f").isEmpty());
        assertFalse(stateStore.lookup(IngestionPipeline.stateKey(KB_RESOURCE_ID, source()), "f").orElseThrow()
                .tombstoned());
    }

    @Test
    @DisplayName("a FULL_TRAVERSAL that met only a source-level failure concludes nothing")
    void sourceLevelFailureIsNoTraversalEvidence() {
        // The guard against a blank site used to read "failed minus unreachable" as
        // definitive answers. A failure with no document id was counted as failed but
        // never as unreachable, so a traversal that learned nothing about any document
        // passed for one that saw the corpus gone, and reconciled it away.
        var connector = new Scripted();
        connector.coverage = Coverage.FULL_TRAVERSAL;
        connector.discovery = context -> context.accept(WorkItem.prefetched(text("a", "Alpha"), context.partition()));
        run(connector);

        // Permanent is definitive about a document, but this one names none.
        connector.discovery = context -> context.reportFailure(null, "robots.txt",
                new SourceError.Permanent("the whole site refused"));
        IngestionReport report = run(connector);

        assertEquals(1, report.documentsFailed());
        assertEquals(0, report.documentsTombstoned());
        assertTrue(report.tombstoningSkipped(), "the run must say it concluded nothing");
        assertFalse(embeddingStore.segmentsOf("a").isEmpty());
    }

    @Test
    @DisplayName("a FULL_TRAVERSAL whose only answer is a document-level 404 still reconciles")
    void documentLevelGoneIsTraversalEvidence() {
        // The other side of the guard: a site whose pages are genuinely gone yields
        // nothing usable either, and that is exactly when absence must count.
        var connector = new Scripted();
        connector.coverage = Coverage.FULL_TRAVERSAL;
        connector.discovery = context -> context.accept(WorkItem.prefetched(text("a", "Alpha"), context.partition()));
        run(connector);

        connector.discovery = context -> context.reportFailure("b", "doc:b", new SourceError.NotFound("404"));
        IngestionReport report = run(connector);

        assertFalse(report.tombstoningSkipped());
        assertEquals(1, report.documentsTombstoned());
        assertTrue(embeddingStore.segmentsOf("a").isEmpty());
    }

    @Test
    @DisplayName("a source-level failure fails the run with its reason and releases the claim")
    void discoveryFailure() {
        var connector = new Scripted();
        connector.discoveryFailure = new SourceException(new SourceError.Auth("The grant was revoked"));

        IngestionReport report = run(connector);

        assertEquals(IngestionReport.Outcome.FAILED, report.outcome());
        assertEquals("The grant was revoked", report.message());
        assertTrue(stateStore.activeRun(IngestionPipeline.stateKey(KB_RESOURCE_ID, source())).isEmpty());
    }

    @Test
    @DisplayName("more than one partition is refused rather than half-run")
    void severalPartitionsRefused() {
        var connector = new Scripted();
        connector.partitions = List.of(new Partition("a", null), new Partition("b", null));

        IngestionReport report = run(connector);

        assertEquals(IngestionReport.Outcome.FAILED, report.outcome());
        assertTrue(report.message().contains("2 partitions"), report.message());
    }

    @Test
    @DisplayName("the connector's stop reason reaches the report under the same name")
    void stopReasonMapped() {
        var connector = new Scripted() {
            @Override
            public DiscoveryResult discover(DiscoveryContext context) {
                return DiscoveryResult.of(StopReason.BYTE_LIMIT, Coverage.PARTIAL);
            }
        };
        assertEquals(WebCrawler.StopReason.BYTE_LIMIT, run(connector).stopReason());
    }
}
