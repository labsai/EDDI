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
import ai.labs.eddi.modules.llm.impl.ThrottledEmbeddingModel;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.request.EmbeddingInputType;
import dev.langchain4j.model.output.Response;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
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
        // The factory's DOCUMENT model, as it builds it: the throttle decorator over
        // the provider — here with a clock and a sleep the test controls.
        when(modelFactory.getOrCreate(any(RagConfiguration.class), any(EmbeddingInputType.class)))
                .thenAnswer(invocation -> ThrottledEmbeddingModel.of(provider, "gemini", 5, true, meters,
                        duration -> {
                            sleeps.add(duration);
                            now = now.plus(duration);
                        }, () -> now));
    }

    /**
     * A provider with a quota of {@code limit} calls per window: past it, every
     * call is refused with the given 429 body until the clock has moved 26 s on.
     */
    private final class QuotaProvider implements EmbeddingModel {
        int limit;
        String body;
        int callsInWindow;
        Instant blockedUntil = Instant.MIN;
        int refusals;

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
            if (callsInWindow >= limit) {
                callsInWindow = 0;
                blockedUntil = now.plusSeconds(26);
                refusals++;
                throw new RateLimitException(new HttpException(429, body));
            }
            callsInWindow++;
            return Response.from(segments.stream().map(s -> Embedding.from(new float[]{0.1f})).toList());
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

    private IngestionReport run(Listing connector) {
        var pipeline = new IngestionPipeline(SourceConnectors.of(connector), new HtmlToMarkdownConverter(), stateStore,
                new DocumentExtractors(List.of(new PlainTextExtractor())), modelFactory, storeFactory, meters) {
            @Override
            Instant uploadDeadline(Instant start, IngestionSource source) {
                return start.plus(runBudget);
            }
        };
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
        provider.callsInWindow = 0;
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
        assertNull(report.resumeAfter(), "the provider named no time a daily quota comes back");
    }
}
