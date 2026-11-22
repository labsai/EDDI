/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion;

import ai.labs.eddi.configs.rag.model.IngestionSource;
import ai.labs.eddi.configs.rag.model.IngestionSource.DriveFolder;
import ai.labs.eddi.configs.rag.model.IngestionSource.GoogleDriveSource;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.connections.ResolvedCredential;
import ai.labs.eddi.modules.ingestion.IngestionPipeline.IngestionReport;
import ai.labs.eddi.modules.ingestion.IngestionPipeline.Mode;
import ai.labs.eddi.modules.ingestion.connector.SourceConnectors;
import ai.labs.eddi.modules.ingestion.drive.FakeDrive;
import ai.labs.eddi.modules.ingestion.files.IIngestedFileStore;
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
 * A Drive source's scope changing between runs, through the real pipeline and
 * state store: a folder taken out goes at the next completed listing instead of
 * after {@code tombstoneAfterMissedRuns} runs, an incomplete listing removes
 * nothing, and a folder put in is simply read.
 */
class GoogleDriveScopeChangePipelineTest {

    private static final String KB_RESOURCE_ID = "5a8b1c2d3e4f5a6b7c8d9e0f";
    private static final String DRIVE = "0AdriveAAA";
    private static final int GRACE = 3;

    private FakeDrive drive;
    private InMemoryIngestionStateStore stateStore;
    private RecordingEmbeddingStore embeddingStore;
    private IngestionPipeline pipeline;
    private IngestionSource source;
    private GoogleDriveSource config;

    private EmbeddingStoreFactory storeFactory;
    private EmbeddingModelFactory modelFactory;

    /** Text that makes the embedding model fail, as a throttled provider would. */
    private String failOn;

    @BeforeEach
    void setUp() {
        drive = new FakeDrive(DRIVE);
        stateStore = new InMemoryIngestionStateStore();
        embeddingStore = new RecordingEmbeddingStore();
        embeddingStore.searchingEverything();
        storeFactory = mock(EmbeddingStoreFactory.class);
        modelFactory = mock(EmbeddingModelFactory.class);
        EmbeddingModel model = mock(EmbeddingModel.class);
        when(model.embed(anyString())).thenReturn(Response.from(Embedding.from(new float[]{0.1f})));
        when(storeFactory.getOrCreate(anyString(), any(RagConfiguration.class))).thenReturn(embeddingStore);
        when(storeFactory.getOrCreateForIngestion(anyString(), any(RagConfiguration.class))).thenReturn(embeddingStore);
        when(modelFactory.getOrCreate(any(RagConfiguration.class), any(EmbeddingInputType.class))).thenReturn(model);
        when(model.embedAll(any())).thenAnswer(invocation -> {
            List<TextSegment> segments = invocation.getArgument(0);
            if (failOn != null && segments.stream().anyMatch(segment -> segment.text().contains(failOn))) {
                throw new IllegalStateException("429 RESOURCE_EXHAUSTED");
            }
            return Response.from(segments.stream().map(segment -> Embedding.from(new float[]{0.1f})).toList());
        });

        var connector = new GoogleDriveSourceConnector(drive,
                new DocumentExtractors(List.of(new PlainTextExtractor())));
        pipeline = new IngestionPipeline(SourceConnectors.of(connector), new HtmlToMarkdownConverter(), stateStore,
                new DocumentExtractors(List.of(new PlainTextExtractor())), modelFactory, storeFactory,
                new SimpleMeterRegistry());
        pipeline.sourceCredentials = (name, url) -> new ResolvedCredential("Authorization", "Bearer fixture-token");

        config = new GoogleDriveSource();
        config.setConnection("google-drive-sa");
        config.setSharedDriveId(DRIVE);
        config.setFolders(List.of(DriveFolder.ofPath("Sales"), DriveFolder.ofPath("Legal")));
        source = new IngestionSource();
        source.setId("src-drive");
        source.setName("handbook");
        source.setType(IngestionSource.TYPE_GOOGLE_DRIVE);
        source.setGoogleDrive(config);
        var settings = new IngestionSource.IngestionSettings();
        settings.setTombstoneAfterMissedRuns(GRACE);
        source.setSettings(settings);

        drive.folder("fSales", "Sales", DRIVE)
                .folder("fArchive", "Archive", "fSales")
                .folder("fLegal", "Legal", DRIVE)
                .file("s1", "offer.txt", "text/plain", "fSales", "The offer is valid for thirty days.")
                .file("s2", "old.txt", "text/plain", "fArchive", "An archived offer from last year.")
                .file("l1", "terms.txt", "text/plain", "fLegal", "Payment is due within fourteen days.");
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

    private String key() {
        return IngestionPipeline.stateKey(KB_RESOURCE_ID, source);
    }

    private boolean live(String documentId) {
        return !embeddingStore.segmentsOf(documentId).isEmpty();
    }

    @Test
    @DisplayName("a folder taken out of the scope goes at the next completed listing, not after the miss grace")
    void removedFolderGoesAtOnce() {
        IngestionReport first = run(Mode.INGEST);
        assertEquals(3, first.documentsIngested(), String.valueOf(first));

        config.setFolders(List.of(DriveFolder.ofPath("Sales")));
        IngestionReport second = run(Mode.INGEST);

        assertEquals(1, second.documentsTombstoned(), String.valueOf(second));
        assertFalse(live("l1"));
        assertTrue(live("s1") && live("s2"), "the folder that stayed keeps its documents");
        assertTrue(stateStore.lookup(key(), "l1").orElseThrow().tombstoned());
    }

    /**
     * The documents a search answers, the way the Manager's Test Retrieval asks.
     */
    private List<String> searchHits() {
        var inspector = new KnowledgeBaseInspector(stateStore, mock(IIngestedFileStore.class), storeFactory, modelFactory);
        return inspector.search(KB_RESOURCE_ID, knowledgeBaseWith(source), null, "offer", 10, 0.0).stream()
                .map(chunk -> String.valueOf(chunk.metadata().get(IngestionPipeline.METADATA_DOCUMENT_ID)))
                .distinct().sorted().toList();
    }

    private static RagConfiguration knowledgeBaseWith(IngestionSource source) {
        RagConfiguration config = knowledgeBase();
        config.setSources(List.of(source));
        return config;
    }

    @Test
    @DisplayName("a removed folder stops answering at once: hidden during an incomplete listing and after a complete one")
    void retrievalAppliesTheCurrentScope() {
        run(Mode.INGEST);
        assertEquals(List.of("l1", "s1", "s2"), searchHits());

        config.setFolders(List.of(DriveFolder.ofPath("Sales")));
        assertEquals(List.of("s1", "s2"), searchHits(), "hidden as soon as the scope changes, before any run");

        drive.script("%27fArchive%27", FakeDrive.error(429, "rateLimitExceeded", "Rate limit exceeded", "600"));
        IngestionReport throttled = run(Mode.INGEST);
        assertEquals(0, throttled.documentsTombstoned());
        assertTrue(live("l1"), "the vectors are still stored");
        assertEquals(List.of("s1", "s2"), searchHits(), "but not retrieved during the incomplete listing");

        run(Mode.INGEST);
        assertFalse(live("l1"));
        assertEquals(List.of("s1", "s2"), searchHits(), "and still not after the complete one removed them");
    }

    @Test
    @DisplayName("a new exclusion hides its subtree at once, and chunks carry their folder chain")
    void exclusionHidesAtOnce() {
        run(Mode.INGEST);
        assertEquals("/fSales/fArchive/",
                embeddingStore.segmentsOf("s2").getFirst().metadata().getString("folderIds"));

        config.setExcludeFolders(List.of(DriveFolder.ofId("fArchive")));

        assertEquals(List.of("l1", "s1"), searchHits());
    }

    @Test
    @DisplayName("chunks written before chains were recorded are stamped in place, without embedding again")
    void legacyChunksAreStamped() {
        run(Mode.INGEST);
        embeddingStore.segments().forEach(segment -> segment.metadata().remove("folderIds"));
        // As written by the previous build: the citation fingerprint without a chain.
        Map.of("s1", "offer.txt", "s2", "old.txt", "l1", "terms.txt").forEach((id, name) -> stateStore.recordCitation(
                key(), id, stateStore.lookup(key(), id).orElseThrow().lastRunId(),
                ContentHashes.citation(name, "https://docs.google.com/fake/" + id)));
        int segments = embeddingStore.segments().size();

        IngestionReport second = run(Mode.INGEST);

        assertEquals(0, second.documentsIngested(), String.valueOf(second));
        assertEquals(segments, embeddingStore.segments().size());
        assertEquals("/fLegal/", embeddingStore.segmentsOf("l1").getFirst().metadata().getString("folderIds"));
    }

    @Test
    @DisplayName("a kept document that fails to embed in the scope-changed run keeps its vectors")
    void failedKeptDocumentIsNotRemoved() {
        run(Mode.INGEST);
        config.setFolders(List.of(DriveFolder.ofPath("Sales")));
        drive.file("s1", "offer.txt", "text/plain", "fSales", "The offer is now valid for sixty days.");
        failOn = "sixty";

        IngestionReport second = run(Mode.INGEST);

        assertEquals(1, second.documentsTombstoned(), "only the removed folder's document goes: " + second);
        assertFalse(live("l1"));
        assertTrue(live("s1"), "listed in the new scope, so its old vectors stay although this embedding failed");
        assertFalse(stateStore.lookup(key(), "s1").orElseThrow().tombstoned());
    }

    @Test
    @DisplayName("without a scope change, a missing document keeps its miss grace")
    void unchangedScopeKeepsTheGrace() {
        run(Mode.INGEST);
        drive.delete("l1");

        IngestionReport second = run(Mode.INGEST);

        assertEquals(0, second.documentsTombstoned());
        assertTrue(live("l1"));
        assertEquals(1, stateStore.lookup(key(), "l1").orElseThrow().missedRuns());
    }

    @Test
    @DisplayName("a new exclusion removes its subtree at once")
    void newExclusionGoesAtOnce() {
        run(Mode.INGEST);

        config.setExcludeFolders(List.of(DriveFolder.ofPath("Sales/Archive")));
        IngestionReport second = run(Mode.INGEST);

        assertEquals(1, second.documentsTombstoned(), String.valueOf(second));
        assertFalse(live("s2"));
        assertTrue(live("s1") && live("l1"));
    }

    @Test
    @DisplayName("an incomplete listing after a scope change removes nothing, and the next complete one does")
    void incompleteListingRemovesNothing() {
        run(Mode.INGEST);
        config.setFolders(List.of(DriveFolder.ofPath("Sales")));
        // Drive keeps saying "slow down" for longer than a run waits.
        drive.script("%27fArchive%27", FakeDrive.error(429, "rateLimitExceeded", "Rate limit exceeded", "600"));

        IngestionReport throttled = run(Mode.INGEST);

        assertEquals(0, throttled.documentsTombstoned(), String.valueOf(throttled));
        assertTrue(live("l1"), "a partial listing is no evidence that anything left");

        IngestionReport complete = run(Mode.INGEST);

        assertEquals(1, complete.documentsTombstoned(), String.valueOf(complete));
        assertFalse(live("l1"));
    }

    @Test
    @DisplayName("a folder put into the scope is read; nothing is removed")
    void addedFolderIsRead() {
        config.setFolders(List.of(DriveFolder.ofPath("Sales")));
        run(Mode.INGEST);
        assertFalse(live("l1"));

        config.setFolders(List.of(DriveFolder.ofPath("Sales"), DriveFolder.ofPath("Legal")));
        IngestionReport second = run(Mode.INGEST);

        assertEquals(1, second.documentsIngested());
        assertEquals(0, second.documentsTombstoned());
        assertTrue(live("l1") && live("s1") && live("s2"));
    }

    @Test
    @DisplayName("a removal the store refuses leaves the change pending, so the next run removes at once again")
    void failedRemovalKeepsTheChangePending() {
        run(Mode.INGEST);
        config.setFolders(List.of(DriveFolder.ofPath("Sales")));
        embeddingStore.withFailingRemoval();

        IngestionReport refused = run(Mode.INGEST);
        assertEquals(0, refused.documentsTombstoned());

        embeddingStore.withWorkingRemoval();
        IngestionReport retried = run(Mode.INGEST);

        assertEquals(1, retried.documentsTombstoned(), "still removed before the miss grace of " + GRACE);
        assertFalse(live("l1"));
    }

    @Test
    @DisplayName("a source whose state predates scope signatures is not fast-tracked on its first run")
    void noRecordedSignatureIsNoChange() {
        run(Mode.INGEST);
        stateStore.recordScopeSignature(key(), null);
        config.setFolders(List.of(DriveFolder.ofPath("Sales")));

        IngestionReport second = run(Mode.INGEST);

        assertEquals(0, second.documentsTombstoned());
        assertTrue(live("l1"));
        assertTrue(stateStore.scopeSignature(key()).isPresent(), "the signature is recorded from now on");
    }

    @Test
    @DisplayName("a rename is not a scope change, and the resolved ids live in state, never in the configuration")
    void renameIsNoChange() {
        run(Mode.INGEST);
        drive.rename("fLegal", "Contracts");

        IngestionReport second = run(Mode.INGEST);

        assertEquals(0, second.documentsTombstoned());
        assertEquals(3, second.documentsUnchanged());
        assertEquals("Legal", config.getFolders().get(1).getPath(), "a run never writes the configuration");
        assertTrue(stateStore.resolvedScope(key()).orElseThrow().contains("fLegal"));
    }

    @Test
    @DisplayName("a preview resolves the paths but records nothing")
    void previewRecordsNothing() {
        IngestionReport preview = run(Mode.PREVIEW);

        assertTrue(preview.message().contains("3 file(s) to read"), preview.message());
        assertTrue(stateStore.resolvedScope(key()).isEmpty());
        assertTrue(stateStore.scopeSignature(key()).isEmpty());
    }
}
