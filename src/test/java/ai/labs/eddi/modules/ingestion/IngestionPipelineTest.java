/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion;

import ai.labs.eddi.configs.rag.model.IngestionSource;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.modules.ingestion.IngestionPipeline.IngestionReport;
import ai.labs.eddi.modules.ingestion.IngestionPipeline.Mode;
import ai.labs.eddi.modules.ingestion.crawl.FakeSite;
import ai.labs.eddi.modules.ingestion.crawl.WebCrawler;
import ai.labs.eddi.modules.llm.impl.EmbeddingModelFactory;
import ai.labs.eddi.modules.llm.impl.EmbeddingStoreFactory;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link IngestionPipeline} — the part that decides what a knowledge base
 * contains.
 *
 * <p>
 * Each failure pinned here was live in the draft this replaces, and every one
 * of them is silent in production: the run reports success, the metrics look
 * healthy, and the agent answers from content that is missing, duplicated or
 * months out of date.
 */
class IngestionPipelineTest {

    private static final String SITE = "https://example.com";
    private static final String KB_RESOURCE_ID = "5a8b1c2d3e4f5a6b7c8d9e0f";
    private static final String KB_NAME = "product-docs";
    private static final String SOURCE_NAME = "docs-crawl";

    private InMemoryIngestionStateStore stateStore;
    private RecordingEmbeddingStore embeddingStore;
    private EmbeddingStoreFactory storeFactory;
    private EmbeddingModelFactory modelFactory;
    private EmbeddingModel embeddingModel;

    @BeforeEach
    void setUp() {
        stateStore = new InMemoryIngestionStateStore();
        embeddingStore = new RecordingEmbeddingStore();
        storeFactory = mock(EmbeddingStoreFactory.class);
        modelFactory = mock(EmbeddingModelFactory.class);
        embeddingModel = mock(EmbeddingModel.class);

        when(storeFactory.getOrCreate(any(RagConfiguration.class), anyString())).thenReturn(embeddingStore);
        when(modelFactory.getOrCreate(any(RagConfiguration.class))).thenReturn(embeddingModel);
        when(embeddingModel.embedAll(any())).thenAnswer(invocation -> {
            List<TextSegment> segments = invocation.getArgument(0);
            return Response.from(segments.stream().map(segment -> Embedding.from(new float[]{0.1f, 0.2f})).toList());
        });
    }

    private IngestionPipeline pipelineFor(FakeSite site) {
        return new IngestionPipeline(new WebCrawler(site), new HtmlToMarkdownConverter(), stateStore,
                modelFactory, storeFactory, new SimpleMeterRegistry());
    }

    private static RagConfiguration knowledgeBase() {
        RagConfiguration config = new RagConfiguration();
        config.setName(KB_NAME);
        config.setChunkSize(400);
        config.setChunkOverlap(0);
        return config;
    }

    private static IngestionSource source() {
        var web = new IngestionSource.WebSource();
        web.setStartUrl(SITE + "/");
        web.setRespectRobots(false);
        web.setRequestDelayMs(0);

        var source = new IngestionSource();
        source.setId("src-1");
        source.setName(SOURCE_NAME);
        source.setWeb(web);
        return source;
    }

    private static String pageWith(String body) {
        return "<html><head><title>Docs</title></head><body><main><p>" + body + "</p></main></body></html>";
    }

    @Nested
    @DisplayName("knowledge base identity")
    class Identity {

        @Test
        @DisplayName("embeds into the knowledge base's store, not one named after the source")
        void keysTheStoreOnTheKnowledgeBase() {
            // The headline defect: the draft used the SOURCE's name as the store key
            // while retrieval used the KNOWLEDGE BASE's, so crawled content went into
            // one pgvector table and every query read another. Ingestion reported
            // success and the agent retrieved nothing. No test caught it because none
            // performed a retrieval after an ingest.
            FakeSite site = new FakeSite().page(SITE + "/", pageWith("Install the thing."));

            pipelineFor(site).run(KB_RESOURCE_ID, knowledgeBase(), source(), Mode.INGEST);

            ArgumentCaptor<String> kbId = ArgumentCaptor.forClass(String.class);
            verify(storeFactory).getOrCreate(any(RagConfiguration.class), kbId.capture());
            assertEquals(KB_NAME, kbId.getValue(),
                    "the store must be keyed by the knowledge base, not by " + SOURCE_NAME);
        }

        @Test
        @DisplayName("fails cleanly when the knowledge base has no name to key on")
        void requiresAKnowledgeBaseName() {
            RagConfiguration nameless = knowledgeBase();
            nameless.setName("  ");

            IngestionReport report = pipelineFor(new FakeSite()).run(KB_RESOURCE_ID, nameless, source(), Mode.INGEST);

            assertEquals(IngestionReport.Outcome.FAILED, report.outcome());
            assertTrue(report.message().contains("name"), report.message());
        }

        @Test
        @DisplayName("scopes ingestion state per knowledge base, so two can crawl the same site")
        void stateIsScopedPerKnowledgeBase() {
            assertFalse(IngestionPipeline.stateKey("kb-a", source())
                    .equals(IngestionPipeline.stateKey("kb-b", source())));
        }
    }

    @Nested
    @DisplayName("replacing content")
    class Replacing {

        @Test
        @DisplayName("stores a new document's segments with citation metadata")
        void storesWithCitationMetadata() {
            FakeSite site = new FakeSite().page(SITE + "/", pageWith("The price is 10 euro."));

            IngestionReport report = pipelineFor(site).run(KB_RESOURCE_ID, knowledgeBase(), source(), Mode.INGEST);

            assertEquals(1, report.documentsIngested());
            assertTrue(report.segmentsStored() > 0);
            var segment = embeddingStore.segments().get(0);
            assertEquals(SITE, segment.metadata().getString(IngestionPipeline.METADATA_DOCUMENT_ID));
            assertNotNull(segment.metadata().getString(IngestionPipeline.METADATA_URL),
                    "an answer has to be able to cite where it came from");
            assertEquals("Docs", segment.metadata().getString(IngestionPipeline.METADATA_TITLE));
            assertEquals(SOURCE_NAME, segment.metadata().getString(IngestionPipeline.METADATA_SOURCE));
        }

        @Test
        @DisplayName("re-ingesting changed content replaces the old chunks instead of adding to them")
        void reIngestReplaces() {
            // The draft called EmbeddingStoreIngestor.ingest, which only appends, and
            // nothing ever removed anything. A page edited weekly left a year of stale
            // versions retrievable beside the current one.
            FakeSite first = new FakeSite().page(SITE + "/", pageWith("The price is 10 euro."));
            pipelineFor(first).run(KB_RESOURCE_ID, knowledgeBase(), source(), Mode.INGEST);

            FakeSite second = new FakeSite().page(SITE + "/", pageWith("The price is 25 euro."));
            pipelineFor(second).run(KB_RESOURCE_ID, knowledgeBase(), source(), Mode.INGEST);

            List<String> texts = embeddingStore.textsOf(SITE);
            assertTrue(texts.stream().anyMatch(text -> text.contains("25 euro")), "the new price must be stored");
            assertFalse(texts.stream().anyMatch(text -> text.contains("10 euro")),
                    "the superseded price must be gone, was: " + texts);
        }

        @Test
        @DisplayName("unchanged content is neither re-embedded nor re-charged")
        void unchangedContentIsSkipped() {
            FakeSite site = new FakeSite().page(SITE + "/", pageWith("Stable content."));
            pipelineFor(site).run(KB_RESOURCE_ID, knowledgeBase(), source(), Mode.INGEST);
            int afterFirst = embeddingStore.segments().size();

            IngestionReport report = pipelineFor(new FakeSite().page(SITE + "/", pageWith("Stable content.")))
                    .run(KB_RESOURCE_ID, knowledgeBase(), source(), Mode.INGEST);

            assertEquals(1, report.documentsUnchanged());
            assertEquals(0, report.documentsIngested());
            assertEquals(afterFirst, embeddingStore.segments().size(), "nothing should have been re-embedded");
        }

        @Test
        @DisplayName("a store that cannot delete is reported, not silently duplicated into")
        void unsupportedRemovalIsReported() {
            embeddingStore = new RecordingEmbeddingStore().withoutRemovalSupport();
            when(storeFactory.getOrCreate(any(RagConfiguration.class), anyString())).thenReturn(embeddingStore);
            FakeSite site = new FakeSite().page(SITE + "/", pageWith("Content."));

            IngestionReport report = pipelineFor(site).run(KB_RESOURCE_ID, knowledgeBase(), source(), Mode.INGEST);

            assertTrue(report.replaceUnsupported(),
                    "the operator has to learn that this store accumulates stale chunks");
            assertEquals(IngestionReport.Outcome.COMPLETED, report.outcome(), "but the run still succeeds");
        }

        @Test
        @DisplayName("reports the segments actually written, not an estimate")
        void reportsRealSegmentCount() {
            // The draft reported markdown.length() / chunkSize and its own integration
            // test asserted on that invented number.
            FakeSite site = new FakeSite().page(SITE + "/", pageWith("word ".repeat(500)));

            IngestionReport report = pipelineFor(site).run(KB_RESOURCE_ID, knowledgeBase(), source(), Mode.INGEST);

            assertEquals(embeddingStore.segments().size(), report.segmentsStored());
        }
    }

    @Nested
    @DisplayName("failure handling")
    class Failures {

        @Test
        @DisplayName("a document is not recorded when embedding fails, so the next run retries it")
        void embeddingFailureIsNotRecordedAsDone() {
            // The draft committed the content hash while DECIDING whether to ingest,
            // with embedding happening afterwards inside a catch that only logged. One
            // 429 therefore marked a page done forever: the hash matched on every later
            // run, so it reported "unchanged" and was never embedded again.
            doThrow(new RuntimeException("provider returned 429")).when(embeddingModel).embedAll(any());
            FakeSite site = new FakeSite().page(SITE + "/", pageWith("Important content."));

            IngestionReport failedRun = pipelineFor(site).run(KB_RESOURCE_ID, knowledgeBase(), source(), Mode.INGEST);

            assertEquals(1, failedRun.documentsFailed());
            assertTrue(stateStore.lookup(IngestionPipeline.stateKey(KB_RESOURCE_ID, source()), SITE).isEmpty(),
                    "a document whose vectors were never stored must not be recorded as ingested");

            // The next run must try again rather than call it unchanged. doAnswer,
            // not when(...), because re-stubbing through when() invokes the mock and
            // would trip the throwing stub that is still in place.
            doAnswer(invocation -> {
                List<TextSegment> retried = invocation.getArgument(0);
                return Response.from(retried.stream().map(segment -> Embedding.from(new float[]{0.1f})).toList());
            }).when(embeddingModel).embedAll(any());
            IngestionReport retry = pipelineFor(new FakeSite().page(SITE + "/", pageWith("Important content.")))
                    .run(KB_RESOURCE_ID, knowledgeBase(), source(), Mode.INGEST);

            assertEquals(1, retry.documentsIngested(), "the page must be retried, not reported unchanged");
        }

        @Test
        @DisplayName("one failing page does not abandon the rest")
        void oneBadPageDoesNotStopTheRun() {
            FakeSite site = new FakeSite()
                    .page(SITE + "/", "<html><body><a href=\"" + SITE + "/broken\">a</a>"
                            + "<a href=\"" + SITE + "/good\">b</a></body></html>")
                    .status(SITE + "/broken", 500)
                    .page(SITE + "/good", pageWith("Good content."));

            IngestionReport report = pipelineFor(site).run(KB_RESOURCE_ID, knowledgeBase(), source(), Mode.INGEST);

            assertEquals(1, report.documentsFailed());
            assertTrue(report.documentsIngested() >= 1);
        }
    }

    @Nested
    @DisplayName("deletion")
    class Deletion {

        private void ingestTwoPages() {
            FakeSite site = new FakeSite()
                    .page(SITE + "/", "<html><body><a href=\"" + SITE + "/keep\">k</a>"
                            + "<a href=\"" + SITE + "/gone\">g</a></body></html>")
                    .page(SITE + "/keep", pageWith("Still here."))
                    .page(SITE + "/gone", pageWith("Will vanish."));
            pipelineFor(site).run(KB_RESOURCE_ID, knowledgeBase(), source(), Mode.INGEST);
        }

        @Test
        @DisplayName("a page removed from the site has its vectors deleted after the miss threshold")
        void vanishedPageIsRemovedFromTheStore() {
            // "Stale detection" in the draft only flipped a flag in a side table that
            // nothing consulted at retrieval time, so a deleted page kept answering
            // questions forever.
            ingestTwoPages();
            assertFalse(embeddingStore.segmentsOf(SITE + "/gone").isEmpty());

            FakeSite shrunk = new FakeSite()
                    .page(SITE + "/", "<html><body><a href=\"" + SITE + "/keep\">k</a></body></html>")
                    .page(SITE + "/keep", pageWith("Still here."));
            // Threshold is 2 by default, so the first miss only counts.
            pipelineFor(shrunk).run(KB_RESOURCE_ID, knowledgeBase(), source(), Mode.INGEST);
            assertFalse(embeddingStore.segmentsOf(SITE + "/gone").isEmpty(), "one miss is not a deletion");

            IngestionReport report = pipelineFor(shrunk).run(KB_RESOURCE_ID, knowledgeBase(), source(), Mode.INGEST);

            assertEquals(1, report.documentsTombstoned());
            assertTrue(embeddingStore.segmentsOf(SITE + "/gone").isEmpty(),
                    "a page deleted from the site must stop being retrievable");
            assertFalse(embeddingStore.segmentsOf(SITE + "/keep").isEmpty(), "the surviving page must be untouched");
        }

        @Test
        @DisplayName("a crawl that hit its limits concludes nothing about deletions")
        void partialCrawlDoesNotTombstone() {
            // The draft marked everything not seen in the current run as stale,
            // unconditionally — so hitting maxPages flagged the rest of the corpus.
            ingestTwoPages();

            IngestionSource capped = source();
            capped.getWeb().setMaxPages(1);
            FakeSite site = new FakeSite().page(SITE + "/", "<html><body><a href=\"" + SITE + "/keep\">k</a></body></html>")
                    .page(SITE + "/keep", pageWith("Still here."));

            IngestionReport report = pipelineFor(site).run(KB_RESOURCE_ID, knowledgeBase(), capped, Mode.INGEST);

            assertTrue(report.tombstoningSkipped(),
                    "a crawl that stopped at its page cap saw an arbitrary subset of the source");
            assertEquals(0, report.documentsTombstoned());
            assertFalse(embeddingStore.segmentsOf(SITE + "/gone").isEmpty(), "nothing may be deleted on a partial run");
        }
    }

    @Nested
    @DisplayName("run control")
    class RunControl {

        @Test
        @DisplayName("a second run while one is in flight is refused, not started")
        void refusesConcurrentRuns() {
            String sourceKey = IngestionPipeline.stateKey(KB_RESOURCE_ID, source());
            stateStore.startRun(sourceKey);

            IngestionReport report = pipelineFor(new FakeSite())
                    .run(KB_RESOURCE_ID, knowledgeBase(), source(), Mode.INGEST);

            assertEquals(IngestionReport.Outcome.ALREADY_RUNNING, report.outcome(),
                    "five clicks on 'run now' must not become five crawls into one store");
        }

        @Test
        @DisplayName("a disabled source does not run")
        void skipsDisabledSource() {
            IngestionSource disabled = source();
            disabled.setEnabled(false);

            IngestionReport report = pipelineFor(new FakeSite().page(SITE + "/", pageWith("x")))
                    .run(KB_RESOURCE_ID, knowledgeBase(), disabled, Mode.INGEST);

            assertEquals(IngestionReport.Outcome.SKIPPED, report.outcome());
            assertTrue(embeddingStore.segments().isEmpty());
        }

        @Test
        @DisplayName("preview reports what would change without embedding or recording anything")
        void previewChangesNothing() {
            FakeSite site = new FakeSite().page(SITE + "/", pageWith("Content."));

            IngestionReport report = pipelineFor(site).run(KB_RESOURCE_ID, knowledgeBase(), source(), Mode.PREVIEW);

            assertEquals(IngestionReport.Outcome.PREVIEW, report.outcome());
            assertEquals(1, report.documentsIngested(), "preview still reports what it would have ingested");
            assertTrue(embeddingStore.segments().isEmpty(), "preview must not embed");
            assertTrue(stateStore.lookup(IngestionPipeline.stateKey(KB_RESOURCE_ID, source()), SITE).isEmpty(),
                    "preview must not record state");
            assertTrue(stateStore.listRuns(IngestionPipeline.stateKey(KB_RESOURCE_ID, source()), 10).isEmpty());
        }

        @Test
        @DisplayName("a completed run is written to the history with its counters")
        void recordsRunHistory() {
            FakeSite site = new FakeSite().page(SITE + "/", pageWith("Content."));

            pipelineFor(site).run(KB_RESOURCE_ID, knowledgeBase(), source(), Mode.INGEST);

            var runs = stateStore.listRuns(IngestionPipeline.stateKey(KB_RESOURCE_ID, source()), 10);
            assertEquals(1, runs.size());
            assertEquals(IIngestionStateStore.IngestionRun.Status.COMPLETED, runs.get(0).status());
            assertEquals(1, runs.get(0).documentsIngested());
            assertTrue(runs.get(0).segmentsStored() > 0);
        }

        @Test
        @DisplayName("the segment budget stops a run that would cost too much")
        void segmentBudgetStopsTheRun() {
            IngestionSource capped = source();
            var settings = new IngestionSource.IngestionSettings();
            settings.setMaxSegmentsPerRun(1);
            capped.setSettings(settings);

            FakeSite site = new FakeSite()
                    .page(SITE + "/", "<html><body><a href=\"" + SITE + "/a\">a</a>"
                            + "<a href=\"" + SITE + "/b\">b</a></body></html>")
                    .page(SITE + "/a", pageWith("word ".repeat(200)))
                    .page(SITE + "/b", pageWith("word ".repeat(200)));

            IngestionReport report = pipelineFor(site).run(KB_RESOURCE_ID, knowledgeBase(), capped, Mode.INGEST);

            assertEquals(WebCrawler.StopReason.CANCELLED, report.stopReason());
            assertTrue(report.tombstoningSkipped(), "a budget-stopped run must not conclude anything is deleted");
        }

        @Test
        @DisplayName("a configured rate turns segments into a reported cost")
        void reportsCostWhenRateConfigured() {
            IngestionSource priced = source();
            var settings = new IngestionSource.IngestionSettings();
            settings.setCostPerThousandSegments(2.0);
            priced.setSettings(settings);
            FakeSite site = new FakeSite().page(SITE + "/", pageWith("Content."));

            IngestionReport report = pipelineFor(site).run(KB_RESOURCE_ID, knowledgeBase(), priced, Mode.INGEST);

            assertEquals(report.segmentsStored() / 1000.0 * 2.0, report.costUsd(), 0.0001);
        }
    }

    @Nested
    @DisplayName("conditional fetching")
    class Conditional {

        @Test
        @DisplayName("stored validators are sent on the next run and a 304 costs nothing")
        void usesStoredValidators() {
            FakeSite first = new FakeSite()
                    .pageWithValidators(SITE + "/", pageWith("Content."), "\"v1\"", null);
            pipelineFor(first).run(KB_RESOURCE_ID, knowledgeBase(), source(), Mode.INGEST);
            int afterFirst = embeddingStore.segments().size();

            FakeSite second = new FakeSite().conditional(SITE + "/", pageWith("Content."), "\"v1\"");
            IngestionReport report = pipelineFor(second).run(KB_RESOURCE_ID, knowledgeBase(), source(), Mode.INGEST);

            assertEquals(1, report.documentsUnchanged());
            assertEquals(afterFirst, embeddingStore.segments().size());
            var command = second.requests().stream()
                    .filter(request -> request.url().equals(SITE + "/"))
                    .findFirst().orElseThrow();
            assertEquals("\"v1\"", command.ifNoneMatch(), "the ETag from the last run must be sent back");
        }
    }

    @Nested
    @DisplayName("configuration validation")
    class Validation {

        @Test
        @DisplayName("a source with only some fields set gets defaults rather than a rejection")
        void partialConfigurationIsAccepted() {
            // The draft's config records threw on a missing maxPages or maxDepth —
            // exactly what Jackson supplies for an omitted field — so a config setting
            // only a path prefix was rejected outright.
            var web = new IngestionSource.WebSource();
            web.setStartUrl(SITE + "/");
            web.setPathPrefix("/docs/");
            web.setMaxPages(null);
            web.setMaxDepth(null);

            var partial = new IngestionSource();
            partial.setName("partial");
            partial.setWeb(web);

            partial.validate();
        }

        @Test
        @DisplayName("a value the operator actually typed is still held to its limits")
        void nonsensicalValuesAreRejected() {
            var web = new IngestionSource.WebSource();
            web.setStartUrl(SITE + "/");
            web.setMaxDepth(0);
            var invalid = new IngestionSource();
            invalid.setName("bad");
            invalid.setWeb(web);

            var thrown = assertThrows(IllegalArgumentException.class,
                    invalid::validate);
            assertTrue(thrown.getMessage().contains("maxDepth"), thrown.getMessage());
        }

        @Test
        @DisplayName("a non-http start URL is rejected")
        void rejectsNonHttpStartUrl() {
            var web = new IngestionSource.WebSource();
            web.setStartUrl("file:///etc/passwd");
            var invalid = new IngestionSource();
            invalid.setName("bad");
            invalid.setWeb(web);

            assertThrows(IllegalArgumentException.class, invalid::validate);
        }

        @Test
        @DisplayName("a tombstone threshold below one is refused — one miss is not a deletion")
        void refusesHairTriggerTombstoning() {
            var settings = new IngestionSource.IngestionSettings();
            settings.setTombstoneAfterMissedRuns(0);
            var source = source();
            source.setSettings(settings);

            var thrown = assertThrows(IllegalArgumentException.class,
                    source::validate);
            assertTrue(thrown.getMessage().contains("tombstoneAfterMissedRuns"), thrown.getMessage());
        }

        @Test
        @DisplayName("the knowledge base validates its sources")
        void knowledgeBaseValidatesSources() {
            RagConfiguration config = knowledgeBase();
            var broken = new IngestionSource();
            broken.setName("broken");
            config.setSources(List.of(broken));

            assertThrows(IllegalArgumentException.class, config::validate);
        }

        @Test
        @DisplayName("a knowledge base finds its own source by id")
        void findsSourceById() {
            RagConfiguration config = knowledgeBase();
            config.setSources(List.of(source()));

            assertEquals(SOURCE_NAME, config.findSource("src-1").getName());
            assertEquals(null, config.findSource("nope"));
            assertEquals(null, config.findSource(null));
        }
    }
}
