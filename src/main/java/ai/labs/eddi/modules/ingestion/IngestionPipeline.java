/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion;

import ai.labs.eddi.configs.rag.model.IngestionSource;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.modules.ingestion.IIngestionStateStore.DocumentState;
import ai.labs.eddi.modules.ingestion.IIngestionStateStore.IngestionRun;
import ai.labs.eddi.modules.ingestion.crawl.CrawlRequest;
import ai.labs.eddi.modules.ingestion.crawl.CrawlSink;
import ai.labs.eddi.modules.ingestion.crawl.WebCrawler;
import ai.labs.eddi.modules.llm.impl.EmbeddingModelFactory;
import ai.labs.eddi.modules.llm.impl.EmbeddingStoreFactory;
import ai.labs.eddi.utils.LogSanitizer;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.exception.UnsupportedFeatureException;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

/**
 * Turns a knowledge base's {@link IngestionSource} into embedded documents.
 *
 * <p>
 * Crawl → convert → compare → embed, one document at a time, then reconcile
 * what has disappeared. The comparison and the reconciliation are the parts
 * that make this a knowledge base rather than a pile of vectors, and they are
 * where the draft this replaces went wrong.
 *
 * <h2>Three rules</h2>
 * <ol>
 * <li><b>The vector store is keyed by the knowledge base.</b> Not by the
 * source. The draft used the source's name, so crawled content landed in one
 * table while retrieval read another — ingestion reported success and the agent
 * retrieved nothing.</li>
 * <li><b>Re-ingesting replaces.</b> Chunks for a document are removed before
 * its new ones are added. Appending leaves last month's prices retrievable
 * beside this month's, which is worse than having no knowledge base at
 * all.</li>
 * <li><b>A document is recorded only after its vectors are stored,</b> and only
 * a crawl that covered the whole source may conclude anything is gone.</li>
 * </ol>
 */
@ApplicationScoped
public class IngestionPipeline {

    private static final Logger LOGGER = Logger.getLogger(IngestionPipeline.class);

    /**
     * Metadata key that ties a segment back to its document, used to replace it.
     */
    /** Cap on third-party text copied into vector metadata. */
    private static final int MAX_TITLE_LENGTH = 300;

    /** Added to a run's time budget before it counts as abandoned. */
    private static final Duration STALE_RUN_MARGIN = Duration.ofMinutes(15);

    public static final String METADATA_DOCUMENT_ID = "documentId";
    public static final String METADATA_URL = "url";
    public static final String METADATA_TITLE = "title";
    public static final String METADATA_SOURCE = "sourceName";
    public static final String METADATA_RUN_ID = "runId";

    /**
     * Which source's ingestion owns a chunk — the state key, not the source's name,
     * because a name can be edited while the state cannot.
     *
     * <p>
     * Without it, two sources of one knowledge base that overlap on a URL delete
     * each other's chunks: removal matched the document id alone, so whichever
     * source ran last owned the vectors while the other's state still claimed them,
     * and its next run reported "unchanged" over an empty store.
     */
    public static final String METADATA_SOURCE_KEY = "sourceKey";
    public static final String METADATA_INGESTED_AT = "ingestedAt";

    private final WebCrawler crawler;
    private final HtmlToMarkdownConverter converter;
    private final IIngestionStateStore stateStore;
    private final EmbeddingModelFactory embeddingModelFactory;
    private final EmbeddingStoreFactory embeddingStoreFactory;
    private final MeterRegistry meterRegistry;

    @Inject
    public IngestionPipeline(WebCrawler crawler,
            HtmlToMarkdownConverter converter,
            IIngestionStateStore stateStore,
            EmbeddingModelFactory embeddingModelFactory,
            EmbeddingStoreFactory embeddingStoreFactory,
            MeterRegistry meterRegistry) {
        this.crawler = crawler;
        this.converter = converter;
        this.stateStore = stateStore;
        this.embeddingModelFactory = embeddingModelFactory;
        this.embeddingStoreFactory = embeddingStoreFactory;
        this.meterRegistry = meterRegistry;
    }

    /** What a run is for. */
    public enum Mode {
        /** Crawl, embed, and reconcile. */
        INGEST,
        /**
         * Crawl and report what would change, embedding nothing and recording nothing.
         * Lets an operator see the effect of a scope or exclusion before paying for it.
         */
        PREVIEW
    }

    /**
     * Runs a source. Blocks for the length of the crawl — call it on its own
     * thread.
     *
     * @param ragConfigId
     *            the knowledge base's resource id, used to scope ingestion state
     */
    public IngestionReport run(String ragConfigId, RagConfiguration knowledgeBase, IngestionSource source, Mode mode) {
        return run(ragConfigId, knowledgeBase, source, mode, null);
    }

    /**
     * Claims a run for this source before any work starts, so a caller that hands
     * the work to another thread can answer its own caller truthfully.
     *
     * <p>
     * Without this, two requests arriving together both saw no active run, both
     * answered "started", and only one of the two workers won the claim inside
     * {@link #run} — the other returned {@code ALREADY_RUNNING} into a log line
     * nobody reads, having crawled nothing.
     *
     * @return the run id to pass back into {@code run}, or empty when a run is
     *         already in flight
     */
    public Optional<String> reserveRun(String ragConfigId, IngestionSource source) {
        // A run whose process died is still marked RUNNING and would block this
        // source indefinitely; nothing else calls this.
        String sourceKey = stateKey(ragConfigId, source);
        stateStore.reapStaleRuns(sourceKey, Instant.now().minus(staleRunThreshold(source)));
        return stateStore.startRun(sourceKey);
    }

    /**
     * Releases a reservation that will never be worked on — the worker thread could
     * not be started. Leaving it claimed would block the source until it is reaped.
     */
    public void abandonReservation(String ragConfigId, IngestionSource source, String runId, String reason) {
        finish(Mode.INGEST, runId, stateKey(ragConfigId, source),
                IngestionReport.failed(runId, source.getId(), reason), IngestionRun.Status.FAILED);
    }

    /**
     * @param reservedRunId
     *            a run already claimed by {@link #reserveRun}, or null to claim one
     *            here. Passing one claimed elsewhere is what stops the claim being
     *            taken twice.
     */
    public IngestionReport run(String ragConfigId, RagConfiguration knowledgeBase, IngestionSource source, Mode mode,
                               String reservedRunId) {

        String sourceKey = stateKey(ragConfigId, source);
        IngestionReport early;
        try {
            source.validate();
            String name = knowledgeBase.getName();
            if (name == null || name.isBlank()) {
                early = IngestionReport.failed(reservedRunId, source.getId(),
                        "The knowledge base has no name, and its name is what the vector store is keyed by");
            } else if (!source.isEnabled() && mode == Mode.INGEST) {
                early = IngestionReport.skipped(source.getId(), "Source is disabled");
            } else {
                early = null;
            }
        } catch (RuntimeException e) {
            // Validation runs after the reservation exists, so an invalid source must
            // not leave the run claimed.
            releaseIfReserved(mode, reservedRunId, sourceKey, source, describe(e));
            throw e;
        }
        if (early != null) {
            releaseIfReserved(mode, reservedRunId, sourceKey, source, early.message());
            return early;
        }

        String knowledgeBaseId = knowledgeBase.getName();
        String runId;
        if (mode == Mode.INGEST) {
            if (reservedRunId != null) {
                runId = reservedRunId;
            } else {
                stateStore.reapStaleRuns(sourceKey, Instant.now().minus(staleRunThreshold(source)));
                var claimed = stateStore.startRun(sourceKey);
                if (claimed.isEmpty()) {
                    // Not an error: an operator clicking "run now" while a scheduled run
                    // is in flight should be told, not start a second crawl into one
                    // store.
                    return IngestionReport.alreadyRunning(source.getId());
                }
                runId = claimed.get();
            }
        } else {
            runId = "preview";
        }

        Instant startedAt = Instant.now();
        Collector collector = new Collector(knowledgeBase, knowledgeBaseId, source, sourceKey, runId, mode);

        // Everything after the run is claimed is guarded, and by Throwable rather
        // than Exception. A claimed run that is never finished blocks its source for
        // good: manual runs answer 409 and every scheduled fire fails until the row
        // is reaped. The ways out are not all RuntimeExceptions — a state-store
        // failure, an OutOfMemoryError, or a StackOverflowError from a pathological
        // page are each enough.
        try {
            WebCrawler.CrawlSummary summary = crawler.crawl(toCrawlRequest(source), collector);

            collector.tombstoned = reconcileDeletions(source, sourceKey, runId, mode, summary, collector);

            IngestionReport report = collector.toReport(summary, null, startedAt);
            finish(mode, runId, sourceKey, report, statusFor(collector));
            return report;
        } catch (Throwable t) {
            LOGGER.errorf(t, "Ingestion failed for source '%s'", LogSanitizer.sanitize(source.getName()));
            try {
                IngestionReport report = collector.toReport(null, describe(t), startedAt);
                finish(mode, runId, sourceKey, report, IngestionRun.Status.FAILED);
                if (t instanceof Error error) {
                    // Recorded, then rethrown: an Error says the JVM is in trouble and
                    // swallowing it would hide that.
                    throw error;
                }
                return report;
            } catch (RuntimeException closingFailure) {
                LOGGER.errorf(closingFailure, "Could not close the failed ingestion run for source '%s' — it will "
                        + "be reaped", LogSanitizer.sanitize(source.getName()));
                throw closingFailure;
            }
        }
    }

    /**
     * A run only counts as failed when nothing at all came through. A stable site
     * with one dead link produced failures on every run otherwise, which trains
     * operators to ignore the status.
     */
    private static IngestionRun.Status statusFor(Collector collector) {
        boolean nothingUsable = collector.ingested + collector.unchanged == 0;
        return collector.failed > 0 && nothingUsable ? IngestionRun.Status.FAILED : IngestionRun.Status.COMPLETED;
    }

    /**
     * Removes the vectors of documents that have gone from the source — but only
     * when the crawl actually covered it.
     *
     * <p>
     * A crawl that hit its page cap, ran out of time, or was cancelled saw an
     * arbitrary subset. Treating that subset as the whole truth is how a knowledge
     * base empties itself because a site was slow.
     */
    private int reconcileDeletions(IngestionSource source, String sourceKey, String runId, Mode mode,
                                   WebCrawler.CrawlSummary summary, Collector collector) {

        if (mode != Mode.INGEST) {
            return 0;
        }
        if (!summary.coveredWholeSource()) {
            collector.tombstoningSkipped = true;
            LOGGER.infof("Not reconciling deletions for source '%s': the crawl stopped at %s rather than covering "
                    + "the source", LogSanitizer.sanitize(source.getName()), summary.stopReason());
            return 0;
        }
        boolean nothingUsable = collector.ingested + collector.unchanged == 0;
        boolean nothingDefinitive = collector.failed == collector.unreachable;
        if (nothingUsable && nothingDefinitive) {
            // The crawler counts a page it handed over as fetched even when the sink
            // discarded it as blank, so a site behind a JavaScript challenge or a
            // maintenance page answers 200 for everything, "covers the source", and
            // yields nothing. Two such runs would delete the whole corpus.
            //
            // Not simply "no usable document": a site whose pages have genuinely been
            // deleted also yields none, and that is exactly when reconciliation should
            // run. The distinction is whether anything definitive was learned — a 404
            // or a 410 — rather than only failures that hide the content.
            collector.tombstoningSkipped = true;
            LOGGER.infof("Not reconciling deletions for source '%s': the crawl produced no usable document and "
                    + "learned nothing definitive about what is gone", LogSanitizer.sanitize(source.getName()));
            return 0;
        }

        List<DocumentState> gone = stateStore.bumpAndFindMissing(
                sourceKey, runId, source.settings().tombstoneAfterMissedRunsOrDefault());
        if (gone.isEmpty()) {
            return 0;
        }

        // Vectors first, tombstone afterwards, and only for what was actually
        // removed. The other order is durable in the wrong direction: a crash or a
        // store that refuses the delete leaves a document flagged gone with its
        // chunks still retrievable, and a tombstoned document is never reported
        // again — so nothing would ever come back for them.
        EmbeddingStore<TextSegment> store = collector.store();
        List<String> removedIds = new ArrayList<>();
        for (DocumentState document : gone) {
            try {
                store.removeAll(metadataKey(METADATA_DOCUMENT_ID).isEqualTo(document.documentId())
                        .and(metadataKey(METADATA_SOURCE_KEY).isEqualTo(sourceKey)));
                removedIds.add(document.documentId());
            } catch (UnsupportedFeatureException e) {
                // The store cannot delete at all. Tombstone anyway — the state is
                // honest about what the source contains — and report it.
                collector.replaceUnsupported = true;
                removedIds.add(document.documentId());
            } catch (RuntimeException e) {
                LOGGER.warnf(e, "Could not remove vectors for a deleted document of source '%s'; it stays live "
                        + "and the next run tries again", LogSanitizer.sanitize(source.getName()));
            }
        }
        stateStore.markTombstoned(sourceKey, removedIds);
        return removedIds.size();
    }

    private void releaseIfReserved(Mode mode, String reservedRunId, String sourceKey, IngestionSource source,
                                   String reason) {
        if (mode != Mode.INGEST || reservedRunId == null) {
            return;
        }
        finish(mode, reservedRunId, sourceKey, IngestionReport.failed(reservedRunId, source.getId(), reason),
                IngestionRun.Status.FAILED);
    }

    private void finish(Mode mode, String runId, String sourceKey, IngestionReport report,
                        IngestionRun.Status status) {
        if (mode != Mode.INGEST) {
            return;
        }
        stateStore.finishRun(new IngestionRun(runId, sourceKey, status, null, Instant.now(),
                report.documentsSeen(), report.documentsIngested(), report.documentsUnchanged(),
                report.documentsFailed(), report.documentsTombstoned(), report.segmentsStored(),
                report.costUsd(), report.message()));
    }

    /** State is scoped to a source of a knowledge base, not to a source name. */
    static String stateKey(String ragConfigId, IngestionSource source) {
        return ragConfigId + ":" + source.effectiveId();
    }

    private static CrawlRequest toCrawlRequest(IngestionSource source) {
        IngestionSource.WebSource web = source.getWeb();
        IngestionSource.IngestionSettings settings = source.settings();

        var scope = new CrawlRequest.Scope(
                web.isSameSiteOnly(),
                web.isIncludeSubdomains(),
                web.getPathPrefix(),
                web.getMaxDepth() == null ? 3 : web.getMaxDepth(),
                web.getExcludePatterns());

        int maxPages = web.getMaxPages() == null ? 200 : web.getMaxPages();
        var limits = new CrawlRequest.Limits(
                maxPages,
                0, // derived from maxPages
                settings.maxBytesPerPageOrDefault(),
                0, // default total budget
                Duration.ofMinutes(settings.timeBudgetMinutesOrDefault()),
                Duration.ofSeconds(web.getTimeoutSeconds() == null ? 15 : web.getTimeoutSeconds()));

        var politeness = new CrawlRequest.Politeness(
                Duration.ofMillis(web.getRequestDelayMs() == null ? 500 : web.getRequestDelayMs()),
                web.getUserAgent(),
                web.isRespectRobots());

        return new CrawlRequest(web.getStartUrl(), scope, limits, politeness);
    }

    private static String describe(Throwable t) {
        String message = t.getMessage();
        return message == null || message.isBlank() ? t.getClass().getSimpleName() : message;
    }

    /** Truncates third-party text before it is stored as metadata. */
    private static String cap(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    /**
     * How long a run may be in flight before it is treated as abandoned: its own
     * time budget plus a margin, so a slow but healthy run is never reaped.
     */
    private static Duration staleRunThreshold(IngestionSource source) {
        return Duration.ofMinutes(source.settings().timeBudgetMinutesOrDefault()).plus(STALE_RUN_MARGIN);
    }

    /**
     * Receives crawled pages and does the per-document work: convert, compare,
     * embed, record.
     */
    private final class Collector implements CrawlSink {

        private final RagConfiguration knowledgeBase;
        private final String knowledgeBaseId;
        private final IngestionSource source;
        private final String sourceKey;
        private final String runId;
        private final Mode mode;
        private final Tags metricTags;

        private EmbeddingStore<TextSegment> store;
        private EmbeddingModel model;

        private int seen;
        private int ingested;
        private int unchanged;
        private int skipped;
        private int failed;
        /**
         * Of those failures, the ones that said nothing about whether the page exists.
         */
        private int unreachable;
        private int segments;
        private int tombstoned;
        private boolean replaceUnsupported;
        private boolean tombstoningSkipped;
        private boolean budgetExhausted;

        private Collector(RagConfiguration knowledgeBase, String knowledgeBaseId,
                IngestionSource source, String sourceKey, String runId, Mode mode) {
            this.knowledgeBase = knowledgeBase;
            this.knowledgeBaseId = knowledgeBaseId;
            this.source = source;
            this.sourceKey = sourceKey;
            this.runId = runId;
            this.mode = mode;
            // Tagged by source: a single global error counter tells an operator that
            // something is failing but not which source, which is the part they need.
            this.metricTags = Tags.of("knowledgeBase", knowledgeBaseId, "source", String.valueOf(source.getName()));
        }

        EmbeddingStore<TextSegment> store() {
            if (store == null) {
                store = embeddingStoreFactory.getOrCreate(knowledgeBase, knowledgeBaseId);
            }
            return store;
        }

        private EmbeddingModel model() {
            if (model == null) {
                model = embeddingModelFactory.getOrCreate(knowledgeBase);
            }
            return model;
        }

        @Override
        public ConditionalHeaders conditionalFor(String documentId) {
            return stateStore.lookup(sourceKey, documentId)
                    // A tombstoned document has no vectors any more, so revalidating it
                    // would earn a 304 and leave it permanently unretrievable. Ask for
                    // the body.
                    .filter(state -> !state.tombstoned())
                    .map(state -> new ConditionalHeaders(state.etag(), state.lastModified()))
                    .orElseGet(ConditionalHeaders::none);
        }

        @Override
        public boolean isCancelled() {
            return budgetExhausted;
        }

        @Override
        public void onUnchanged(String documentId) {
            seen++;
            unchanged++;
            if (mode == Mode.INGEST) {
                stateStore.recordSeen(sourceKey, documentId, runId);
            }
        }

        @Override
        public void onError(CrawlError error) {
            failed++;
            meterRegistry.counter("eddi.ingestion.errors", metricTags).increment();

            if (mode != Mode.INGEST || error.documentId() == null || !error.contentUnknown()) {
                return;
            }
            // The server refused, failed, or asked us to come back later, so this run
            // learned nothing about whether the page still exists. Recording the run
            // against the document stops it counting as a miss: otherwise the same
            // tail pages of a rate-limited site are deleted after
            // tombstoneAfterMissedRuns runs, with every run reporting success.
            unreachable++;
            stateStore.recordUnreachable(sourceKey, error.documentId(), runId);
        }

        @Override
        public void onPage(CrawledPage page) {
            seen++;
            try {
                String markdown = converter.convert(page.html(), page.finalUrl(),
                        source.settings().maxContentLengthOrDefault());
                if (page.truncated()) {
                    // The body hit its size cap, so this document is incomplete. Said
                    // once per page rather than silently embedding a fragment as if it
                    // were the whole thing.
                    LOGGER.infof("Document '%s' of source '%s' was truncated at the page size cap",
                            LogSanitizer.sanitize(page.documentId()), LogSanitizer.sanitize(source.getName()));
                }
                if (markdown.isBlank()) {
                    // A page of pure navigation converts to nothing; storing an empty
                    // document would only pollute retrieval.
                    skipped++;
                    return;
                }

                String hash = ContentHashes.sha256(markdown);
                var existing = stateStore.lookup(sourceKey, page.documentId());
                if (existing.isPresent() && !existing.get().hasChanged(hash)) {
                    unchanged++;
                    if (mode == Mode.INGEST) {
                        stateStore.recordSeen(sourceKey, page.documentId(), runId);
                    }
                    return;
                }

                if (mode == Mode.PREVIEW) {
                    ingested++;
                    return;
                }

                int stored = embed(page, markdown);
                segments += stored;
                ingested++;
                meterRegistry.counter("eddi.ingestion.segments.stored", metricTags).increment(stored);

                // Only now, with the vectors safely stored. Recording before this — or
                // while deciding whether to ingest, as the draft did — means one
                // provider timeout marks a page done forever.
                stateStore.recordIngested(sourceKey, page.documentId(), hash,
                        page.etag(), page.lastModified(), runId);

                if (segments >= source.settings().maxSegmentsPerRunOrDefault()) {
                    budgetExhausted = true;
                    LOGGER.warnf("Ingestion of source '%s' stopped at its segment budget (%d)",
                            LogSanitizer.sanitize(source.getName()), segments);
                }
            } catch (RuntimeException e) {
                failed++;
                meterRegistry.counter("eddi.ingestion.errors", metricTags).increment();
                LOGGER.warnf(e, "Failed to ingest a document of source '%s'",
                        LogSanitizer.sanitize(source.getName()));
            }
        }

        /**
         * Replaces a document's chunks, and returns how many were actually written —
         * not an estimate. The draft reported {@code markdown.length() / chunkSize},
         * which its own integration test then asserted on.
         */
        private int embed(CrawledPage page, String markdown) {
            EmbeddingStore<TextSegment> embeddingStore = store();

            Metadata metadata = Metadata.from(METADATA_DOCUMENT_ID, page.documentId())
                    .put(METADATA_URL, page.finalUrl())
                    // Capped: the title comes from a third-party page and is copied onto
                    // every segment of the document.
                    .put(METADATA_TITLE, cap(page.title(), MAX_TITLE_LENGTH))
                    .put(METADATA_SOURCE, String.valueOf(source.getName()))
                    .put(METADATA_SOURCE_KEY, sourceKey)
                    .put(METADATA_RUN_ID, runId)
                    .put(METADATA_INGESTED_AT, Instant.now().toString());

            var splitter = DocumentSplitters.recursive(
                    knowledgeBase.getChunkSize() == null ? 1000 : knowledgeBase.getChunkSize(),
                    knowledgeBase.getChunkOverlap() == null ? 100 : knowledgeBase.getChunkOverlap());

            List<TextSegment> textSegments = splitter.split(Document.from(markdown, metadata));
            if (textSegments.isEmpty()) {
                return 0;
            }

            // Add first, then drop what this run superseded.
            //
            // Removing first meant a provider failure between the delete and the add
            // left the document with no vectors at all, while its state row still
            // carried the old hash — so a page that changed, failed to embed, and then
            // reverted was reported "unchanged" for ever with nothing in the store. A
            // crash in that window did the same with no exception. This order costs a
            // moment of duplication instead, which the runId filter then removes.
            var embeddings = model().embedAll(textSegments).content();
            embeddingStore.addAll(embeddings, textSegments);

            try {
                embeddingStore.removeAll(metadataKey(METADATA_DOCUMENT_ID).isEqualTo(page.documentId())
                        .and(metadataKey(METADATA_SOURCE_KEY).isEqualTo(sourceKey))
                        .and(metadataKey(METADATA_RUN_ID).isNotEqualTo(runId)));
            } catch (UnsupportedFeatureException e) {
                // Surfaced in the report rather than swallowed: on a store that cannot
                // delete, re-ingestion accumulates, and the operator has to know.
                replaceUnsupported = true;
            }
            return textSegments.size();
        }

        IngestionReport toReport(WebCrawler.CrawlSummary summary, String error, Instant startedAt) {
            double cost = 0.0;
            Double rate = source.settings().getCostPerThousandSegments();
            if (rate != null && rate > 0) {
                cost = (segments / 1000.0) * rate;
            }
            return new IngestionReport(
                    runId,
                    source.getId(),
                    error != null
                            ? IngestionReport.Outcome.FAILED
                            : mode == Mode.PREVIEW
                                    ? IngestionReport.Outcome.PREVIEW
                                    : IngestionReport.Outcome.COMPLETED,
                    seen, ingested, unchanged, skipped, failed, tombstoned, segments, cost,
                    replaceUnsupported, tombstoningSkipped,
                    summary == null ? null : summary.stopReason(),
                    Duration.between(startedAt, Instant.now()),
                    error);
        }
    }

    /**
     * What one ingestion run did.
     *
     * @param replaceUnsupported
     *            the configured vector store cannot delete by metadata, so
     *            re-ingested documents accumulate stale chunks. Reported rather
     *            than hidden: it changes what retrieval returns.
     * @param tombstoningSkipped
     *            the crawl did not cover the whole source, so nothing was concluded
     *            to be deleted
     */
    public record IngestionReport(
            String runId,
            String sourceId,
            Outcome outcome,
            int documentsSeen,
            int documentsIngested,
            int documentsUnchanged,
            int documentsSkipped,
            int documentsFailed,
            int documentsTombstoned,
            int segmentsStored,
            double costUsd,
            boolean replaceUnsupported,
            boolean tombstoningSkipped,
            WebCrawler.StopReason stopReason,
            Duration duration,
            String message) {

        public enum Outcome {
            COMPLETED, PREVIEW, FAILED, SKIPPED, ALREADY_RUNNING
        }

        static IngestionReport failed(String runId, String sourceId, String message) {
            return new IngestionReport(runId, sourceId, Outcome.FAILED, 0, 0, 0, 0, 0, 0, 0, 0.0,
                    false, false, null, Duration.ZERO, message);
        }

        static IngestionReport skipped(String sourceId, String message) {
            return new IngestionReport(null, sourceId, Outcome.SKIPPED, 0, 0, 0, 0, 0, 0, 0, 0.0,
                    false, false, null, Duration.ZERO, message);
        }

        static IngestionReport alreadyRunning(String sourceId) {
            return new IngestionReport(null, sourceId, Outcome.ALREADY_RUNNING, 0, 0, 0, 0, 0, 0, 0, 0.0,
                    false, false, null, Duration.ZERO,
                    "A run is already in flight for this source");
        }

        public boolean isSuccess() {
            return outcome == Outcome.COMPLETED || outcome == Outcome.PREVIEW;
        }
    }
}
