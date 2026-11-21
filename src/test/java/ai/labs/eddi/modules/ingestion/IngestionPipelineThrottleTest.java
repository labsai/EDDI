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
import ai.labs.eddi.modules.ingestion.crawl.WebCrawler;
import ai.labs.eddi.modules.ingestion.extract.DocumentExtractors;
import ai.labs.eddi.modules.ingestion.extract.PlainTextExtractor;
import ai.labs.eddi.modules.llm.impl.EmbeddingModelFactory;
import ai.labs.eddi.modules.llm.impl.EmbeddingStoreFactory;
import ai.labs.eddi.modules.llm.impl.ProviderRetryHints;
import ai.labs.eddi.modules.llm.impl.ProviderThrottledException;
import ai.labs.eddi.modules.llm.impl.ThrottledEmbeddingModel;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.request.EmbeddingInputType;
import dev.langchain4j.model.output.Response;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The 49-of-68 run, in miniature: an embedding provider that answers 429 after
 * a few calls per simulated window, through the real
 * {@link ThrottledEmbeddingModel} and the real pipeline. Nothing sleeps — waits
 * advance a fake clock.
 */
class IngestionPipelineThrottleTest {

    private static final String KB_RESOURCE_ID = "5a8b1c2d3e4f5a6b7c8d9e0f";

    private InMemoryIngestionStateStore stateStore;
    private RecordingEmbeddingStore embeddingStore;
    private EmbeddingStoreFactory storeFactory;
    private EmbeddingModelFactory modelFactory;
    private SimpleMeterRegistry meters;

    private Instant now;
    private final List<Duration> sleeps = new ArrayList<>();
    private QuotaProvider provider;
    private Duration runBudget = Duration.ofMinutes(10);
    private int batchSize = ThrottledEmbeddingModel.DEFAULT_BATCH_SIZE;
    private ThrottledEmbeddingModel.Sleeper sleeper;
    private IngestionPipeline lastPipeline;

    @BeforeEach
    void setUp() {
        stateStore = new InMemoryIngestionStateStore();
        embeddingStore = new RecordingEmbeddingStore();
        storeFactory = mock(EmbeddingStoreFactory.class);
        modelFactory = mock(EmbeddingModelFactory.class);
        meters = new SimpleMeterRegistry();
        now = Instant.now();
        provider = new QuotaProvider(Integer.MAX_VALUE, fixture("gemini-429-embed-per-minute"));
        when(storeFactory.getOrCreate(anyString(), any(RagConfiguration.class))).thenReturn(embeddingStore);
        when(storeFactory.getOrCreateForIngestion(anyString(), any(RagConfiguration.class))).thenReturn(embeddingStore);
        sleeper = duration -> {
            sleeps.add(duration);
            now = now.plus(duration);
        };
        // The factory's DOCUMENT model, as it builds it: the throttle decorator over
        // the provider — here with a clock and a sleep the test controls.
        when(modelFactory.getOrCreate(any(RagConfiguration.class), any(EmbeddingInputType.class)))
                .thenAnswer(invocation -> ThrottledEmbeddingModel.of(provider, "gemini", 5, true, meters,
                        duration -> sleeper.sleep(duration), () -> now,
                        ThrottledEmbeddingModel.DEFAULT_UNBOUND_MAX_WAIT, batchSize));
    }

    @AfterEach
    void clearInterrupt() {
        Thread.interrupted();
    }

    /**
     * A provider with a quota of {@code limit} units per window: past it, every
     * call is refused with the given 429 body until the clock has moved 26 s on. A
     * unit is a call, or — with {@link #perSegment} — a segment, which is how
     * Gemini counts a batch request against its per-minute quota.
     */
    private final class QuotaProvider implements EmbeddingModel {
        int limit;
        String body;
        boolean perSegment;
        int usedInWindow;
        Instant blockedUntil = Instant.MIN;
        int refusals;
        int segmentsEmbedded;

        QuotaProvider(int limit, String body) {
            this.limit = limit;
            this.body = body;
        }

        @Override
        public Response<List<Embedding>> embedAll(List<TextSegment> segments) {
            if (now.isBefore(blockedUntil)) {
                refusals++;
                throw new RateLimitException(new HttpException(429, body));
            }
            int cost = perSegment ? segments.size() : 1;
            if (usedInWindow + cost > limit) {
                // The window turns 26 s from now.
                usedInWindow = 0;
                blockedUntil = now.plusSeconds(26);
                refusals++;
                throw new RateLimitException(new HttpException(429, body));
            }
            usedInWindow += cost;
            segmentsEmbedded += segments.size();
            return Response.from(segments.stream().map(s -> Embedding.from(new float[]{0.1f})).toList());
        }
    }

    /**
     * Lists documents by version token and fetches each on demand, counting the
     * fetches: the path where the pipeline can know a document is unchanged without
     * its content.
     */
    private static final class Versioned implements SourceConnector {
        final Map<String, String> versions = new LinkedHashMap<>();
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
            versions.forEach((id, version) -> context.accept(WorkItem.upsert(id, version, context.partition(),
                    Map.of(), new WorkItem.Citation("T-" + id, "doc:" + id))));
            return new DiscoveryResult(StopReason.COMPLETED, Coverage.FULL_LISTING, null, null, null);
        }

        @Override
        public SourceDocument fetch(WorkItem item, SourceContext context) {
            fetched.add(item.documentId());
            return new SourceDocument(item.documentId(), new Content.Text("Body of " + item.documentId() + " "
                    + item.versionToken()), item.versionToken(), null, false,
                    Map.of(SourceMetadataKeys.TITLE, "T-" + item.documentId(), SourceMetadataKeys.CITATION_URL,
                            "doc:" + item.documentId()));
        }
    }

    /**
     * Lists its documents as prefetched text, ignoring isCancelled as a careless
     * connector would.
     */
    private static final class Listing implements SourceConnector {
        final Map<String, String> documents = new LinkedHashMap<>();
        Coverage coverage = Coverage.FULL_LISTING;

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
            documents.forEach((id, body) -> context.accept(WorkItem.prefetched(
                    new SourceDocument(id, new Content.Text(body), null, null, false,
                            Map.of(SourceMetadataKeys.TITLE, "T-" + id, SourceMetadataKeys.CITATION_URL, "doc:" + id)),
                    context.partition())));
            return new DiscoveryResult(StopReason.COMPLETED, coverage, null, null, null);
        }

        @Override
        public SourceDocument fetch(WorkItem item, SourceContext context) {
            throw new UnsupportedOperationException("everything is prefetched");
        }
    }

    private IngestionReport run(SourceConnector connector) {
        var pipeline = new IngestionPipeline(SourceConnectors.of(connector), new HtmlToMarkdownConverter(), stateStore,
                new DocumentExtractors(List.of(new PlainTextExtractor())), modelFactory, storeFactory, meters) {
            @Override
            Instant uploadDeadline(Instant start, IngestionSource source) {
                return start.plus(runBudget);
            }
        };
        lastPipeline = pipeline;
        return pipeline.run(KB_RESOURCE_ID, knowledgeBase(), source(), Mode.INGEST);
    }

    private static Listing listing(String version, String... ids) {
        var connector = new Listing();
        for (String id : ids) {
            connector.documents.put(id, "Body of " + id + " " + version);
        }
        return connector;
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
        source.setName("throttled");
        source.setWeb(web);
        var settings = new IngestionSource.IngestionSettings();
        // One missed run is enough to tombstone, so a run that wrongly concludes
        // anything is gone shows it at once.
        settings.setTombstoneAfterMissedRuns(1);
        source.setSettings(settings);
        return source;
    }

    private String sourceKey() {
        return IngestionPipeline.stateKeyForSourceId(KB_RESOURCE_ID, "src-1");
    }

    private IngestionRun lastRun() {
        return stateStore.listRuns(sourceKey(), 1).get(0);
    }

    private static String fixture(String name) {
        try (InputStream in = IngestionPipelineThrottleTest.class.getResourceAsStream("/llm-errors/" + name + ".json")) {
            if (in == null) {
                throw new IllegalStateException("missing fixture " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    @DisplayName("with time to spare, every 429 is waited out: everything ingested, nothing failed")
    void normalDeadlineIngestsEverything() {
        provider.limit = 2;

        IngestionReport report = run(listing("v1", "a", "b", "c", "d", "e", "f"));

        assertEquals(IngestionReport.Outcome.COMPLETED, report.outcome());
        assertEquals(6, report.documentsIngested());
        assertEquals(0, report.documentsFailed());
        assertEquals(0, report.documentsThrottled());
        assertEquals(2, provider.refusals);
        assertEquals(List.of(Duration.ofSeconds(26), Duration.ofSeconds(26)), sleeps);
        assertEquals(WebCrawler.StopReason.COMPLETED, report.stopReason());
    }

    @Test
    @DisplayName("a wait past the deadline stops the run THROTTLED: partial, nothing failed, nothing tombstoned")
    void shortDeadlineStopsThrottled() {
        // A first run, unthrottled, puts six documents in the store.
        IngestionReport first = run(listing("v1", "a", "b", "c", "d", "e", "f"));
        assertEquals(6, first.documentsIngested());

        // Everything changed, "f" has gone from the source, and the quota now allows
        // two calls a window with only 10 s of run left.
        provider.limit = 2;
        provider.usedInWindow = 0;
        runBudget = Duration.ofSeconds(10);
        IngestionReport throttled = run(listing("v2", "a", "b", "c", "d", "e"));

        assertEquals(IngestionReport.Outcome.THROTTLED, throttled.outcome());
        assertEquals(WebCrawler.StopReason.THROTTLED, throttled.stopReason());
        assertEquals(2, throttled.documentsIngested());
        assertEquals(0, throttled.documentsFailed(), "a quota is not a document failure");
        assertEquals(3, throttled.documentsThrottled(), "c, and the two the connector offered after it");
        assertEquals(0, throttled.documentsTombstoned(), "f's absence proves nothing in a partial run");
        assertTrue(throttled.tombstoningSkipped());
        assertTrue(sleeps.isEmpty(), "a 26 s wait does not fit in 10 s, so none is started");
        assertEquals(1, provider.refusals, "later documents are not sent to a provider that has said no");
        assertNotNull(throttled.resumeAfter());
        assertTrue(throttled.resumeAfter().isAfter(Instant.now().plusSeconds(20)));
        assertNull(throttled.message(), "a throttle is not an error to show in red");
        assertEquals(3.0, meters.counter("eddi.ingestion.throttled", "knowledgeBase", "kb", "source", "throttled")
                .count());

        IngestionRun record = lastRun();
        assertEquals(IngestionRun.Status.COMPLETED, record.status());
        assertEquals("THROTTLED", record.stopReason());
        assertEquals(3, record.documentsThrottled());
        assertEquals(throttled.resumeAfter(), record.resumeAfter());
        assertEquals(0, record.documentsFailed());
        assertNull(record.error());
        assertFalse(stateStore.lookup(sourceKey(), "f").orElseThrow().tombstoned());
        assertFalse(embeddingStore.segmentsOf("c").isEmpty(), "c keeps its old chunks until it is re-embedded");

        // The next run, with the quota back, lists the source again and takes the rest.
        provider.limit = Integer.MAX_VALUE;
        provider.blockedUntil = Instant.MIN;
        runBudget = Duration.ofMinutes(10);
        IngestionReport next = run(listing("v2", "a", "b", "c", "d", "e"));

        assertEquals(IngestionReport.Outcome.COMPLETED, next.outcome());
        assertEquals(3, next.documentsIngested(), "c, d and e");
        assertEquals(2, next.documentsUnchanged());
        assertEquals(1, next.documentsTombstoned(), "now f is concluded gone");
        assertEquals(0, next.documentsFailed());
    }

    @Test
    @DisplayName("a daily quota stops the run at once, without waiting at all")
    void dailyQuotaStopsImmediately() {
        provider = new QuotaProvider(1, fixture("gemini-429-embed-per-day"));

        IngestionReport report = run(listing("v1", "a", "b", "c"));

        assertEquals(IngestionReport.Outcome.THROTTLED, report.outcome());
        assertEquals(1, report.documentsIngested());
        assertEquals(2, report.documentsThrottled());
        assertEquals(0, report.documentsFailed());
        assertTrue(sleeps.isEmpty(), "a day does not fit in any run");
        // Gemini's daily quota resets at midnight Pacific time, which the run records
        // so the next scheduled fire waits for it.
        assertEquals(ProviderRetryHints.dailyReset("gemini", Instant.now()).orElseThrow(), report.resumeAfter());
        assertEquals(report.resumeAfter(), lastRun().resumeAfter());
    }

    @Test
    @DisplayName("a daily stop on a provider with no known reset waits the configured daily cooldown")
    void dailyStopUsesTheCooldownWhereTheResetIsUnknown() {
        var pipeline = new IngestionPipeline(SourceConnectors.of(new Listing()), new HtmlToMarkdownConverter(),
                stateStore, new DocumentExtractors(List.of(new PlainTextExtractor())), modelFactory, storeFactory,
                meters);
        pipeline.dailyCooldown = Duration.ofHours(3);
        Instant at = Instant.parse("2026-11-21T10:00:00Z");

        assertEquals(at.plus(Duration.ofHours(3)), pipeline.resumeAfter(
                new ProviderThrottledException("openai", null, true, null, null), at));
        assertEquals(Instant.parse("2026-11-22T08:00:00Z"), pipeline.resumeAfter(
                new ProviderThrottledException("gemini", null, true, null, null), at));
        assertEquals(at.plusSeconds(26), pipeline.resumeAfter(
                new ProviderThrottledException("gemini", Duration.ofSeconds(26), false, null, null), at));
        assertNull(pipeline.resumeAfter(new ProviderThrottledException("gemini", null, false, null, null), at),
                "a rate limit that named no time gets none invented");
    }

    @Test
    @DisplayName("a document larger than one quota window finishes: each batch is retried alone")
    void documentLargerThanTheQuotaWindowFinishes() {
        provider = new QuotaProvider(100, fixture("gemini-429-embed-per-minute"));
        provider.perSegment = true;

        IngestionReport report = run(bigDocument());

        assertEquals(IngestionReport.Outcome.COMPLETED, report.outcome());
        assertEquals(1, report.documentsIngested());
        assertTrue(report.segmentsStored() > 200, "got " + report.segmentsStored());
        assertEquals(report.segmentsStored(), provider.segmentsEmbedded,
                "no segment was embedded twice: a refused batch is the only thing sent again");
        assertFalse(sleeps.isEmpty());
    }

    @Test
    @DisplayName("embedded as one call, the same document never fits the window and the run stops THROTTLED")
    void wholeDocumentRetryNeverFinishes() {
        // The defect: a retry repaid every batch already done, so the call never fit.
        provider = new QuotaProvider(100, fixture("gemini-429-embed-per-minute"));
        provider.perSegment = true;
        batchSize = 10_000;

        IngestionReport report = run(bigDocument());

        assertEquals(IngestionReport.Outcome.THROTTLED, report.outcome());
        assertEquals(0, report.documentsIngested());
        assertEquals(1, report.documentsThrottled());
    }

    private static Listing bigDocument() {
        var connector = new Listing();
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 250; i++) {
            body.append("Paragraph ").append(i).append(' ').append("x".repeat(300)).append("\n\n");
        }
        connector.documents.put("big", body.toString());
        return connector;
    }

    @Test
    @DisplayName("after a throttle, an unchanged prefetched document still counts as unchanged, not throttled")
    void unchangedPrefetchedAfterAThrottle() {
        run(listing("v1", "a", "b", "c", "d"));

        // a and b changed; c and d did not. The provider refuses for the day at once.
        provider = new QuotaProvider(0, fixture("gemini-429-embed-per-day"));
        var connector = listing("v1", "a", "b", "c", "d");
        connector.documents.put("a", "Body of a v2");
        connector.documents.put("b", "Body of b v2");
        IngestionReport report = run(connector);

        assertEquals(IngestionReport.Outcome.THROTTLED, report.outcome());
        assertEquals(2, report.documentsUnchanged(), "c and d need no provider call");
        assertEquals(2, report.documentsThrottled(), "a, refused, and b, not sent");
        assertEquals(1, provider.refusals, "b is not sent to a provider that has said no");
        assertEquals(0, report.documentsFailed());
    }

    @Test
    @DisplayName("after a throttle, an unchanged listed document is not fetched and counts as unchanged")
    void unchangedFetchedAfterAThrottle() {
        var connector = new Versioned();
        connector.versions.put("a", "1");
        connector.versions.put("b", "1");
        connector.versions.put("c", "1");
        connector.versions.put("d", "1");
        run(connector);

        connector.fetched.clear();
        connector.versions.put("a", "2");
        connector.versions.put("b", "2");
        provider = new QuotaProvider(0, fixture("gemini-429-embed-per-day"));
        IngestionReport report = run(connector);

        assertEquals(IngestionReport.Outcome.THROTTLED, report.outcome());
        assertEquals(List.of("a"), connector.fetched, "b is not fetched once the provider has said no");
        assertEquals(2, report.documentsUnchanged());
        assertEquals(2, report.documentsThrottled());
        assertEquals(0, report.documentsFailed());
    }

    @Test
    @DisplayName("a spent balance fails the run with a clear error, and no document")
    void billingFailureFailsTheRun() {
        provider = new QuotaProvider(0, fixture("openai-429-insufficient-quota"));

        IngestionReport report = run(listing("v1", "a", "b", "c"));

        assertEquals(IngestionReport.Outcome.FAILED, report.outcome());
        assertTrue(report.message().contains("billing"), report.message());
        assertEquals(0, report.documentsFailed(), "no document is at fault");
        assertEquals(0, report.documentsThrottled(), "not a throttle");
        assertEquals(1, provider.refusals, "the run stops at the first refusal");
        IngestionRun record = lastRun();
        assertEquals(IngestionRun.Status.FAILED, record.status());
        assertEquals(report.message(), record.error());
        assertNull(record.resumeAfter());
    }

    @Test
    @DisplayName("a run the provider's billing stopped concludes nothing deleted")
    void billingStopTombstonesNothing() {
        run(listing("v1", "a", "b", "c"));

        provider = new QuotaProvider(0, fixture("openai-429-insufficient-quota"));
        IngestionReport report = run(listing("v2", "a", "b"));

        assertEquals(IngestionReport.Outcome.FAILED, report.outcome());
        assertEquals(0, report.documentsTombstoned(), "c's absence proves nothing in a stopped run");
        assertFalse(stateStore.lookup(sourceKey(), "c").orElseThrow().tombstoned());
    }

    @Test
    @DisplayName("an interrupted wait cancels the run: not paused by the provider, nothing failed")
    void interruptedWaitCancelsTheRun() {
        provider.limit = 1;
        sleeper = duration -> {
            throw new InterruptedException();
        };

        IngestionReport report = run(listing("v1", "a", "b", "c"));
        Thread.interrupted();

        assertEquals(IngestionReport.Outcome.COMPLETED, report.outcome(), "not THROTTLED");
        assertEquals(0, report.documentsThrottled());
        assertEquals(0, report.documentsFailed());
        assertNull(report.resumeAfter());
        assertEquals(IngestionRun.Status.CANCELLED, lastRun().status());
    }

    @Test
    @DisplayName("the gate holds a scheduled run while resumeAfter is ahead, and opens after it")
    void gateFollowsResumeAfter() {
        provider = new QuotaProvider(1, fixture("gemini-429-embed-per-day"));
        IngestionReport report = run(listing("v1", "a", "b"));
        var pipeline = lastPipeline;

        pipeline.clock = () -> report.resumeAfter().minusSeconds(1);
        assertEquals(Optional.of(report.resumeAfter()), pipeline.throttledUntil(KB_RESOURCE_ID, source()));

        pipeline.clock = () -> report.resumeAfter();
        assertTrue(pipeline.throttledUntil(KB_RESOURCE_ID, source()).isEmpty(), "open from resumeAfter on");
    }

    @Test
    @DisplayName("a throttled run with no resume time holds the gate for the cooldown from when it finished")
    void gateUsesTheCooldownWithoutAResumeTime() {
        // A rate limit that names no time, and a run too short to wait the fallback.
        provider = new QuotaProvider(1, "<html>Too Many Requests</html>");
        runBudget = Duration.ofSeconds(10);
        IngestionReport report = run(listing("v1", "a", "b"));
        assertEquals(IngestionReport.Outcome.THROTTLED, report.outcome());
        assertNull(report.resumeAfter(), "the 30 s fallback is not reported as the provider's time");

        var pipeline = lastPipeline;
        pipeline.throttleCooldown = Duration.ofMinutes(5);
        Instant finished = lastRun().finishedAt();
        pipeline.clock = () -> finished.plus(Duration.ofMinutes(4));
        assertEquals(Optional.of(finished.plus(Duration.ofMinutes(5))),
                pipeline.throttledUntil(KB_RESOURCE_ID, source()));
        pipeline.clock = () -> finished.plus(Duration.ofMinutes(5));
        assertTrue(pipeline.throttledUntil(KB_RESOURCE_ID, source()).isEmpty());
    }

    @Test
    @DisplayName("the gate is open when the last run was not throttled, or there is none")
    void gateOpenOtherwise() {
        var pipeline = new IngestionPipeline(SourceConnectors.of(new Listing()), new HtmlToMarkdownConverter(),
                stateStore, new DocumentExtractors(List.of(new PlainTextExtractor())), modelFactory, storeFactory,
                meters);
        assertTrue(pipeline.throttledUntil(KB_RESOURCE_ID, source()).isEmpty(), "no runs");

        run(listing("v1", "a"));
        assertTrue(lastPipeline.throttledUntil(KB_RESOURCE_ID, source()).isEmpty(), "a completed run");
    }
}
