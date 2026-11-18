/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion;

import ai.labs.eddi.configs.rag.model.IngestionSource;
import ai.labs.eddi.configs.rag.model.IngestionSource.GoogleDriveSource;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.connections.ResolvedCredential;
import ai.labs.eddi.modules.ingestion.IngestionPipeline.IngestionReport;
import ai.labs.eddi.modules.ingestion.IngestionPipeline.Mode;
import ai.labs.eddi.modules.ingestion.connector.SourceConnectors;
import ai.labs.eddi.modules.ingestion.drive.FakeDrive;
import ai.labs.eddi.modules.ingestion.drive.GoogleDriveSourceConnector;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The Drive connector through the real pipeline and state store: what a second
 * run costs, what a version bump costs, and what an outage does to the
 * knowledge base. The connector's own tests feed it stored versions by hand;
 * these read them back from where the pipeline wrote them.
 */
class GoogleDriveIngestionPipelineTest {

    private static final String KB_RESOURCE_ID = "5a8b1c2d3e4f5a6b7c8d9e0f";
    private static final String DRIVE = "0AdriveAAA";

    private FakeDrive drive;
    private InMemoryIngestionStateStore stateStore;
    private RecordingEmbeddingStore embeddingStore;
    private IngestionPipeline pipeline;
    private IngestionSource source;

    @BeforeEach
    void setUp() {
        drive = new FakeDrive(DRIVE);
        stateStore = new InMemoryIngestionStateStore();
        embeddingStore = new RecordingEmbeddingStore();
        EmbeddingStoreFactory storeFactory = mock(EmbeddingStoreFactory.class);
        EmbeddingModelFactory modelFactory = mock(EmbeddingModelFactory.class);
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(storeFactory.getOrCreate(anyString(), any(RagConfiguration.class))).thenReturn(embeddingStore);
        when(storeFactory.getOrCreateForIngestion(anyString(), any(RagConfiguration.class))).thenReturn(embeddingStore);
        when(modelFactory.getOrCreate(any(RagConfiguration.class), any(EmbeddingInputType.class))).thenReturn(model);
        when(model.embedAll(any())).thenAnswer(invocation -> {
            List<TextSegment> segments = invocation.getArgument(0);
            return Response.from(segments.stream().map(segment -> Embedding.from(new float[]{0.1f})).toList());
        });

        var connector = new GoogleDriveSourceConnector(drive,
                new DocumentExtractors(List.of(new PlainTextExtractor())));
        pipeline = new IngestionPipeline(SourceConnectors.of(connector), new HtmlToMarkdownConverter(), stateStore,
                new DocumentExtractors(List.of(new PlainTextExtractor())), modelFactory, storeFactory,
                new SimpleMeterRegistry());
        pipeline.sourceCredentials = (name, url) -> new ResolvedCredential("Authorization", "Bearer fixture-token");

        var config = new GoogleDriveSource();
        config.setConnection("google-drive-sa");
        config.setSharedDriveId(DRIVE);
        source = new IngestionSource();
        source.setId("src-drive");
        source.setName("handbook");
        source.setType(IngestionSource.TYPE_GOOGLE_DRIVE);
        source.setGoogleDrive(config);
        var settings = new IngestionSource.IngestionSettings();
        settings.setTombstoneAfterMissedRuns(1);
        source.setSettings(settings);

        drive.file("f1", "notes.txt", "text/plain", DRIVE, "Travel is booked through the portal.")
                .nativeFile("d1", "Leave policy", FakeDrive.DOC, DRIVE, 4,
                        Map.of("text/markdown", utf8("# Leave\n\nTwenty-five days a year.")));
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static RagConfiguration knowledgeBase() {
        RagConfiguration config = new RagConfiguration();
        config.setName("kb");
        config.setChunkSize(400);
        config.setChunkOverlap(0);
        return config;
    }

    private IngestionReport run(Mode mode) {
        return pipeline.run(KB_RESOURCE_ID, knowledgeBase(), source, mode);
    }

    @Test
    @DisplayName("a second run over an unchanged drive downloads and exports nothing")
    void unchangedDriveCostsOnlyTheListing() {
        IngestionReport first = run(Mode.INGEST);
        assertEquals(2, first.documentsIngested(), String.valueOf(first));
        int exports = drive.countRequests("/export");
        int downloads = drive.countRequests("alt=media");

        IngestionReport second = run(Mode.INGEST);

        assertEquals(0, second.documentsIngested());
        assertEquals(2, second.documentsUnchanged());
        assertEquals(exports, drive.countRequests("/export"));
        assertEquals(downloads, drive.countRequests("alt=media"));
    }

    @Test
    @DisplayName("a native version bump with the same content exports once, embeds nothing, then rests")
    void versionBumpWithoutContentChange() {
        run(Mode.INGEST);
        int segments = embeddingStore.segments().size();
        drive.nativeFile("d1", "Leave policy (renamed)", FakeDrive.DOC, DRIVE, 5,
                Map.of("text/markdown", utf8("# Leave\n\nTwenty-five days a year.")));
        int exports = drive.countRequests("/export");

        IngestionReport bumped = run(Mode.INGEST);
        assertEquals(exports + 1, drive.countRequests("/export"));
        assertEquals(0, bumped.documentsIngested());
        assertEquals(segments, embeddingStore.segments().size());

        IngestionReport after = run(Mode.INGEST);
        assertEquals(exports + 1, drive.countRequests("/export"), "the new version was recorded");
        assertEquals(2, after.documentsUnchanged());
    }

    @Test
    @DisplayName("a service account removed from the drive fails the run and deletes nothing")
    void lostAccessDeletesNothing() {
        run(Mode.INGEST);
        int segments = embeddingStore.segments().size();
        drive.driveVisible = false;

        IngestionReport first = run(Mode.INGEST);
        IngestionReport second = run(Mode.INGEST);

        assertEquals(IngestionReport.Outcome.FAILED, first.outcome());
        assertEquals(IngestionReport.Outcome.FAILED, second.outcome());
        assertEquals(0, first.documentsTombstoned() + second.documentsTombstoned());
        assertEquals(segments, embeddingStore.segments().size());
        assertTrue(second.message().contains("not a member"), second.message());
    }

    @Test
    @DisplayName("a preview lists, downloads nothing, and says what it found")
    void previewIsAListingPass() {
        IngestionReport preview = run(Mode.PREVIEW);

        assertEquals(IngestionReport.Outcome.PREVIEW, preview.outcome());
        assertEquals(2, preview.documentsIngested());
        assertTrue(preview.message().startsWith("Google Drive listing: 2 file(s) to read"), preview.message());
        assertEquals(0, drive.countRequests("/export"));
        assertEquals(0, drive.countRequests("alt=media"));
        assertTrue(embeddingStore.segments().isEmpty());
    }

    @Test
    @DisplayName("an export over Google's limit and an unreadable type are skipped, not failed")
    void skipsAreNotFailures() {
        drive.file("img", "diagram.png", "image/png", DRIVE, "png")
                .script("/files/d1/export", FakeDrive.error(403, "exportSizeLimitExceeded",
                        "This file is too large to be exported."));

        IngestionReport report = run(Mode.INGEST);

        assertEquals(2, report.documentsSkipped(), String.valueOf(report));
        assertEquals(0, report.documentsFailed());
        assertEquals(1, report.documentsIngested());
        assertFalse(report.tombstoningSkipped());
    }
}
