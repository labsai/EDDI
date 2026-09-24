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
import ai.labs.eddi.modules.ingestion.extract.DocumentExtractors;
import ai.labs.eddi.modules.ingestion.extract.ExtractionLimits;
import ai.labs.eddi.modules.ingestion.extract.UnreadableDocumentException;
import ai.labs.eddi.modules.ingestion.files.IIngestedFileStore;
import ai.labs.eddi.modules.ingestion.files.IIngestedFileStore.StoredFile;
import ai.labs.eddi.modules.llm.impl.EmbeddingModelFactory;
import ai.labs.eddi.modules.llm.impl.EmbeddingStoreFactory;
import ai.labs.eddi.utils.LogSanitizer;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.document.splitter.DocumentSplitters;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.exception.UnsupportedFeatureException;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.request.EmbeddingInputType;
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
    private final IIngestedFileStore fileStore;
    private final DocumentExtractors extractors;
    private final EmbeddingModelFactory embeddingModelFactory;
    private final EmbeddingStoreFactory embeddingStoreFactory;
    private final MeterRegistry meterRegistry;

    @Inject
    public IngestionPipeline(WebCrawler crawler,
            HtmlToMarkdownConverter converter,
            IIngestionStateStore stateStore,
            IIngestedFileStore fileStore,
            DocumentExtractors extractors,
            EmbeddingModelFactory embeddingModelFactory,
            EmbeddingStoreFactory embeddingStoreFactory,
            MeterRegistry meterRegistry) {
        this.crawler = crawler;
        this.converter = converter;
        this.stateStore = stateStore;
        this.fileStore = fileStore;
        this.extractors = extractors;
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
                IngestionReport.failed(runId, source.effectiveId(), reason), IngestionRun.Status.FAILED);
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
                early = IngestionReport.failed(reservedRunId, source.effectiveId(),
                        "The knowledge base has no name, and its name is what the vector store is keyed by");
            } else if (!source.isEnabled() && mode == Mode.INGEST) {
                early = IngestionReport.skipped(source.effectiveId(), "Source is disabled");
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
                    return IngestionReport.alreadyRunning(source.effectiveId());
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
            SourceRun sourceRun = source.isUpload()
                    ? readUploadedFiles(sourceKey, source, collector)
                    : crawl(source, collector);
            WebCrawler.CrawlSummary summary = sourceRun.summary();

            collector.tombstoned = reconcileDeletions(source, sourceKey, runId, mode, sourceRun, collector);

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
                                   SourceRun sourceRun, Collector collector) {

        if (mode != Mode.INGEST) {
            return 0;
        }
        if (!sourceRun.coveredWholeSource()) {
            collector.tombstoningSkipped = true;
            LOGGER.infof("Not reconciling deletions for source '%s': the run stopped at %s rather than covering "
                    + "the source", LogSanitizer.sanitize(source.getName()), sourceRun.summary().stopReason());
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
        finish(mode, reservedRunId, sourceKey, IngestionReport.failed(reservedRunId, source.effectiveId(), reason),
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

    /**
     * Removes one document's vectors now, rather than at the next run.
     *
     * <p>
     * Deleting an uploaded file has to take its content out of retrieval
     * immediately. Leaving that to the next run would mean an operator who removes
     * a document because it should not have been there is told it is gone while
     * agents keep answering from it until the cron fires — which, for a source with
     * no cron, is never.
     *
     * @return false when the configured vector store cannot delete by metadata, so
     *         the chunks are still there and the caller has to say so
     */
    public boolean forgetDocument(String ragConfigId, RagConfiguration knowledgeBase, IngestionSource source,
                                  String documentId) {

        String sourceKey = stateKey(ragConfigId, source);
        // The state row goes first. If the removal below fails, a tombstoned row
        // means the next run re-ingests the document rather than reporting it
        // unchanged over vectors that were never deleted.
        stateStore.markTombstoned(sourceKey, List.of(documentId));
        try {
            embeddingStoreFactory.getOrCreate(knowledgeBase, knowledgeBase.getName())
                    .removeAll(metadataKey(METADATA_DOCUMENT_ID).isEqualTo(documentId)
                            .and(metadataKey(METADATA_SOURCE_KEY).isEqualTo(sourceKey)));
            return true;
        } catch (UnsupportedFeatureException e) {
            LOGGER.warnf("The vector store of knowledge base '%s' cannot delete by metadata, so the chunks of "
                    + "document '%s' remain retrievable", LogSanitizer.sanitize(knowledgeBase.getName()),
                    LogSanitizer.sanitize(documentId));
            return false;
        } catch (RuntimeException e) {
            // A store that is merely unwell. Reported the same way rather than
            // thrown: the caller is deleting a file, and a 500 would leave the
            // operator with a file still listed and no idea whether its content is
            // still being answered from. The row is tombstoned either way, so the
            // next run re-ingests rather than reporting it unchanged over nothing.
            LOGGER.errorf(e, "Could not remove the chunks of document '%s' from knowledge base '%s'",
                    LogSanitizer.sanitize(documentId), LogSanitizer.sanitize(knowledgeBase.getName()));
            return false;
        }
    }

    /**
     * Removes everything one source ever put into the knowledge base.
     *
     * <p>
     * For a source that is being deleted, or that is changing into a kind of source
     * that cannot own the documents it already has. Without it, removing an upload
     * source deletes the only copy of its files while every vector they produced
     * stays retrievable and unreachable: no endpoint lists them, because the source
     * they belong to is gone.
     *
     * @return false when the vector store cannot delete by metadata, so the chunks
     *         are still there
     */
    public boolean forgetSource(String ragConfigId, RagConfiguration knowledgeBase, IngestionSource source) {
        String sourceKey = stateKey(ragConfigId, source);
        try {
            embeddingStoreFactory.getOrCreate(knowledgeBase, knowledgeBase.getName())
                    .removeAll(metadataKey(METADATA_SOURCE_KEY).isEqualTo(sourceKey));
            stateStore.purgeSource(sourceKey);
            return true;
        } catch (UnsupportedFeatureException e) {
            LOGGER.warnf("The vector store of knowledge base '%s' cannot delete by metadata, so the chunks of "
                    + "removed source '%s' remain retrievable", LogSanitizer.sanitize(knowledgeBase.getName()),
                    LogSanitizer.sanitize(source.getName()));
            stateStore.purgeSource(sourceKey);
            return false;
        } catch (RuntimeException e) {
            LOGGER.errorf(e, "Could not remove the chunks of source '%s' from knowledge base '%s'; they stay "
                    + "retrievable and nothing lists them any more",
                    LogSanitizer.sanitize(source.getName()), LogSanitizer.sanitize(knowledgeBase.getName()));
            return false;
        }
    }

    /**
     * Claims the source's run slot for something other than a run.
     *
     * <p>
     * Deleting a file has to exclude a run, not merely notice one: a run that
     * starts between the check and the delete lists the file, loads bytes that are
     * about to go, embeds them, and records the document as ingested — which clears
     * the tombstone the delete just wrote. The file is gone and its content is
     * still retrievable, for a cron-less source indefinitely. Taking the same claim
     * a run takes is what makes that impossible rather than unlikely.
     *
     * @return the claim to pass to {@link #releaseClaim}, or empty when a run holds
     *         it
     */
    public Optional<String> claimForMaintenance(String ragConfigId, IngestionSource source) {
        return reserveRun(ragConfigId, source);
    }

    /**
     * Releases a claim from {@link #claimForMaintenance}, recording what it did.
     */
    public void releaseClaim(String ragConfigId, IngestionSource source, String claimId, int documentsTombstoned) {
        stateStore.finishRun(new IngestionRun(claimId, stateKey(ragConfigId, source),
                IngestionRun.Status.COMPLETED, null, Instant.now(),
                0, 0, 0, 0, documentsTombstoned, 0, 0.0, null));
    }

    /**
     * State is scoped to a source of a knowledge base, not to a source name.
     *
     * <p>
     * Public because the uploaded files of a source are keyed by it too: a file,
     * the document state derived from it and the vectors it produced all have to
     * answer to one key, or a purge leaves two of the three behind.
     */
    public static String stateKey(String ragConfigId, IngestionSource source) {
        return stateKeyForSourceId(ragConfigId, source.effectiveId());
    }

    /**
     * The same key, for a caller that has only the source's id left. Named apart
     * from {@link #stateKey} so that a null argument still picks one of them.
     */
    public static String stateKeyForSourceId(String ragConfigId, String sourceId) {
        return ragConfigId + ":" + sourceId;
    }

    /**
     * What a run covered, and how.
     *
     * <p>
     * Coverage is carried separately from the summary because the two kinds of
     * source know it differently. A crawl can only infer it — it stopped at a page
     * cap, or it reached nothing at all, and {@code CrawlSummary} works that out
     * from what happened. An upload source knows it outright: it listed the store.
     * Reusing the crawl's inference for uploads would mean an operator who deletes
     * the last file of a source is told nothing was concluded, and its vectors
     * would stay in the knowledge base for good.
     */
    private record SourceRun(WebCrawler.CrawlSummary summary, boolean coveredWholeSource) {
    }

    private SourceRun crawl(IngestionSource source, Collector collector) {
        WebCrawler.CrawlSummary summary = crawler.crawl(toCrawlRequest(source), collector);
        return new SourceRun(summary, summary.coveredWholeSource() && learnedSomething(collector));
    }

    /**
     * Whether a crawl that reported coverage actually learned anything.
     *
     * <p>
     * The crawler counts a page it handed over as fetched even when the sink
     * discarded it as blank, so a site behind a JavaScript challenge or a
     * maintenance page answers 200 for everything, "covers the source", and yields
     * nothing. Two such runs would delete the whole corpus.
     *
     * <p>
     * Not simply "no usable document": a site whose pages have genuinely been
     * deleted also yields none, and that is exactly when reconciliation should run.
     * The distinction is whether anything definitive was learned — a 404 or a 410 —
     * rather than only failures that hide the content.
     *
     * <p>
     * This is a statement about crawling, which is why it lives here rather than in
     * the reconciliation it feeds. An upload source that lists an empty store has
     * learned something definitive: the operator deleted the files. Applying a
     * crawler's caution to it would leave a deleted document answering questions
     * for ever, because an upload source has no next crawl to correct it.
     */
    private static boolean learnedSomething(Collector collector) {
        boolean nothingUsable = collector.ingested + collector.unchanged == 0;
        boolean nothingDefinitive = collector.failed == collector.unreachable;
        return !(nothingUsable && nothingDefinitive);
    }

    /**
     * Reads every file an upload source holds, extracting text from each.
     *
     * <p>
     * The file's own hash decides whether it changed, and it is known without
     * reading the bytes — so an unchanged 20 MB manual costs one metadata query per
     * run rather than a download and a full PDF parse.
     */
    private SourceRun readUploadedFiles(String sourceKey, IngestionSource source, Collector collector) {
        Instant start = Instant.now();
        Instant deadline = uploadDeadline(start, source);
        List<StoredFile> files;
        try {
            files = fileStore.list(sourceKey);
        } catch (RuntimeException e) {
            // The store is unavailable, so this run learned nothing about which files
            // exist. Reported as an uncovered run: concluding "no files" here would
            // delete the whole knowledge base over a database blip.
            collector.onError(new CrawlSink.CrawlError(null, null,
                    "The uploaded files of this source could not be listed: " + describe(e), 0, true));
            return new SourceRun(uploadSummary(collector, start, WebCrawler.StopReason.CANCELLED), false);
        }

        ExtractionLimits limits = ExtractionLimits.defaults()
                .withMaxCharacters(source.settings().maxContentLengthOrDefault());
        WebCrawler.StopReason stopReason = WebCrawler.StopReason.COMPLETED;
        int read = 0;

        for (StoredFile file : files) {
            if (collector.isCancelled()) {
                stopReason = WebCrawler.StopReason.CANCELLED;
                break;
            }
            if (Instant.now().isAfter(deadline)) {
                stopReason = WebCrawler.StopReason.TIME_LIMIT;
                break;
            }
            if (read >= source.upload().maxFilesOrDefault()) {
                // The limit was lowered after the files were uploaded. Stopping short
                // is not coverage, so nothing is concluded to be deleted.
                stopReason = WebCrawler.StopReason.PAGE_LIMIT;
                break;
            }
            read++;
            readOneFile(sourceKey, source, file, limits, collector);
        }

        boolean covered = stopReason == WebCrawler.StopReason.COMPLETED;
        return new SourceRun(uploadSummary(collector, start, stopReason), covered);
    }

    /**
     * When a run over uploaded files has to stop.
     *
     * <p>
     * The same budget a crawl gets. Without it a run over a large source can
     * outlive the point at which it is treated as abandoned, and the next fire
     * starts a second worker embedding into the same store — each one deleting the
     * other's fresh chunks, because replacement filters on the run id.
     *
     * <p>
     * Its own method so a test can shorten it. The alternative is a test that
     * blocks for the shortest budget the configuration allows, which is a minute,
     * and a minute of wall clock in a unit suite is a minute nobody spends twice.
     */
    Instant uploadDeadline(Instant start, IngestionSource source) {
        return start.plus(Duration.ofMinutes(source.settings().timeBudgetMinutesOrDefault()));
    }

    private void readOneFile(String sourceKey, IngestionSource source, StoredFile file,
                             ExtractionLimits limits, Collector collector) {
        // Asked before the bytes are fetched: an unchanged file needs neither.
        var known = stateStore.lookup(sourceKey, file.fileId());
        if (known.isPresent() && !known.get().tombstoned() && !known.get().hasChanged(file.contentHash())) {
            collector.onUnchanged(file.fileId());
            return;
        }
        try {
            byte[] content = fileStore.load(sourceKey, file.fileId()).orElse(null);
            if (content == null) {
                // Deleted between the listing and the read. Not an error and not a
                // miss: the next run will see it gone and reconcile it properly.
                return;
            }
            String markdown = extractors.extract(content, file.mimeType(), limits);
            if (markdown.length() >= limits.maxCharacters()) {
                // Said once per file rather than silently embedding the first third
                // of a manual as if it were the whole thing.
                LOGGER.infof("File '%s' of source '%s' was truncated at %d characters (maxContentLength)",
                        LogSanitizer.sanitize(file.fileName()), LogSanitizer.sanitize(source.getName()),
                        limits.maxCharacters());
            }
            collector.onExtractedDocument(file.fileId(), "file:" + file.fileName(),
                    titleOf(file.fileName()), markdown, file.contentHash());
        } catch (UnreadableDocumentException | IIngestedFileStore.IngestedFileStoreException e) {
            // The file is there and could not be read. Recorded as unreachable rather
            // than missing, so a broken file does not have vectors it never had
            // deleted, and does not count as evidence that the source is empty.
            collector.onError(new CrawlSink.CrawlError(file.fileId(), file.fileName(),
                    describe(e), 0, true));
        }
    }

    private static WebCrawler.CrawlSummary uploadSummary(Collector collector, Instant start,
                                                         WebCrawler.StopReason stopReason) {
        return new WebCrawler.CrawlSummary(collector.ingested, collector.unchanged, collector.skipped,
                collector.failed, collector.unreachable, collector.seen, 0L,
                Duration.between(start, Instant.now()), stopReason);
    }

    /** A file name without its extension reads better as a document title. */
    static String titleOf(String fileName) {
        int dot = fileName.lastIndexOf('.');
        String stem = dot > 0 ? fileName.substring(0, dot) : fileName;
        return stem.isBlank() ? fileName : stem;
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
                // DOCUMENT: the crawler is storing these vectors. An asymmetric model
                // embeds a document differently from a query, and gets to know which.
                model = embeddingModelFactory.getOrCreate(knowledgeBase, EmbeddingInputType.DOCUMENT);
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
                acceptDocument(page.documentId(), page.finalUrl(), page.title(), markdown,
                        ContentHashes.sha256(markdown), page.etag(), page.lastModified());
            } catch (RuntimeException e) {
                recordDocumentFailure(e);
            }
        }

        /**
         * A document whose text something other than the crawler produced — an uploaded
         * file.
         *
         * @param changeKey
         *            what decides whether this document changed. For a file it is the
         *            hash of the bytes, not of the extracted text: the text is
         *            re-derived on every run, and an extractor improving its output
         *            would otherwise re-embed every file in the knowledge base.
         */
        void onExtractedDocument(String documentId, String url, String title, String markdown, String changeKey) {
            seen++;
            try {
                acceptDocument(documentId, url, title, markdown, changeKey, null, null);
            } catch (RuntimeException e) {
                recordDocumentFailure(e);
            }
        }

        private void recordDocumentFailure(RuntimeException e) {
            failed++;
            meterRegistry.counter("eddi.ingestion.errors", metricTags).increment();
            LOGGER.warnf(e, "Failed to ingest a document of source '%s'",
                    LogSanitizer.sanitize(source.getName()));
        }

        /** Everything that is the same whatever produced the text. */
        private void acceptDocument(String documentId, String url, String title, String markdown,
                                    String changeKey, String etag, String lastModified) {

            if (markdown.isBlank()) {
                // A page of pure navigation converts to nothing; storing an empty
                // document would only pollute retrieval.
                skipped++;
                return;
            }

            var existing = stateStore.lookup(sourceKey, documentId);
            if (existing.isPresent() && !existing.get().hasChanged(changeKey)) {
                unchanged++;
                if (mode == Mode.INGEST) {
                    stateStore.recordSeen(sourceKey, documentId, runId);
                }
                return;
            }

            if (mode == Mode.PREVIEW) {
                ingested++;
                return;
            }

            int stored = embed(documentId, url, title, markdown);
            segments += stored;
            ingested++;
            meterRegistry.counter("eddi.ingestion.segments.stored", metricTags).increment(stored);

            // Only now, with the vectors safely stored. Recording before this — or
            // while deciding whether to ingest, as the draft did — means one
            // provider timeout marks a page done forever.
            stateStore.recordIngested(sourceKey, documentId, changeKey, etag, lastModified, runId);

            if (segments >= source.settings().maxSegmentsPerRunOrDefault()) {
                budgetExhausted = true;
                LOGGER.warnf("Ingestion of source '%s' stopped at its segment budget (%d)",
                        LogSanitizer.sanitize(source.getName()), segments);
            }
        }

        /**
         * Replaces a document's chunks, and returns how many were actually written —
         * not an estimate. The draft reported {@code markdown.length() / chunkSize},
         * which its own integration test then asserted on.
         */
        private int embed(String documentId, String url, String title, String markdown) {
            EmbeddingStore<TextSegment> embeddingStore = store();

            Metadata metadata = Metadata.from(METADATA_DOCUMENT_ID, documentId)
                    .put(METADATA_URL, url)
                    // Capped: the title comes from a third-party page and is copied onto
                    // every segment of the document.
                    .put(METADATA_TITLE, cap(title, MAX_TITLE_LENGTH))
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
                embeddingStore.removeAll(metadataKey(METADATA_DOCUMENT_ID).isEqualTo(documentId)
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
                    source.effectiveId(),
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
     * @param sourceId
     *            always {@link IngestionSource#effectiveId()}, never
     *            {@code getId()}: a source that arrived without an id is addressed,
     *            keyed and scheduled by its name everywhere else, so reporting null
     *            here left the run history, the REST answer and the fire log unable
     *            to say which source they were about
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
