/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion;

import ai.labs.eddi.configs.rag.model.IngestionSource;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.modules.ingestion.IIngestionStateStore.IngestionRun;
import ai.labs.eddi.modules.ingestion.IngestionPipeline.IngestionReport;
import ai.labs.eddi.modules.ingestion.IngestionPipeline.Mode;
import ai.labs.eddi.modules.ingestion.IngestionWorkQueue.WorkCounts;
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
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.modules.ingestion.crawl.WebCrawler;
import ai.labs.eddi.modules.ingestion.extract.DocumentExtractors;
import ai.labs.eddi.modules.ingestion.extract.PlainTextExtractor;
import ai.labs.eddi.modules.llm.impl.EmbeddingModelFactory;
import ai.labs.eddi.modules.llm.impl.EmbeddingStoreFactory;
import ai.labs.eddi.modules.llm.impl.ProviderThrottledException;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.request.EmbeddingInputType;
import dev.langchain4j.model.output.Response;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The pipeline over the durable work table: a listing queues, the drain claims
 * and processes, and whatever stops a run — a throttle, a crash, a partial
 * listing — leaves the documents in the table rather than losing, failing or
 * removing them.
 */
class IngestionWorkTableTest {

    private static final String KB = "5a8b1c2d3e4f5a6b7c8d9e0f";
    private static final String FOLDERS = "root,folder-a";

    private MortalStore stateStore;
    private RecordingEmbeddingStore embeddingStore;
    private EmbeddingStoreFactory storeFactory;
    private EmbeddingModelFactory modelFactory;
    private Drive drive;
    private Instant clock;
    /** Documents the provider refuses with a throttle, by id. */
    private Predicate<String> throttles = id -> false;
    private final List<String> embedded = new ArrayList<>();
    private int maxAttempts = 5;

    @BeforeEach
    void setUp() {
        stateStore = new MortalStore();
        embeddingStore = new RecordingEmbeddingStore();
        storeFactory = mock(EmbeddingStoreFactory.class);
        modelFactory = mock(EmbeddingModelFactory.class);
        clock = Instant.now();
        drive = new Drive();
        when(storeFactory.getOrCreate(anyString(), any(RagConfiguration.class))).thenReturn(embeddingStore);
        when(storeFactory.getOrCreateForIngestion(anyString(), any(RagConfiguration.class))).thenReturn(embeddingStore);
        when(modelFactory.getOrCreate(any(RagConfiguration.class), any(EmbeddingInputType.class)))
                .thenReturn(new Provider());
    }

    /** Embeds anything, except a document the test says the quota refuses. */
    private final class Provider implements EmbeddingModel {
        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            String documentId = segments.get(0).metadata().getString(IngestionPipeline.METADATA_DOCUMENT_ID);
            if (throttles.test(documentId)) {
                throw new ProviderThrottledException("gemini", Duration.ofMinutes(10), false, null, null);
            }
            embedded.add(documentId);
            return Response.from(segments.stream().map(s -> Embedding.from(new float[]{0.1f})).toList());
        }
    }

    /**
     * A Drive-like source: lists by version token with a folder chain, fetches on
     * demand.
     */
    private static final class Drive implements SourceConnector {
        final Map<String, String> versions = new LinkedHashMap<>();
        final List<String> fetched = new ArrayList<>();
        Coverage coverage = Coverage.FULL_LISTING;
        int discoveries;
        /** Thrown by fetch for these ids. */
        final Map<String, Error> crashes = new LinkedHashMap<>();
        final Set<String> transientFailures = new HashSet<>();

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
            discoveries++;
            versions.forEach((id, version) -> context.accept(WorkItem.upsert(id, version, context.partition(),
                    Map.of("name", id + ".txt"), new WorkItem.Citation("T-" + id, "doc:" + id, FOLDERS))));
            return new DiscoveryResult(coverage == Coverage.PARTIAL ? StopReason.TIME_LIMIT : StopReason.COMPLETED,
                    coverage, null, coverage == Coverage.PARTIAL ? "stopped early" : null, null);
        }

        @Override
        public SourceDocument fetch(WorkItem item, SourceContext context) throws SourceException {
            fetched.add(item.documentId());
            Error crash = crashes.get(item.documentId());
            if (crash != null) {
                throw crash;
            }
            if (transientFailures.contains(item.documentId())) {
                throw new SourceException(new SourceError.Transient("503", null));
            }
            // The hints and the citation made it through the table.
            assertEquals(item.documentId() + ".txt", item.hints().get("name"));
            assertEquals(FOLDERS, item.citation().folderIds());
            return new SourceDocument(item.documentId(), new Content.Text("Body of " + item.documentId() + " "
                    + item.versionToken()), item.versionToken(), null, false,
                    Map.of(SourceMetadataKeys.TITLE, "T-" + item.documentId(), SourceMetadataKeys.CITATION_URL,
                            "doc:" + item.documentId(), SourceMetadataKeys.FOLDER_IDS, FOLDERS));
        }
    }

    private IngestionPipeline pipeline() {
        var pipeline = new IngestionPipeline(SourceConnectors.of(drive), new HtmlToMarkdownConverter(), stateStore,
                new DocumentExtractors(List.of(new PlainTextExtractor())), modelFactory, storeFactory,
                new SimpleMeterRegistry());
        pipeline.clock = () -> clock;
        pipeline.maxWorkAttempts = maxAttempts;
        pipeline.claimBatch = 2;
        pipeline.continuations = continuations;
        return pipeline;
    }

    /**
     * What the pipeline armed and withdrew, in order: an instant, or null for a
     * withdrawal.
     */
    private final List<Instant> armed = new ArrayList<>();
    private RagIngestionContinuations continuations = null;

    private void recordContinuations() {
        continuations = new RagIngestionContinuations(mock(IScheduleStore.class)) {
            @Override
            public void arm(String ragConfigId, String sourceId, Instant at) {
                assertEquals(KB, ragConfigId);
                assertEquals("src-1", sourceId);
                armed.add(at);
            }

            @Override
            public void disarm(String ragConfigId, String sourceId) {
                armed.add(null);
            }
        };
    }

    private IngestionReport run(IngestionRun.Trigger trigger) {
        return pipeline().run(KB, knowledgeBase(), source(), Mode.INGEST, null, trigger);
    }

    private IngestionReport run() {
        return run(IngestionRun.Trigger.MANUAL);
    }

    private void list(String version, String... ids) {
        drive.versions.clear();
        for (String id : ids) {
            drive.versions.put(id, version);
        }
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
        source.setName("work-table");
        source.setWeb(web);
        var settings = new IngestionSource.IngestionSettings();
        settings.setTombstoneAfterMissedRuns(1);
        source.setSettings(settings);
        return source;
    }

    private static String sourceKey() {
        return IngestionPipeline.stateKeyForSourceId(KB, "src-1");
    }

    private WorkCounts counts() {
        return stateStore.workCounts(sourceKey(), clock);
    }

    @Test
    @DisplayName("a listing queues, the drain ingests in the same run, and the chunks keep their folder chain")
    void listingQueuesAndTheDrainIngests() {
        list("v1", "a", "b", "c");

        IngestionReport report = run();

        assertEquals(IngestionReport.Outcome.COMPLETED, report.outcome());
        assertEquals(3, report.documentsIngested());
        assertEquals(new WorkCounts(0, 0, 0, 3, 0, null), counts());
        assertEquals(FOLDERS, embeddingStore.segmentsOf("a").get(0).metadata().getString(SourceMetadataKeys.FOLDER_IDS),
                "the retrieval-time folder filter reads this off every drained chunk");

        // Unchanged next time: nothing is queued again or fetched.
        drive.fetched.clear();
        IngestionReport second = run();
        assertEquals(3, second.documentsUnchanged());
        assertTrue(drive.fetched.isEmpty());
    }

    @Test
    @DisplayName("a throttle defers the drained documents to the provider's time: none failed, none removed")
    void aThrottleDefersAndNeverFails() {
        list("v1", "a", "b", "c", "gone");
        run();
        assertEquals(4, embedded.size());

        // Everything changed, "gone" left the source, and the provider now refuses "b".
        list("v2", "a", "b", "c");
        throttles = id -> id.equals("b");
        embedded.clear();
        IngestionReport throttled = run();

        assertEquals(IngestionReport.Outcome.THROTTLED, throttled.outcome());
        assertEquals(WebCrawler.StopReason.THROTTLED, throttled.stopReason());
        assertEquals(0, throttled.documentsFailed(), "a quota is not a document failure");
        assertEquals(1, throttled.documentsThrottled());
        assertNotNull(throttled.resumeAfter());
        assertEquals(1, throttled.documentsTombstoned(),
                "the listing was complete, so the document that left the source is removed regardless");
        assertTrue(stateStore.lookup(sourceKey(), "gone").orElseThrow().tombstoned());
        assertFalse(stateStore.lookup(sourceKey(), "b").orElseThrow().tombstoned(),
                "the deferred document keeps its old vectors");
        assertFalse(stateStore.lookup(sourceKey(), "c").orElseThrow().tombstoned());
        WorkCounts deferred = counts();
        assertEquals(0, deferred.failed());
        assertEquals(1, deferred.waiting(), "b waits for the provider's time");
        assertEquals(1, deferred.queued(), "c, never reached, is still due");
        assertEquals(throttled.resumeAfter(), deferred.nextAttemptAt(),
                "due exactly when the provider takes calls again, not after a failure's backoff");

        // A listing in the meantime must not undo b's deferral.
        throttles = id -> false;
        embedded.clear();
        run();
        assertEquals(List.of("c"), embedded, "re-listing equal work does not make b due early");

        // Once the provider's time has passed, a continuation drains the rest.
        clock = clock.plus(Duration.ofMinutes(15));
        drive.discoveries = 0;
        IngestionReport resumed = run(IngestionRun.Trigger.CONTINUATION);
        assertEquals(0, drive.discoveries, "a continuation drains and lists nothing");
        assertEquals(1, resumed.documentsIngested());
        assertEquals(0, counts().outstanding());
    }

    @Test
    @DisplayName("a run that dies mid-drain loses no document and duplicates none")
    void aCrashLosesNothing() {
        list("v1", "a", "b", "c", "d");
        // The node dies while it fetches "c": nothing after it runs, not even the
        // release of its claims.
        drive.crashes.put("c", new NodeDied());
        stateStore.dead = true;
        var dyingPipeline = pipeline();
        dyingPipeline.claimBatch = 4;
        assertThrows(NodeDied.class, () -> dyingPipeline.run(KB, knowledgeBase(), source(), Mode.INGEST, null,
                IngestionRun.Trigger.MANUAL));
        stateStore.dead = false;
        // What the reaper does to a dead run's row; its claims stay as they were.
        String dead = stateStore.listRuns(sourceKey(), 1).get(0).runId();
        stateStore.finishRun(new IngestionRun(dead, sourceKey(), IngestionRun.Status.FAILED, null, Instant.now(),
                0, 0, 0, 0, 0, 0, 0.0, "abandoned"));
        assertEquals(List.of("a", "b"), embedded, "a and b were embedded before the node died");

        // Long after the dead run's claim lease, the next run takes over.
        drive.crashes.clear();
        clock = clock.plus(Duration.ofDays(1));
        IngestionReport next = run(IngestionRun.Trigger.CONTINUATION);

        assertEquals(List.of("a", "b", "c", "d"), embedded.stream().sorted().toList(),
                "every document embedded exactly once");
        assertEquals(2, next.documentsIngested());
        for (String id : List.of("a", "b", "c", "d")) {
            assertEquals(1, embeddingStore.segmentsOf(id).size(), id + " has one set of chunks");
        }
        assertEquals(0, counts().outstanding());
    }

    @Test
    @DisplayName("a partial listing withdraws no queued work and removes no document")
    void aPartialListingRemovesNothing() {
        list("v1", "a", "b");
        run();
        // "b" changes, but its drain is throttled, so its new version stays queued.
        list("v2", "a", "b");
        throttles = id -> id.equals("b");
        run();
        assertEquals(1, counts().waiting());

        // A listing that stopped early and did not get to "a" or "b".
        throttles = id -> false;
        list("v2");
        drive.coverage = Coverage.PARTIAL;
        IngestionReport partial = run();

        assertEquals(0, partial.documentsTombstoned());
        assertTrue(partial.tombstoningSkipped());
        assertFalse(stateStore.lookup(sourceKey(), "a").orElseThrow().tombstoned());
        assertEquals(1, counts().waiting(), "b's queued work survives a listing that never looked at it");

        // A complete listing that no longer has b withdraws its work.
        drive.coverage = Coverage.FULL_LISTING;
        list("v2", "a");
        run();
        assertEquals(0, counts().outstanding());
    }

    @Test
    @DisplayName("a document that keeps failing is poisoned, and retry-failed queues it again")
    void repeatedFailuresPoison() {
        maxAttempts = 2;
        list("v1", "a", "bad");
        drive.transientFailures.add("bad");

        IngestionReport first = run();
        assertEquals(1, first.documentsFailed());
        assertEquals(1, counts().waiting(), "after one failure it waits for its backoff");

        clock = clock.plus(Duration.ofHours(2));
        run(IngestionRun.Trigger.CONTINUATION);
        assertEquals(1, counts().failed(), "the second failure reaches the cap");
        assertEquals(0, counts().outstanding());
        assertEquals("503", stateStore.lastWorkError(sourceKey(), "bad"));

        drive.transientFailures.clear();
        assertEquals(1, stateStore.retryFailedWork(sourceKey(), clock));
        run(IngestionRun.Trigger.CONTINUATION);
        assertTrue(embedded.contains("bad"));
        assertEquals(0, counts().failed());
    }

    @Test
    @DisplayName("a preview queues nothing")
    void previewQueuesNothing() {
        list("v1", "a", "b");
        pipeline().run(KB, knowledgeBase(), source(), Mode.PREVIEW);
        assertEquals(WorkCounts.NONE, counts());
    }

    @Test
    @DisplayName("continuations: a safety net while listing, then withdrawn once nothing is left")
    void continuationIsArmedAsASafetyNetThenWithdrawn() {
        recordContinuations();
        list("v1", "a", "b");

        run();

        assertTrue(armed.size() >= 2, String.valueOf(armed));
        assertNotNull(armed.get(0), "armed at the first queued document, before any claim");
        assertTrue(armed.get(0).isAfter(clock.plus(Duration.ofMinutes(10))), "for after the claim lease");
        assertNull(armed.get(armed.size() - 1), "withdrawn: the drain finished everything");
    }

    @Test
    @DisplayName("continuations: after a throttle, armed for when the provider takes calls again")
    void continuationAfterAThrottleWaitsForTheProvider() {
        recordContinuations();
        list("v1", "a", "b");
        throttles = id -> id.equals("a");

        IngestionReport report = run();

        Instant last = armed.get(armed.size() - 1);
        assertNotNull(last, "work is left, so a continuation is armed");
        assertEquals(report.resumeAfter(), last, "not before the provider's time: it would be refused again");
    }

    @Test
    @DisplayName("continuations: none after a budget stop, which leaves the rest to the next run")
    void noContinuationAfterABudgetStop() {
        recordContinuations();
        list("v1", "a", "b", "c");
        IngestionSource budgeted = source();
        budgeted.getSettings().setMaxSegmentsPerRun(1);

        pipeline().run(KB, knowledgeBase(), budgeted, Mode.INGEST, null, IngestionRun.Trigger.MANUAL);

        assertTrue(counts().outstanding() > 0, "the budget left work queued");
        assertNull(armed.get(armed.size() - 1), "and the run withdrew its safety net instead of re-arming");
    }

    @Test
    @DisplayName("claims a failed release left behind are taken over by the next run at once")
    void strandedClaimsAreTakenOverByTheNextRun() {
        list("v1", "a", "b", "c", "d");
        drive.crashes.put("b", new NodeDied());
        stateStore.releaseFails = true;
        var failing = pipeline();
        failing.claimBatch = 4;
        assertThrows(NodeDied.class, () -> failing.run(KB, knowledgeBase(), source(), Mode.INGEST, null,
                IngestionRun.Trigger.MANUAL));
        stateStore.releaseFails = false;
        drive.crashes.clear();
        assertTrue(counts().inFlight() > 0, "the release did not land: rows are left claimed");

        // No time passes: the leases have not run out.
        IngestionReport next = run(IngestionRun.Trigger.CONTINUATION);

        assertEquals(3, next.documentsIngested(), "b, c and d are taken over, not left to their lease");
        assertEquals(0, counts().outstanding());
    }

    /** Stands for the JVM going away: not caught as a document failure. */
    private static final class NodeDied extends Error {
    }

    /**
     * The in-memory store, able to stand for a node that dies: it then releases and
     * records nothing.
     */
    private static final class MortalStore extends InMemoryIngestionStateStore {
        boolean dead;

        /** The release does not land, as when the database is briefly unreachable. */
        boolean releaseFails;

        @Override
        public synchronized int releaseClaims(String sourceId, String claimer) {
            return dead || releaseFails ? 0 : super.releaseClaims(sourceId, claimer);
        }

        @Override
        public synchronized void finishRun(IngestionRun run) {
            if (!dead) {
                super.finishRun(run);
            }
        }
    }
}
