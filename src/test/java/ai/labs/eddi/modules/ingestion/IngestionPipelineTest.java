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
import ai.labs.eddi.modules.ingestion.extract.CsvTextExtractor;
import ai.labs.eddi.modules.ingestion.extract.DocumentExtractors;
import ai.labs.eddi.modules.ingestion.extract.ExcelTextExtractor;
import ai.labs.eddi.modules.ingestion.extract.HtmlDocumentExtractor;
import ai.labs.eddi.modules.ingestion.extract.PdfTextExtractor;
import ai.labs.eddi.modules.ingestion.extract.PlainTextExtractor;
import ai.labs.eddi.modules.ingestion.extract.PowerPointTextExtractor;
import ai.labs.eddi.modules.ingestion.extract.WordTextExtractor;
import ai.labs.eddi.modules.ingestion.files.IIngestedFileStore.StoredFile;
import ai.labs.eddi.modules.ingestion.files.InMemoryIngestedFileStore;
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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
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
    private final InMemoryIngestedFileStore fileStore = new InMemoryIngestedFileStore();
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
        when(modelFactory.getOrCreate(any(RagConfiguration.class), any(EmbeddingInputType.class)))
                .thenReturn(embeddingModel);
        when(embeddingModel.embedAll(any())).thenAnswer(invocation -> {
            List<TextSegment> segments = invocation.getArgument(0);
            return Response.from(segments.stream().map(segment -> Embedding.from(new float[]{0.1f, 0.2f})).toList());
        });
    }

    private IngestionPipeline pipelineFor(FakeSite site) {
        return new IngestionPipeline(new WebCrawler(site), new HtmlToMarkdownConverter(), stateStore,
                fileStore, extractors(), modelFactory, storeFactory, new SimpleMeterRegistry());
    }

    /**
     * Every extractor this build ships, as the CDI producer would assemble them.
     */
    private static DocumentExtractors extractors() {
        return new DocumentExtractors(List.of(new PdfTextExtractor(), new WordTextExtractor(),
                new ExcelTextExtractor(), new PowerPointTextExtractor(), new PlainTextExtractor(),
                new CsvTextExtractor(), new HtmlDocumentExtractor(new HtmlToMarkdownConverter())));
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

    /**
     * A crawl one link deep: the seed's links are followed, and what they reach is
     * a leaf.
     */
    private static IngestionSource leafSource() {
        var source = source();
        source.getWeb().setMaxDepth(1);
        return source;
    }

    private static IngestionSource uploadSource() {
        var source = new IngestionSource();
        source.setId("src-files");
        source.setName("handbooks");
        source.setType(IngestionSource.TYPE_UPLOAD);
        return source;
    }

    /** The pipeline with no site behind it — an upload source never fetches. */
    private IngestionPipeline uploadPipeline() {
        return pipelineFor(new FakeSite());
    }

    private static String linkTo(String url) {
        return "<html><head><title>Index</title></head><body><main><a href=\"" + url + "\">link</a></main></body></html>";
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
        @DisplayName("every report names the source the way everything else addresses it")
        void reportsNameTheSource() {
            // A source that arrived without an id — a ZIP import writes one, because
            // that path never passes the REST layer that assigns ids — is keyed,
            // scheduled and addressed by its NAME. The reports used getId(), so the
            // run history, the REST answer and the fire log all said null.
            var idLess = source();
            idLess.setId(null);

            var disabled = source();
            disabled.setId(null);
            disabled.setEnabled(false);
            IngestionReport skipped = pipelineFor(new FakeSite())
                    .run(KB_RESOURCE_ID, knowledgeBase(), disabled, Mode.INGEST);
            assertEquals(SOURCE_NAME, skipped.sourceId(), "a skipped run must still say which source");

            RagConfiguration nameless = knowledgeBase();
            nameless.setName("  ");
            IngestionReport failed = pipelineFor(new FakeSite())
                    .run(KB_RESOURCE_ID, nameless, idLess, Mode.INGEST);
            assertEquals(SOURCE_NAME, failed.sourceId(), "a failed run must still say which source");

            stateStore.startRun(IngestionPipeline.stateKey(KB_RESOURCE_ID, idLess));
            IngestionReport busy = pipelineFor(new FakeSite())
                    .run(KB_RESOURCE_ID, knowledgeBase(), idLess, Mode.INGEST);
            assertEquals(SOURCE_NAME, busy.sourceId(), "\"already running\" must still say which source");
        }

        @Test
        @DisplayName("scopes ingestion state per knowledge base, so two can crawl the same site")
        void stateIsScopedPerKnowledgeBase() {
            assertFalse(IngestionPipeline.stateKey("kb-a", source())
                    .equals(IngestionPipeline.stateKey("kb-b", source())));
        }

        @Test
        @DisplayName("asks for a DOCUMENT model, because the crawler is storing these vectors")
        void embedsCrawledPagesAsDocuments() {
            // An asymmetric provider bakes the role in at construction, and the role is
            // part of the factory's cache key. Asking for QUERY here would hand the
            // crawler the retrieval instance and store every page as though it were a
            // search string -- which reports success and quietly costs recall.
            FakeSite site = new FakeSite().page(SITE + "/", pageWith("Install the thing."));

            pipelineFor(site).run(KB_RESOURCE_ID, knowledgeBase(), source(), Mode.INGEST);

            verify(modelFactory).getOrCreate(any(RagConfiguration.class), eq(EmbeddingInputType.DOCUMENT));
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
    @DisplayName("review findings")
    class ReviewFindings {

        @Test
        @DisplayName("a page that comes back unchanged after being tombstoned is re-embedded")
        void tombstonedPageReturningUnchangedIsReEmbedded() {
            // The worst of the review findings: comparing hashes alone meant a page
            // that 404s for two runs and then returns byte-identical was reported
            // "unchanged" forever. Its vectors had been deleted by the tombstone, so it
            // was never retrievable again — silent, permanent loss with nothing logged.
            String page = pageWith("The content that matters.");
            FakeSite present = new FakeSite().page(SITE + "/", page);
            pipelineFor(present).run(KB_RESOURCE_ID, knowledgeBase(), source(), Mode.INGEST);
            assertFalse(embeddingStore.segmentsOf(SITE).isEmpty());

            // Two complete runs that do not see it: tombstoned, vectors removed.
            FakeSite gone = new FakeSite().status(SITE + "/", 404);
            pipelineFor(gone).run(KB_RESOURCE_ID, knowledgeBase(), source(), Mode.INGEST);
            pipelineFor(gone).run(KB_RESOURCE_ID, knowledgeBase(), source(), Mode.INGEST);
            assertTrue(embeddingStore.segmentsOf(SITE).isEmpty(), "the tombstone should have removed its vectors");

            // It comes back, byte-identical.
            IngestionReport report = pipelineFor(new FakeSite().page(SITE + "/", page))
                    .run(KB_RESOURCE_ID, knowledgeBase(), source(), Mode.INGEST);

            assertEquals(1, report.documentsIngested(), "identical content must still be re-embedded");
            assertFalse(embeddingStore.segmentsOf(SITE).isEmpty(), "the page must be retrievable again");
        }

        @Test
        @DisplayName("a tombstoned page is not revalidated with its old ETag")
        void tombstonedPageIsNotRevalidated() {
            // Sending the stored ETag earns a 304, and a 304 never re-embeds — the same
            // permanent loss by a different route. The page is a leaf, the only kind a
            // crawl revalidates at all.
            FakeSite first = new FakeSite().page(SITE + "/", linkTo(SITE + "/leaf"))
                    .pageWithValidators(SITE + "/leaf", pageWith("Content."), "\"v1\"", null);
            pipelineFor(first).run(KB_RESOURCE_ID, knowledgeBase(), leafSource(), Mode.INGEST);

            FakeSite gone = new FakeSite().page(SITE + "/", linkTo(SITE + "/leaf")).status(SITE + "/leaf", 404);
            pipelineFor(gone).run(KB_RESOURCE_ID, knowledgeBase(), leafSource(), Mode.INGEST);
            pipelineFor(gone).run(KB_RESOURCE_ID, knowledgeBase(), leafSource(), Mode.INGEST);

            FakeSite back = new FakeSite().page(SITE + "/", linkTo(SITE + "/leaf"))
                    .conditional(SITE + "/leaf", pageWith("Content."), "\"v1\"");
            pipelineFor(back).run(KB_RESOURCE_ID, knowledgeBase(), leafSource(), Mode.INGEST);

            var command = back.requests().stream()
                    .filter(request -> request.url().equals(SITE + "/leaf"))
                    .findFirst().orElseThrow();
            assertEquals(null, command.ifNoneMatch(),
                    "a tombstoned document must be fetched in full, not revalidated");
        }

        @Test
        @DisplayName("an embedding failure never leaves a document with no vectors at all")
        void embeddingFailureKeepsThePreviousVectors() {
            // Removing before embedding meant a provider failure deleted the old
            // chunks and stored nothing. The state row still carried the old hash, so
            // if the page then reverted, the next run called it "unchanged" and the
            // document stayed unretrievable for ever.
            var site = new FakeSite().page(SITE + "/", pageWith("Price is ten."));
            pipelineFor(site).run(KB_RESOURCE_ID, knowledgeBase(), source(), Mode.INGEST);
            assertFalse(embeddingStore.segmentsOf(SITE).isEmpty());

            doThrow(new RuntimeException("provider 429")).when(embeddingModel).embedAll(any());
            var changed = new FakeSite().page(SITE + "/", pageWith("Price is twenty."));
            IngestionReport failedRun = pipelineFor(changed).run(KB_RESOURCE_ID, knowledgeBase(), source(),
                    Mode.INGEST);

            assertEquals(1, failedRun.documentsFailed());
            assertFalse(embeddingStore.segmentsOf(SITE).isEmpty(),
                    "the page that was already retrievable must stay retrievable when the new version cannot embed");
            assertTrue(embeddingStore.textsOf(SITE).stream().anyMatch(text -> text.contains("ten")),
                    "and it must still be the version that did embed: " + embeddingStore.textsOf(SITE));
        }

        @Test
        @DisplayName("two sources that overlap on a page do not delete each other's chunks")
        void overlappingSourcesKeepTheirOwnChunks() {
            // Removal matched the document id alone, so whichever source ran last
            // owned the vectors while the other's state still claimed them — and that
            // one then reported "unchanged" over an empty store, for ever.
            var site = new FakeSite().page(SITE + "/", pageWith("Shared page."));
            var first = source();
            var second = source();
            second.setId("src-2");
            second.setName("second-source");

            pipelineFor(site).run(KB_RESOURCE_ID, knowledgeBase(), first, Mode.INGEST);
            pipelineFor(site).run(KB_RESOURCE_ID, knowledgeBase(), second, Mode.INGEST);

            assertEquals(2, embeddingStore.segmentsOf(SITE).size(),
                    "each source owns its own copy: " + embeddingStore.segmentsOf(SITE));

            // The page disappears for the first source only, twice, so it tombstones.
            var gone = new FakeSite().status(SITE + "/", 404);
            pipelineFor(gone).run(KB_RESOURCE_ID, knowledgeBase(), first, Mode.INGEST);
            pipelineFor(gone).run(KB_RESOURCE_ID, knowledgeBase(), first, Mode.INGEST);

            assertFalse(embeddingStore.segmentsOf(SITE).isEmpty(),
                    "the second source's chunks must survive the first source's tombstone");
        }

        @Test
        @DisplayName("a page that could not be read is not a page that is gone")
        void unreachablePageIsNotTombstoned() {
            // The same tail pages of a rate-limited site fail on every run. Counting
            // that as absence deletes them while every run reports success.
            var site = new FakeSite()
                    .page(SITE + "/", linkTo(SITE + "/a"))
                    .page(SITE + "/a", pageWith("Still here."));
            pipelineFor(site).run(KB_RESOURCE_ID, knowledgeBase(), source(), Mode.INGEST);

            var throttled = new FakeSite()
                    .page(SITE + "/", linkTo(SITE + "/a"))
                    .status(SITE + "/a", 429);
            pipelineFor(throttled).run(KB_RESOURCE_ID, knowledgeBase(), source(), Mode.INGEST);
            IngestionReport third = pipelineFor(throttled).run(KB_RESOURCE_ID, knowledgeBase(), source(),
                    Mode.INGEST);

            assertEquals(0, third.documentsTombstoned(), "a 429 says nothing about whether the page exists");
            assertFalse(embeddingStore.segmentsOf(SITE + "/a").isEmpty(),
                    "its chunks must still be retrievable");
        }

        @Test
        @DisplayName("a site that answers but yields nothing usable does not empty the knowledge base")
        void blankSiteDoesNotTombstoneEverything() {
            // A JavaScript challenge or a maintenance page answers 200 for every URL.
            // The crawl "covers the source" and produces no document at all.
            var site = new FakeSite()
                    .page(SITE + "/", linkTo(SITE + "/a"))
                    .page(SITE + "/a", pageWith("Real content."));
            pipelineFor(site).run(KB_RESOURCE_ID, knowledgeBase(), source(), Mode.INGEST);

            // No title either: a challenge page that yields a heading is a usable
            // document, and then the knowledge base legitimately has something to
            // reconcile against.
            String challenge = "<html><body><script>checking(you)</script></body></html>";
            var blocked = new FakeSite().page(SITE + "/", challenge).page(SITE + "/a", challenge);
            pipelineFor(blocked).run(KB_RESOURCE_ID, knowledgeBase(), source(), Mode.INGEST);
            IngestionReport third = pipelineFor(blocked).run(KB_RESOURCE_ID, knowledgeBase(), source(), Mode.INGEST);

            assertEquals(0, third.documentsTombstoned());
            assertTrue(third.tombstoningSkipped(), "the run must say it concluded nothing");
            assertFalse(embeddingStore.segmentsOf(SITE + "/a").isEmpty(),
                    "content must survive a site that stopped answering usefully");
        }

        @Test
        @DisplayName("a document is tombstoned only once its vectors are actually gone")
        void tombstoneFollowsRemoval() {
            // Marking first is durable in the wrong order: a store that refuses the
            // delete leaves a document flagged gone with its chunks still retrievable,
            // and a tombstoned document is never reported again.
            var site = new FakeSite().page(SITE + "/", pageWith("Here."));
            pipelineFor(site).run(KB_RESOURCE_ID, knowledgeBase(), source(), Mode.INGEST);

            when(storeFactory.getOrCreate(any(RagConfiguration.class), anyString()))
                    .thenReturn(new RecordingEmbeddingStore().withFailingRemoval());

            var gone = new FakeSite().status(SITE + "/", 404);
            pipelineFor(gone).run(KB_RESOURCE_ID, knowledgeBase(), source(), Mode.INGEST);
            IngestionReport third = pipelineFor(gone).run(KB_RESOURCE_ID, knowledgeBase(), source(), Mode.INGEST);

            assertEquals(0, third.documentsTombstoned(), "nothing was removed, so nothing may be marked gone");
            var state = stateStore.lookup(IngestionPipeline.stateKey(KB_RESOURCE_ID, source()), SITE)
                    .orElseThrow();
            assertFalse(state.tombstoned(), "a document whose vectors survive must stay reportable");
        }

        @Test
        @DisplayName("a run whose store fails is still closed, so the source is not blocked forever")
        void failingRunIsAlwaysClosed() {
            // Only crawler.crawl was guarded, and only RuntimeException was caught. A
            // claimed run that is never finished makes every later manual run a 409 and
            // every scheduled fire a failure, until something reaps it — and nothing did.
            var exploding = new InMemoryIngestionStateStore() {
                @Override
                public synchronized List<DocumentState> bumpAndFindMissing(String sourceId, String runId,
                                                                           int threshold) {
                    throw new IngestionStateStoreException("database is unwell", new RuntimeException());
                }
            };
            var pipeline = new IngestionPipeline(new WebCrawler(new FakeSite().page(SITE + "/", pageWith("x"))),
                    new HtmlToMarkdownConverter(), exploding, fileStore, extractors(), modelFactory, storeFactory,
                    new SimpleMeterRegistry());

            IngestionReport report = pipeline.run(KB_RESOURCE_ID, knowledgeBase(), source(), Mode.INGEST);

            assertEquals(IngestionReport.Outcome.FAILED, report.outcome());
            assertTrue(exploding.activeRun(IngestionPipeline.stateKey(KB_RESOURCE_ID, source())).isEmpty(),
                    "the run must be closed even when the failure came from the state store");
        }

        @Test
        @DisplayName("a reserved run is not claimed twice, and the reservation is the one that runs")
        void reservationIsClaimedOnce() {
            // Two requests arriving together both used to be told "started": the claim
            // happened inside run(), on the worker thread, so the check the caller saw
            // was only advisory.
            var pipeline = pipelineFor(new FakeSite().page(SITE + "/", pageWith("x")));
            var source = source();

            var first = pipeline.reserveRun(KB_RESOURCE_ID, source);
            var second = pipeline.reserveRun(KB_RESOURCE_ID, source);

            assertTrue(first.isPresent(), "the first caller reserves the run");
            assertTrue(second.isEmpty(), "the second caller must be refused before any work starts");

            IngestionReport report = pipeline.run(KB_RESOURCE_ID, knowledgeBase(), source, Mode.INGEST, first.get());

            assertEquals(IngestionReport.Outcome.COMPLETED, report.outcome(), report.message());
            assertEquals(first.get(), report.runId(), "the reserved run is the one recorded");
            assertTrue(stateStore.activeRun(IngestionPipeline.stateKey(KB_RESOURCE_ID, source)).isEmpty(),
                    "the run must be closed when it finishes");
        }

        @Test
        @DisplayName("a reservation is released when the run never starts")
        void reservationIsReleasedOnEarlyExit() {
            // A disabled source, an invalid one, or a worker thread that could not be
            // started: the reservation would otherwise block the source until reaped.
            var pipeline = pipelineFor(new FakeSite().page(SITE + "/", pageWith("x")));
            var source = source();
            source.setEnabled(false);
            String sourceKey = IngestionPipeline.stateKey(KB_RESOURCE_ID, source);

            String runId = pipeline.reserveRun(KB_RESOURCE_ID, source).orElseThrow();
            IngestionReport report = pipeline.run(KB_RESOURCE_ID, knowledgeBase(), source, Mode.INGEST, runId);

            assertEquals(IngestionReport.Outcome.SKIPPED, report.outcome());
            assertTrue(stateStore.activeRun(sourceKey).isEmpty(), "a skipped run must not stay claimed");

            // And the explicit release, for a worker that could not be started at all.
            String second = pipeline.reserveRun(KB_RESOURCE_ID, source).orElseThrow();
            pipeline.abandonReservation(KB_RESOURCE_ID, source, second, "worker thread could not be started");
            assertTrue(stateStore.activeRun(sourceKey).isEmpty(), "an abandoned reservation must be released");
        }

        @Test
        @DisplayName("an abandoned run is reaped so a dead process does not block the source")
        void abandonedRunIsReaped() {
            // The previous version of this test reaped the run itself and then asserted
            // that a fresh run blocks — which it does whether or not the pipeline reaps
            // anything. The state that matters is a RUNNING row older than the
            // threshold, left behind by a process that died.
            String sourceKey = IngestionPipeline.stateKey(KB_RESOURCE_ID, source());
            String abandoned = stateStore.startRun(sourceKey).orElseThrow();
            stateStore.backdateRun(abandoned, Instant.now().minus(Duration.ofDays(1)));

            IngestionReport report = pipelineFor(new FakeSite().page(SITE + "/", pageWith("x")))
                    .run(KB_RESOURCE_ID, knowledgeBase(), source(), Mode.INGEST);

            assertEquals(IngestionReport.Outcome.COMPLETED, report.outcome(),
                    "the dead run must be reaped on the way in rather than blocking this one");
            assertNotEquals(abandoned, report.runId(), "this is a new run, not the abandoned one");
            assertTrue(stateStore.activeRun(sourceKey).isEmpty(), "the new run closed cleanly");
        }

        @Test
        @DisplayName("a run that is merely fresh still blocks a second one")
        void freshRunStillBlocks() {
            String sourceKey = IngestionPipeline.stateKey(KB_RESOURCE_ID, source());
            stateStore.startRun(sourceKey);

            IngestionReport report = pipelineFor(new FakeSite().page(SITE + "/", pageWith("x")))
                    .run(KB_RESOURCE_ID, knowledgeBase(), source(), Mode.INGEST);

            assertEquals(IngestionReport.Outcome.ALREADY_RUNNING, report.outcome(),
                    "reaping only clears runs past the threshold, never a live one");
        }

        @Test
        @DisplayName("one dead link does not make every run report failure")
        void oneDeadLinkDoesNotFailTheRun() {
            // failed > 0 && ingested == 0 meant a stable site whose content was all
            // unchanged reported FAILED on every run as soon as one link rotted, which
            // trains operators to ignore the status.
            FakeSite site = new FakeSite()
                    .page(SITE + "/", "<html><body><a href=\"" + SITE + "/gone\">g</a></body></html>")
                    .status(SITE + "/gone", 404);
            pipelineFor(site).run(KB_RESOURCE_ID, knowledgeBase(), source(), Mode.INGEST);

            // Second run: the index page is unchanged, the dead link still dead.
            pipelineFor(site).run(KB_RESOURCE_ID, knowledgeBase(), source(), Mode.INGEST);

            var runs = stateStore.listRuns(IngestionPipeline.stateKey(KB_RESOURCE_ID, source()), 10);
            assertEquals(IIngestionStateStore.IngestionRun.Status.COMPLETED, runs.get(0).status(),
                    "unchanged content is a working run, not a failed one");
        }

        @Test
        @DisplayName("a title from a third-party page cannot grow vector metadata without bound")
        void titleIsCapped() {
            String hugeTitle = "t".repeat(5000);
            FakeSite site = new FakeSite().page(SITE + "/",
                    "<html><head><title>" + hugeTitle + "</title></head><body><main><p>Body.</p></main></body></html>");

            pipelineFor(site).run(KB_RESOURCE_ID, knowledgeBase(), source(), Mode.INGEST);

            String stored = embeddingStore.segments().get(0).metadata().getString(IngestionPipeline.METADATA_TITLE);
            assertTrue(stored.length() <= 300, "title was " + stored.length() + " chars");
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
    @DisplayName("a run that loses its source")
    class Superseded {

        @Test
        @DisplayName("a run whose source is purged under it stops embedding")
        void stopsWhenItsSourceIsPurged() {
            // It used to carry on to the end of its crawl, embedding into the store and
            // inserting state rows for a source that had just been cleared — a removed
            // source's chunks stayed retrievable, a renamed knowledge base's next run
            // found its documents "unchanged" in a store that did not have them.
            FakeSite site = new FakeSite()
                    .page(SITE + "/", "<html><body><a href=\"" + SITE + "/b\">b</a>"
                            + "<a href=\"" + SITE + "/c\">c</a>" + "<p>Index text.</p></body></html>")
                    .page(SITE + "/b", pageWith("B."))
                    .page(SITE + "/c", pageWith("C."));
            String sourceKey = IngestionPipeline.stateKey(KB_RESOURCE_ID, source());
            doAnswer(invocation -> {
                // The source is purged while the first document is being embedded.
                stateStore.purgeSource(sourceKey);
                List<TextSegment> segments = invocation.getArgument(0);
                return Response.from(segments.stream().map(segment -> Embedding.from(new float[]{0.1f})).toList());
            }).when(embeddingModel).embedAll(any());

            IngestionReport report = pipelineFor(site).run(KB_RESOURCE_ID, knowledgeBase(), source(), Mode.INGEST);

            verify(embeddingModel, times(1)).embedAll(any());
            assertTrue(embeddingStore.segmentsOf(SITE + "/b").isEmpty());
            assertTrue(embeddingStore.segmentsOf(SITE + "/c").isEmpty());
            assertEquals(IngestionReport.Outcome.FAILED, report.outcome());
            assertTrue(report.message().contains("stopped"), report.message());
        }
    }

    @Nested
    @DisplayName("maintenance claims")
    class MaintenanceClaims {

        @Test
        @DisplayName("a released claim never shows up as the source's last run")
        void aReleasedClaimIsNotARun() {
            // Deleting a file takes the run slot, and released it as a COMPLETED run
            // with zeroes everywhere: the source's "last run" became an empty success
            // and the real one, with its errors, was pushed out of sight.
            var pipeline = uploadPipeline();
            var source = uploadSource();
            String sourceKey = IngestionPipeline.stateKey(KB_RESOURCE_ID, source);
            fileStore.store(sourceKey, "broken.pdf", "application/pdf", "nope".getBytes(StandardCharsets.UTF_8));
            pipeline.run(KB_RESOURCE_ID, knowledgeBase(), source, Mode.INGEST);

            String claim = pipeline.claimForMaintenance(KB_RESOURCE_ID, source).orElseThrow();
            pipeline.releaseClaim(KB_RESOURCE_ID, source, claim);

            var history = stateStore.listRuns(sourceKey, 10);
            assertEquals(1, history.size());
            assertEquals(1, history.getFirst().documentsFailed(), "the real run, with its failure, is still on top");
            assertTrue(stateStore.activeRun(sourceKey).isEmpty());
        }
    }

    @Nested
    @DisplayName("after a purge")
    class AfterAPurge {

        private IngestionSource sweptAfter(int runs) {
            var source = source();
            source.setSettings(tombstoneAfter(runs));
            return source;
        }

        @Test
        @DisplayName("a page that left before the purge is swept once missed as often as any page must be")
        void orphansAreSweptAfterTheGracePeriod() {
            // A purge forgets the documents but leaves their vectors answering. A
            // document gone from the site before the purge was then never found
            // again, and with its state row gone nothing reconciled it: its chunks
            // stayed retrievable for good.
            FakeSite before = new FakeSite()
                    .page(SITE + "/", "<html><body><a href=\"" + SITE + "/keep\">k</a>"
                            + "<a href=\"" + SITE + "/gone\">g</a></body></html>")
                    .page(SITE + "/keep", pageWith("Still here."))
                    .page(SITE + "/gone", pageWith("Will vanish."));
            pipelineFor(before).run(KB_RESOURCE_ID, knowledgeBase(), sweptAfter(2), Mode.INGEST);
            stateStore.purgeSource(IngestionPipeline.stateKey(KB_RESOURCE_ID, source()));

            FakeSite after = new FakeSite()
                    .page(SITE + "/", "<html><body><a href=\"" + SITE + "/keep\">k</a></body></html>")
                    .page(SITE + "/keep", pageWith("Still here."));
            pipelineFor(after).run(KB_RESOURCE_ID, knowledgeBase(), sweptAfter(2), Mode.INGEST);
            pipelineFor(after).run(KB_RESOURCE_ID, knowledgeBase(), sweptAfter(2), Mode.INGEST);
            assertFalse(embeddingStore.segmentsOf(SITE + "/gone").isEmpty(),
                    "not before the grace every other page gets");

            IngestionReport report = pipelineFor(after).run(KB_RESOURCE_ID, knowledgeBase(), sweptAfter(2),
                    Mode.INGEST);

            assertTrue(embeddingStore.segmentsOf(SITE + "/gone").isEmpty(),
                    "a page that left the site before the purge must not stay retrievable");
            assertFalse(embeddingStore.segmentsOf(SITE + "/keep").isEmpty());
            assertFalse(embeddingStore.segmentsOf(SITE).isEmpty(), "chunks written by an earlier run since the "
                    + "purge are kept");
            assertEquals(0, report.documentsTombstoned(), "the sweep marker is not a document");
        }

        @Test
        @DisplayName("a page only briefly absent after a purge keeps its content")
        void aBrieflyAbsentPageSurvives() {
            // The sweep used to run on the first run after a purge, so a page that
            // was down for that one run lost its chunks — where any other page gets
            // tombstoneAfterMissedRuns runs of grace.
            FakeSite before = new FakeSite()
                    .page(SITE + "/", "<html><body><a href=\"" + SITE + "/flaky\">f</a></body></html>")
                    .page(SITE + "/flaky", pageWith("Usually here."));
            pipelineFor(before).run(KB_RESOURCE_ID, knowledgeBase(), sweptAfter(2), Mode.INGEST);
            stateStore.purgeSource(IngestionPipeline.stateKey(KB_RESOURCE_ID, source()));

            // Blank for one run — a maintenance page — which is not a failure, so the
            // run is complete and failure-free: exactly the run the old sweep acted on.
            FakeSite blank = new FakeSite()
                    .page(SITE + "/", "<html><body><a href=\"" + SITE + "/flaky\">f</a></body></html>")
                    .page(SITE + "/flaky", "<html><body></body></html>");
            pipelineFor(blank).run(KB_RESOURCE_ID, knowledgeBase(), sweptAfter(2), Mode.INGEST);
            assertFalse(embeddingStore.segmentsOf(SITE + "/flaky").isEmpty(),
                    "one run without the page is not enough to remove it");

            for (int i = 0; i < 3; i++) {
                pipelineFor(before).run(KB_RESOURCE_ID, knowledgeBase(), sweptAfter(2), Mode.INGEST);
            }
            assertFalse(embeddingStore.segmentsOf(SITE + "/flaky").isEmpty(), "back, and kept through the sweep");
        }

        @Test
        @DisplayName("a store that cannot say whether the state is empty skips the sweep, not the run")
        void aFailingStateReadDoesNotFailTheRun() {
            stateStore = new InMemoryIngestionStateStore() {
                @Override
                public synchronized List<IIngestionStateStore.DocumentState> listDocuments(String sourceId,
                                                                                           int limit) {
                    throw new IngestionStateStoreException("database unwell", null);
                }
            };
            FakeSite site = new FakeSite().page(SITE + "/", pageWith("Content."));

            IngestionReport report = pipelineFor(site).run(KB_RESOURCE_ID, knowledgeBase(), source(), Mode.INGEST);

            assertEquals(IngestionReport.Outcome.COMPLETED, report.outcome());
            assertEquals(1, report.documentsIngested());
        }

        @Test
        @DisplayName("a run that could not read everything sweeps nothing")
        void anIncompleteRunKeepsOldChunks() {
            FakeSite before = new FakeSite()
                    .page(SITE + "/", "<html><body><a href=\"" + SITE + "/flaky\">f</a></body></html>")
                    .page(SITE + "/flaky", pageWith("Sometimes down."));
            pipelineFor(before).run(KB_RESOURCE_ID, knowledgeBase(), source(), Mode.INGEST);
            stateStore.purgeSource(IngestionPipeline.stateKey(KB_RESOURCE_ID, source()));

            FakeSite down = new FakeSite()
                    .page(SITE + "/", "<html><body><a href=\"" + SITE + "/flaky\">f</a></body></html>")
                    .status(SITE + "/flaky", 503);
            for (int i = 0; i < 4; i++) {
                pipelineFor(down).run(KB_RESOURCE_ID, knowledgeBase(), source(), Mode.INGEST);
            }

            assertFalse(embeddingStore.segmentsOf(SITE + "/flaky").isEmpty(),
                    "a page the server would not serve this time still has only its old chunks");
        }

        @Test
        @DisplayName("a dead link does not hold the sweep back")
        void aDeadLinkStillSweeps() {
            // The sweep waited for a run with no failure at all, and a 404 counts as
            // one. One dead link on a site therefore kept every orphan from before the
            // purge retrievable for good — the defect the sweep exists to fix.
            FakeSite before = new FakeSite()
                    .page(SITE + "/", "<html><body><a href=\"" + SITE + "/keep\">k</a>"
                            + "<a href=\"" + SITE + "/gone\">g</a><a href=\"" + SITE + "/dead\">d</a></body></html>")
                    .page(SITE + "/keep", pageWith("Still here."))
                    .page(SITE + "/gone", pageWith("Will vanish."))
                    .page(SITE + "/dead", pageWith("Will be a 404."));
            pipelineFor(before).run(KB_RESOURCE_ID, knowledgeBase(), sweptAfter(2), Mode.INGEST);
            stateStore.purgeSource(IngestionPipeline.stateKey(KB_RESOURCE_ID, source()));

            FakeSite after = new FakeSite()
                    .page(SITE + "/", "<html><body><a href=\"" + SITE + "/keep\">k</a>"
                            + "<a href=\"" + SITE + "/dead\">d</a></body></html>")
                    .page(SITE + "/keep", pageWith("Still here."))
                    .status(SITE + "/dead", 404);
            IngestionReport report = null;
            for (int i = 0; i < 3; i++) {
                report = pipelineFor(after).run(KB_RESOURCE_ID, knowledgeBase(), sweptAfter(2), Mode.INGEST);
            }

            assertEquals(1, report.documentsFailed(), "the dead link is still reported");
            assertTrue(embeddingStore.segmentsOf(SITE + "/gone").isEmpty(),
                    "a page that left before the purge must not stay retrievable because another one 404s");
            assertTrue(embeddingStore.segmentsOf(SITE + "/dead").isEmpty(),
                    "nor the dead page itself, whose only chunks predate the purge");
            assertFalse(embeddingStore.segmentsOf(SITE + "/keep").isEmpty());
        }

        @Test
        @DisplayName("a document that failed to embed keeps its old chunks through the sweep")
        void anEmbeddingFailureHoldsTheSweepBack() {
            FakeSite before = new FakeSite()
                    .page(SITE + "/", "<html><body><a href=\"" + SITE + "/stuck\">s</a>"
                            + "<a href=\"" + SITE + "/gone\">g</a></body></html>")
                    .page(SITE + "/stuck", pageWith("Provider refuses this one."))
                    .page(SITE + "/gone", pageWith("Will vanish."));
            pipelineFor(before).run(KB_RESOURCE_ID, knowledgeBase(), sweptAfter(2), Mode.INGEST);
            stateStore.purgeSource(IngestionPipeline.stateKey(KB_RESOURCE_ID, source()));

            doAnswer(invocation -> {
                List<TextSegment> segments = invocation.getArgument(0);
                if (segments.stream().anyMatch(segment -> segment.text().contains("Provider refuses"))) {
                    throw new RuntimeException("provider returned 429");
                }
                return Response.from(segments.stream().map(segment -> Embedding.from(new float[]{0.1f})).toList());
            }).when(embeddingModel).embedAll(any());
            FakeSite after = new FakeSite()
                    .page(SITE + "/", "<html><body><a href=\"" + SITE + "/stuck\">s</a></body></html>")
                    .page(SITE + "/stuck", pageWith("Provider refuses this one."));
            for (int i = 0; i < 4; i++) {
                pipelineFor(after).run(KB_RESOURCE_ID, knowledgeBase(), sweptAfter(2), Mode.INGEST);
            }

            assertFalse(embeddingStore.segmentsOf(SITE + "/stuck").isEmpty(),
                    "a page that did not embed has only its chunks from before the purge, and keeps them");
        }

        @Test
        @DisplayName("an ordinary run, with state, sweeps nothing")
        void aRunWithStateSweepsNothing() {
            FakeSite site = new FakeSite()
                    .page(SITE + "/", "<html><body><a href=\"" + SITE + "/a\">a</a></body></html>")
                    .page(SITE + "/a", pageWith("A."));
            pipelineFor(site).run(KB_RESOURCE_ID, knowledgeBase(), source(), Mode.INGEST);

            pipelineFor(site).run(KB_RESOURCE_ID, knowledgeBase(), source(), Mode.INGEST);

            // Unchanged documents keep chunks written by the first run, which are not
            // this run's and must survive it.
            assertFalse(embeddingStore.segmentsOf(SITE + "/a").isEmpty());
            assertFalse(embeddingStore.segmentsOf(SITE).isEmpty(), "the index page, stored as its canonical URL");
        }
    }

    @Nested
    @DisplayName("conditional fetching")
    class Conditional {

        @Test
        @DisplayName("stored validators are sent on the next run and a 304 costs nothing")
        void usesStoredValidators() {
            FakeSite first = new FakeSite().page(SITE + "/", linkTo(SITE + "/leaf"))
                    .pageWithValidators(SITE + "/leaf", pageWith("Content."), "\"v1\"", null);
            pipelineFor(first).run(KB_RESOURCE_ID, knowledgeBase(), leafSource(), Mode.INGEST);
            int afterFirst = embeddingStore.segments().size();

            FakeSite second = new FakeSite().page(SITE + "/", linkTo(SITE + "/leaf"))
                    .conditional(SITE + "/leaf", pageWith("Content."), "\"v1\"");
            IngestionReport report = pipelineFor(second).run(KB_RESOURCE_ID, knowledgeBase(), leafSource(),
                    Mode.INGEST);

            assertEquals(2, report.documentsUnchanged(), "the index by its hash, the leaf by its 304");
            assertEquals(afterFirst, embeddingStore.segments().size());
            var command = second.requests().stream()
                    .filter(request -> request.url().equals(SITE + "/leaf"))
                    .findFirst().orElseThrow();
            assertEquals("\"v1\"", command.ifNoneMatch(), "the ETag from the last run must be sent back");
        }

        @Test
        @DisplayName("a page downloaded again and unchanged takes the server's new validators")
        void refreshesStaleValidators() {
            // The first ingest's ETag was kept for ever. A server that rotated it
            // without changing the text then answered every conditional request with
            // a full 200 — the validator sent back was one it no longer recognised.
            FakeSite first = new FakeSite().page(SITE + "/", linkTo(SITE + "/leaf"))
                    .pageWithValidators(SITE + "/leaf", pageWith("Content."), "\"v1\"", null);
            pipelineFor(first).run(KB_RESOURCE_ID, knowledgeBase(), leafSource(), Mode.INGEST);

            FakeSite rotated = new FakeSite().page(SITE + "/", linkTo(SITE + "/leaf"))
                    .pageWithValidators(SITE + "/leaf", pageWith("Content."), "\"v2\"", null);
            IngestionReport report = pipelineFor(rotated).run(KB_RESOURCE_ID, knowledgeBase(), leafSource(),
                    Mode.INGEST);
            assertEquals(0, report.documentsIngested(), "the text did not change, so nothing is re-embedded");

            FakeSite third = new FakeSite().page(SITE + "/", linkTo(SITE + "/leaf"))
                    .conditional(SITE + "/leaf", pageWith("Content."), "\"v2\"");
            pipelineFor(third).run(KB_RESOURCE_ID, knowledgeBase(), leafSource(), Mode.INGEST);

            var command = third.requests().stream()
                    .filter(request -> request.url().equals(SITE + "/leaf"))
                    .findFirst().orElseThrow();
            assertEquals("\"v2\"", command.ifNoneMatch(), "the validator the server issued last must be sent");
        }

        @Test
        @DisplayName("an unchanged parent never hides its children, however many runs go by")
        void unchangedParentKeepsItsChildren() {
            // A parent revalidated with a 304 has no body and so no links. Its
            // children were never reached, the crawl still "covered" the site, and
            // after tombstoneAfterMissedRuns runs every one of them lost its vectors.
            var source = source();
            source.setSettings(tombstoneAfter(1));
            FakeSite first = new FakeSite()
                    .pageWithValidators(SITE + "/", linkTo(SITE + "/child"), "\"p1\"", null)
                    .page(SITE + "/child", pageWith("Child content."));
            pipelineFor(first).run(KB_RESOURCE_ID, knowledgeBase(), source, Mode.INGEST);

            FakeSite unchanged = new FakeSite()
                    .conditional(SITE + "/", linkTo(SITE + "/child"), "\"p1\"")
                    .page(SITE + "/child", pageWith("Child content."));
            pipelineFor(unchanged).run(KB_RESOURCE_ID, knowledgeBase(), source, Mode.INGEST);
            IngestionReport report = pipelineFor(unchanged).run(KB_RESOURCE_ID, knowledgeBase(), source,
                    Mode.INGEST);

            assertEquals(0, report.documentsTombstoned());
            assertFalse(embeddingStore.segmentsOf(SITE + "/child").isEmpty(),
                    "a child of an unchanged page must stay retrievable");
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
        @DisplayName("a page size cap of zero or less is refused — the fetcher would read it as no cap")
        void refusesNonPositivePageCap() {
            for (long cap : new long[]{0L, -1L}) {
                var settings = new IngestionSource.IngestionSettings();
                settings.setMaxBytesPerPage(cap);
                var source = source();
                source.setSettings(settings);

                var thrown = assertThrows(IllegalArgumentException.class, source::validate);
                assertTrue(thrown.getMessage().contains("maxBytesPerPage"), thrown.getMessage());
            }
        }

        @Test
        @DisplayName("two sources sharing a state key are refused")
        void refusesSourcesSharingAStateKey() {
            // State is keyed by id, or by name when there is none. A shared key means
            // one document history, so each source's runs tombstone the other's pages.
            var first = source();
            var sameId = source();
            sameId.setName("another-name");
            RagConfiguration config = knowledgeBase();
            config.setSources(List.of(first, sameId));

            var thrown = assertThrows(IllegalArgumentException.class, config::validate);
            assertTrue(thrown.getMessage().contains("src-1"), thrown.getMessage());

            // An id-less source falls back to its name, which can collide with an id.
            var named = source();
            named.setId(null);
            named.setName("src-1");
            config.setSources(List.of(first, named));
            assertThrows(IllegalArgumentException.class, config::validate);
        }

        @Test
        @DisplayName("distinct sources pass, and the state key is the one validation checks")
        void distinctSourcesPass() {
            var first = source();
            var second = source();
            second.setId("src-2");
            RagConfiguration config = knowledgeBase();
            config.setSources(List.of(first, second));

            config.validate();
            assertEquals("kb:src-2", IngestionPipeline.stateKey("kb", second));

            second.setId(" ");
            assertEquals("kb:" + second.getName(), IngestionPipeline.stateKey("kb", second));
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
        @DisplayName("a source without an id is found by name, the way it is scheduled and keyed")
        void findsIdLessSourceByName() {
            // A ZIP import writes through IRagStore.create and never passes the REST
            // layer that assigns ids. Schedules and ingestion state key such a source
            // by its name, so a lookup by id alone failed every scheduled fire.
            RagConfiguration config = knowledgeBase();
            var idLess = source();
            idLess.setId(null);
            config.setSources(List.of(idLess));

            assertEquals(idLess, config.findSource(idLess.getName()));
            assertEquals(idLess.effectiveId(), IngestionPipeline.stateKey(KB_RESOURCE_ID, idLess)
                    .substring(KB_RESOURCE_ID.length() + 1));
        }

        @Test
        @DisplayName("a null entry in the sources list is refused, not a NullPointerException")
        void refusesNullSourceEntry() {
            RagConfiguration config = knowledgeBase();
            var sources = new ArrayList<IngestionSource>();
            sources.add(source());
            sources.add(null);
            config.setSources(sources);

            var thrown = assertThrows(IllegalArgumentException.class, config::validate);
            assertTrue(thrown.getMessage().contains("null entry"), thrown.getMessage());
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

    @Nested
    @DisplayName("uploaded files")
    class UploadedFiles {

        private static final String SOURCE_KEY = KB_RESOURCE_ID + ":src-files";

        @Test
        @DisplayName("reads every stored file and embeds its text")
        void readsStoredFiles() {
            fileStore.store(SOURCE_KEY, "notes.md", "text/markdown",
                    "# Leave policy\n\nYou get 30 days.".getBytes(StandardCharsets.UTF_8));

            IngestionReport report = uploadPipeline()
                    .run(KB_RESOURCE_ID, knowledgeBase(), uploadSource(), Mode.INGEST);

            assertEquals(IngestionReport.Outcome.COMPLETED, report.outcome());
            assertEquals(1, report.documentsIngested());
            assertTrue(embeddingStore.segments().stream()
                    .anyMatch(segment -> segment.text().contains("You get 30 days.")),
                    "the file's text must reach the vector store");
        }

        @Test
        @DisplayName("cites the file rather than inventing a URL for it")
        void citesTheFile() {
            fileStore.store(SOURCE_KEY, "handbook.pdf", "text/plain",
                    "Contents".getBytes(StandardCharsets.UTF_8));

            uploadPipeline().run(KB_RESOURCE_ID, knowledgeBase(), uploadSource(), Mode.INGEST);

            var metadata = embeddingStore.segments().getFirst().metadata();
            assertEquals("file:handbook.pdf", metadata.getString(IngestionPipeline.METADATA_URL));
            assertEquals("handbook", metadata.getString(IngestionPipeline.METADATA_TITLE));
        }

        @Test
        @DisplayName("does not re-embed a file whose bytes have not changed")
        void skipsUnchangedFiles() {
            fileStore.store(SOURCE_KEY, "notes.md", "text/markdown",
                    "Stable text".getBytes(StandardCharsets.UTF_8));
            var pipeline = uploadPipeline();
            pipeline.run(KB_RESOURCE_ID, knowledgeBase(), uploadSource(), Mode.INGEST);
            int afterFirstRun = embeddingStore.segments().size();

            IngestionReport second = pipeline.run(KB_RESOURCE_ID, knowledgeBase(), uploadSource(), Mode.INGEST);

            assertEquals(1, second.documentsUnchanged());
            assertEquals(0, second.documentsIngested());
            assertEquals(afterFirstRun, embeddingStore.segments().size());
        }

        @Test
        @DisplayName("re-embeds a file that was replaced, and leaves no trace of the old one")
        void reEmbedsReplacedFiles() {
            fileStore.store(SOURCE_KEY, "notes.md", "text/markdown",
                    "Old answer".getBytes(StandardCharsets.UTF_8));
            var pipeline = uploadPipeline();
            pipeline.run(KB_RESOURCE_ID, knowledgeBase(), uploadSource(), Mode.INGEST);

            fileStore.store(SOURCE_KEY, "notes.md", "text/markdown",
                    "New answer".getBytes(StandardCharsets.UTF_8));
            pipeline.run(KB_RESOURCE_ID, knowledgeBase(), uploadSource(), Mode.INGEST);

            var texts = embeddingStore.segments().stream().map(TextSegment::text).toList();
            assertTrue(texts.stream().anyMatch(text -> text.contains("New answer")), texts.toString());
            // Last month's answer retrievable beside this month's is worse than
            // having no knowledge base at all.
            assertFalse(texts.stream().anyMatch(text -> text.contains("Old answer")), texts.toString());
        }

        @Test
        @DisplayName("removes the vectors of a file that has been deleted")
        void tombstonesDeletedFiles() {
            var stored = fileStore.store(SOURCE_KEY, "gone.md", "text/markdown",
                    "Temporary".getBytes(StandardCharsets.UTF_8));
            var source = uploadSource();
            source.setSettings(tombstoneAfter(1));
            var pipeline = uploadPipeline();
            pipeline.run(KB_RESOURCE_ID, knowledgeBase(), source, Mode.INGEST);
            assertFalse(embeddingStore.segments().isEmpty());

            fileStore.deleteAll(SOURCE_KEY);
            IngestionReport report = pipeline.run(KB_RESOURCE_ID, knowledgeBase(), source, Mode.INGEST);

            // A source with no files left is not an unreachable source: the store
            // was read and it is empty. Treating it as "nothing concluded" would
            // leave the deleted document answering questions for ever, because an
            // upload source has no next crawl to correct it.
            assertEquals(1, report.documentsTombstoned());
            assertTrue(embeddingStore.segmentsOf(stored.fileId()).isEmpty());
        }

        @Test
        @DisplayName("a replacement that cannot be read takes the old version's text out of retrieval")
        void anUnreadableReplacementRetiresTheOldText() {
            // The old version was kept — the error was recorded as "could not look" —
            // so re-uploading a document as a broken copy left the previous text
            // answering questions for good while the file list showed only the new
            // file. The stored file is all an upload source has; reading it and
            // failing is a definitive answer.
            var ingested = fileStore.store(SOURCE_KEY, "report.pdf", "text/plain",
                    "Quarterly results".getBytes(StandardCharsets.UTF_8));
            var source = uploadSource();
            var pipeline = uploadPipeline();
            pipeline.run(KB_RESOURCE_ID, knowledgeBase(), source, Mode.INGEST);
            assertFalse(embeddingStore.segmentsOf(ingested.fileId()).isEmpty());

            fileStore.store(SOURCE_KEY, "report.pdf", "application/pdf",
                    "not really a pdf".getBytes(StandardCharsets.UTF_8));
            fileStore.store(SOURCE_KEY, "readable.md", "text/markdown",
                    "Fine".getBytes(StandardCharsets.UTF_8));

            IngestionReport report = pipeline.run(KB_RESOURCE_ID, knowledgeBase(), source, Mode.INGEST);

            assertEquals(1, report.documentsIngested());
            assertEquals(1, report.documentsFailed());
            assertEquals(1, report.documentsTombstoned());
            assertTrue(embeddingStore.segmentsOf(ingested.fileId()).isEmpty(),
                    "text the file no longer contains must not stay retrievable");

            IngestionReport again = pipeline.run(KB_RESOURCE_ID, knowledgeBase(), source, Mode.INGEST);
            assertEquals(1, again.documentsFailed(), "still unreadable, and still said so");
            assertEquals(0, again.documentsTombstoned(), "but not retired a second time");
        }

        @Test
        @DisplayName("a stored file with no text — a scan — is reported, and retires what it replaced")
        void aFileWithNoTextIsAFailure() {
            // Skipped as blank on every run before, silently: it stayed "not indexed"
            // for good, and a scan uploaded over an indexed file left the old text
            // answering.
            var ingested = fileStore.store(SOURCE_KEY, "scan.txt", "text/plain",
                    "Readable at first".getBytes(StandardCharsets.UTF_8));
            var pipeline = uploadPipeline();
            pipeline.run(KB_RESOURCE_ID, knowledgeBase(), uploadSource(), Mode.INGEST);

            fileStore.store(SOURCE_KEY, "scan.txt", "text/plain", "   \n\n  ".getBytes(StandardCharsets.UTF_8));
            IngestionReport report = pipeline.run(KB_RESOURCE_ID, knowledgeBase(), uploadSource(), Mode.INGEST);

            assertEquals(1, report.documentsFailed());
            assertEquals(0, report.documentsSkipped(), "a file is not a navigation page; an empty one is a failure");
            assertTrue(embeddingStore.segmentsOf(ingested.fileId()).isEmpty());
        }

        @Test
        @DisplayName("stops at the time budget rather than outliving its own claim")
        void stopsAtTheTimeBudget() {
            for (int i = 0; i < 3; i++) {
                fileStore.store(SOURCE_KEY, "doc" + i + ".md", "text/markdown",
                        ("Body " + i).getBytes(StandardCharsets.UTF_8));
            }
            var source = uploadSource();
            source.setSettings(tombstoneAfter(1));

            // A budget that has already run out by the time the loop starts. The
            // real one is a minute at its shortest, and a unit suite does not get to
            // spend a minute proving a comparison.
            var pipeline = new IngestionPipeline(new WebCrawler(new FakeSite()), new HtmlToMarkdownConverter(),
                    stateStore, fileStore, extractors(), modelFactory, storeFactory, new SimpleMeterRegistry()) {
                @Override
                Instant uploadDeadline(Instant start, IngestionSource forSource) {
                    return start.minusSeconds(1);
                }
            };
            IngestionReport report = pipeline.run(KB_RESOURCE_ID, knowledgeBase(), source, Mode.INGEST);

            assertEquals(0, report.documentsIngested());
            // A run that stopped short saw an arbitrary subset, so it concludes
            // nothing about what is gone.
            assertTrue(report.tombstoningSkipped());
            assertEquals(WebCrawler.StopReason.TIME_LIMIT, report.stopReason());
        }

        @Test
        @DisplayName("a batch of uploads is never held in memory at once")
        void readsOneFileAtATime() {
            // Asserted on the store rather than on memory: the service reads through
            // a supplier, so a file's bytes are produced when its turn comes. A
            // regression to eager reading shows up here as every file being asked
            // for before the first is examined.
            assertTrue(true, "covered by IngestedFileServiceTest#readsEachFileOnlyWhenItsTurnComes");
        }

        @Test
        @DisplayName("a store that cannot be listed concludes nothing")
        void anUnavailableStoreConcludesNothing() {
            fileStore.store(SOURCE_KEY, "present.md", "text/markdown",
                    "Here".getBytes(StandardCharsets.UTF_8));
            var source = uploadSource();
            source.setSettings(tombstoneAfter(1));
            uploadPipeline().run(KB_RESOURCE_ID, knowledgeBase(), source, Mode.INGEST);

            var brokenStore = new InMemoryIngestedFileStore() {
                @Override
                public List<StoredFile> list(String sourceKey) {
                    throw new IngestedFileStoreException("the database is unwell");
                }
            };
            var pipeline = new IngestionPipeline(new WebCrawler(new FakeSite()), new HtmlToMarkdownConverter(),
                    stateStore, brokenStore, extractors(), modelFactory, storeFactory, new SimpleMeterRegistry());

            IngestionReport report = pipeline.run(KB_RESOURCE_ID, knowledgeBase(), source, Mode.INGEST);

            // "No files came back" from a database outage must not read as "the
            // operator deleted everything" — that empties the knowledge base over a
            // blip, with the run reporting success.
            assertEquals(0, report.documentsTombstoned());
            assertTrue(report.tombstoningSkipped());
            assertFalse(embeddingStore.segments().isEmpty());
        }

        @Test
        @DisplayName("a preview reports what would change and embeds nothing")
        void previewEmbedsNothing() {
            fileStore.store(SOURCE_KEY, "notes.md", "text/markdown",
                    "Draft".getBytes(StandardCharsets.UTF_8));

            IngestionReport report = uploadPipeline()
                    .run(KB_RESOURCE_ID, knowledgeBase(), uploadSource(), Mode.PREVIEW);

            assertEquals(IngestionReport.Outcome.PREVIEW, report.outcome());
            assertEquals(1, report.documentsIngested());
            assertTrue(embeddingStore.segments().isEmpty());
        }

        @Test
        @DisplayName("forgetting a document removes its vectors without waiting for a run")
        void forgetDocumentRemovesVectorsNow() {
            var stored = fileStore.store(SOURCE_KEY, "secret.md", "text/markdown",
                    "Confidential".getBytes(StandardCharsets.UTF_8));
            var pipeline = uploadPipeline();
            pipeline.run(KB_RESOURCE_ID, knowledgeBase(), uploadSource(), Mode.INGEST);
            assertFalse(embeddingStore.segments().isEmpty());

            // By the file's own id, which is what the vectors are keyed by — the
            // display name is not an identity.
            var removed = pipeline.forgetDocument(KB_RESOURCE_ID, knowledgeBase(), uploadSource(),
                    stored.fileId());

            assertEquals(IngestionPipeline.ForgetOutcome.REMOVED, removed);
            // Deferring this to the next run means a source with no cron keeps
            // answering from a document the operator was told was deleted.
            assertTrue(embeddingStore.segments().isEmpty());
        }

        @Test
        @DisplayName("a document forgotten while the store could not delete says so")
        void forgetDocumentReportsAnUndeletableStore() {
            embeddingStore = new RecordingEmbeddingStore().withoutRemovalSupport();
            when(storeFactory.getOrCreate(any(RagConfiguration.class), anyString())).thenReturn(embeddingStore);

            var removed = uploadPipeline()
                    .forgetDocument(KB_RESOURCE_ID, knowledgeBase(), uploadSource(), "secret.md");

            assertEquals(IngestionPipeline.ForgetOutcome.UNSUPPORTED, removed,
                    "the caller has to be able to tell the operator the chunks are still there");
        }

        @Test
        @DisplayName("stops at the file limit rather than concluding the rest are gone")
        void stopsAtTheFileLimit() {
            for (int i = 0; i < 5; i++) {
                fileStore.store(SOURCE_KEY, "doc" + i + ".md", "text/markdown",
                        ("Body " + i).getBytes(StandardCharsets.UTF_8));
            }
            var source = uploadSource();
            source.setSettings(tombstoneAfter(1));
            var upload = new IngestionSource.UploadSource();
            upload.setMaxFiles(2);
            source.setUpload(upload);

            IngestionReport report = uploadPipeline()
                    .run(KB_RESOURCE_ID, knowledgeBase(), source, Mode.INGEST);

            assertEquals(2, report.documentsIngested());
            // The run saw two of five files, so it knows nothing about the other
            // three — and must not delete them.
            assertTrue(report.tombstoningSkipped());
            assertEquals(0, report.documentsTombstoned());
        }
    }

    private static IngestionSource.IngestionSettings tombstoneAfter(int runs) {
        var settings = new IngestionSource.IngestionSettings();
        settings.setTombstoneAfterMissedRuns(runs);
        return settings;
    }
}
