/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion;

import ai.labs.eddi.configs.rag.model.KnowledgeBaseStorage;
import ai.labs.eddi.configs.rag.model.IngestionSource;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.modules.ingestion.IIngestionStateStore.DocumentState;
import ai.labs.eddi.modules.ingestion.IIngestionStateStore.IngestionRun;
import ai.labs.eddi.modules.ingestion.connector.Coverage;
import ai.labs.eddi.modules.ingestion.connector.DiscoveryContext;
import ai.labs.eddi.modules.ingestion.connector.DiscoveryResult;
import ai.labs.eddi.modules.ingestion.connector.KnownVersion;
import ai.labs.eddi.modules.ingestion.connector.Partition;
import ai.labs.eddi.modules.ingestion.connector.SourceConnector;
import ai.labs.eddi.modules.ingestion.connector.SourceConnectors;
import ai.labs.eddi.modules.ingestion.connector.SourceContext;
import ai.labs.eddi.modules.ingestion.connector.SourceDocument;
import ai.labs.eddi.modules.ingestion.connector.SourceDocument.Content;
import ai.labs.eddi.modules.ingestion.connector.SourceError;
import ai.labs.eddi.modules.ingestion.connector.SourceException;
import ai.labs.eddi.modules.ingestion.connector.WorkItem;
import ai.labs.eddi.modules.ingestion.crawl.WebCrawler;
import ai.labs.eddi.modules.ingestion.crawl.WebSourceConnector;
import ai.labs.eddi.modules.ingestion.extract.DocumentExtractors;
import ai.labs.eddi.modules.ingestion.extract.ExtractionLimits;
import ai.labs.eddi.modules.ingestion.extract.UnreadableDocumentException;
import ai.labs.eddi.modules.ingestion.files.IIngestedFileStore;
import ai.labs.eddi.modules.ingestion.files.UploadSourceConnector;
import ai.labs.eddi.modules.llm.impl.EmbeddingModelFactory;
import ai.labs.eddi.modules.llm.impl.EmbeddingStoreFactory;
import ai.labs.eddi.modules.llm.impl.InMemoryStoreFullException;
import ai.labs.eddi.modules.llm.impl.InMemoryStoreRetiredException;
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
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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

    /**
     * The state row that marks a source whose state was cleared, until the orphan
     * sweep has run. Not a URL and not a file id, so no document can collide with
     * it.
     */
    static final String SWEEP_MARKER = "eddi:orphan-sweep";

    /** Runs since a purge beyond which the sweep declines to guess. */
    private static final int SWEEP_RUN_LIMIT = 1000;

    /** Added to a run's time budget before it counts as abandoned. */
    private static final Duration STALE_RUN_MARGIN = Duration.ofMinutes(15);

    /**
     * How often a crawl asks whether it still owns its source. Between pages only —
     * before embedding a document it always asks, because that is the write that
     * outlives the run.
     */
    private static final Duration OWNERSHIP_CHECK_INTERVAL = Duration.ofSeconds(5);

    /**
     * How often a run writes its counters while it works. Throttled on purpose: one
     * small update per source every couple of seconds is noise to the database, one
     * per document is not. Not final only so a test can make every document report.
     */
    Duration progressReportInterval = Duration.ofSeconds(2);

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

    private final SourceConnectors connectors;
    private final HtmlToMarkdownConverter converter;
    private final IIngestionStateStore stateStore;
    private final DocumentExtractors extractors;
    private final EmbeddingModelFactory embeddingModelFactory;
    private final EmbeddingStoreFactory embeddingStoreFactory;
    private final MeterRegistry meterRegistry;

    /**
     * The pipeline with the two built-in connectors, for callers that assemble it
     * by hand rather than through CDI.
     */
    public IngestionPipeline(WebCrawler crawler,
            HtmlToMarkdownConverter converter,
            IIngestionStateStore stateStore,
            IIngestedFileStore fileStore,
            DocumentExtractors extractors,
            EmbeddingModelFactory embeddingModelFactory,
            EmbeddingStoreFactory embeddingStoreFactory,
            MeterRegistry meterRegistry) {
        this(SourceConnectors.of(new WebSourceConnector(crawler), new UploadSourceConnector(fileStore)),
                converter, stateStore, extractors, embeddingModelFactory, embeddingStoreFactory, meterRegistry);
    }

    @Inject
    public IngestionPipeline(SourceConnectors connectors,
            HtmlToMarkdownConverter converter,
            IIngestionStateStore stateStore,
            DocumentExtractors extractors,
            EmbeddingModelFactory embeddingModelFactory,
            EmbeddingStoreFactory embeddingStoreFactory,
            MeterRegistry meterRegistry) {
        this.connectors = connectors;
        this.converter = converter;
        this.stateStore = stateStore;
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
        stateStore.reapStaleRuns(sourceKey, staleBefore(source));
        return stateStore.startRun(sourceKey, staleAfterClaim(source));
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
        return run(ragConfigId, knowledgeBase, source, mode, reservedRunId, null);
    }

    /**
     * @param trigger
     *            what started the run, recorded on its history row; null when the
     *            caller does not say
     */
    public IngestionReport run(String ragConfigId, RagConfiguration knowledgeBase, IngestionSource source, Mode mode,
                               String reservedRunId, IngestionRun.Trigger trigger) {

        String sourceKey = stateKey(ragConfigId, source);
        IngestionReport early;
        try {
            source.validateStored();
            String name = knowledgeBase.getName();
            boolean keyedByName = !KnowledgeBaseStorage.usesIdNamespace(knowledgeBase);
            if (keyedByName && (name == null || name.isBlank())) {
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

        // Only a metric tag now; the store is addressed by ragConfigId.
        String knowledgeBaseId = knowledgeBase.getName() != null ? knowledgeBase.getName() : ragConfigId;
        String runId;
        if (mode == Mode.INGEST) {
            if (reservedRunId != null) {
                runId = reservedRunId;
            } else {
                stateStore.reapStaleRuns(sourceKey, staleBefore(source));
                var claimed = stateStore.startRun(sourceKey, staleAfterClaim(source));
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
        Collector collector = new Collector(knowledgeBase, ragConfigId, knowledgeBaseId, source, sourceKey, runId, mode,
                trigger);
        // Written once at the start so the trigger is on the row before the first
        // document, not only after the first progress interval.
        collector.reportProgress(true);

        // Everything after the run is claimed is guarded, and by Throwable rather
        // than Exception. A claimed run that is never finished blocks its source for
        // good: manual runs answer 409 and every scheduled fire fails until the row
        // is reaped. The ways out are not all RuntimeExceptions — a state-store
        // failure, an OutOfMemoryError, or a StackOverflowError from a pathological
        // page are each enough.
        try {
            reconcileStoreFingerprint(mode, ragConfigId, knowledgeBase, knowledgeBaseId, source, sourceKey, collector);
            if (mode == Mode.INGEST) {
                markForSweepIfFresh(source, sourceKey, runId);
            }
            SourceRun sourceRun = discover(connectors.forType(source.getType()), source, sourceKey, runId, mode,
                    collector);

            // Added to, not assigned: a file retired as unreadable during the run is
            // already counted.
            collector.tombstoned += reconcileDeletions(source, sourceKey, runId, mode, sourceRun, collector);

            // A run that wrote nothing (every document unchanged) never meets the
            // retired store's refusal, yet its documents are not in the knowledge
            // base's current store: report that rather than success.
            if (mode == Mode.INGEST && collector.storeRefused == null && collector.store != null
                    && EmbeddingStoreFactory.isRetired(collector.store)) {
                collector.storeRefused = new InMemoryStoreRetiredException(knowledgeBaseId).getMessage();
            }

            IngestionReport report = collector.toReport(sourceRun.stopReason(), null, startedAt);
            finish(mode, runId, sourceKey, report, statusFor(collector), trigger);
            return report;
        } catch (Throwable t) {
            LOGGER.errorf(t, "Ingestion failed for source '%s'", LogSanitizer.sanitize(source.getName()));
            try {
                IngestionReport report = collector.toReport(null, describe(t), startedAt);
                finish(mode, runId, sourceKey, report, IngestionRun.Status.FAILED, trigger);
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
        if (collector.storeRefused != null) {
            // Not "some documents failed": the in-memory store took nothing more. When
            // it is full, every later run says the same until an operator acts; when
            // it was replaced mid-run, the next run re-embeds into the new store.
            return IngestionRun.Status.FAILED;
        }
        boolean nothingUsable = collector.ingested + collector.unchanged == 0;
        return collector.failed > 0 && nothingUsable ? IngestionRun.Status.FAILED : IngestionRun.Status.COMPLETED;
    }

    /**
     * Makes sure the content hashes this run is about to trust describe the vector
     * store it is about to write to.
     *
     * <p>
     * A hash says what a document was embedded as, not where. An in-memory store is
     * gone after a restart and is rebuilt when the embedding configuration changes,
     * while the hashes outlive both — so every document used to read "unchanged"
     * over an empty store, and a scheduled run never refilled it. The store's
     * fingerprint is therefore recorded with the source's state, and when the store
     * this run writes to has a different one, the state is invalidated before
     * anything is compared: every document is embedded again, and conditional
     * requests are not sent, since a 304 would skip the page just the same.
     *
     * <p>
     * A source with no recorded fingerprint — all state written before this check
     * existed — counts as a mismatch, but only for a store that has a fingerprint
     * at all. External stores have none today ({@code null}), keep their data
     * across restarts, and are left exactly as they were.
     *
     * <p>
     * Invalidated first, recorded second: a crash between the two leaves rows that
     * re-embed and a fingerprint that still mismatches, so the next run settles it.
     * A preview only reports what a run would do, and writes nothing.
     */
    private void reconcileStoreFingerprint(Mode mode, String ragConfigId, RagConfiguration knowledgeBase,
                                           String knowledgeBaseId, IngestionSource source, String sourceKey,
                                           Collector collector) {

        String current;
        if (mode == Mode.INGEST) {
            // Bound for the whole run: the fingerprint checked here must describe the
            // store the run writes to. A later lookup could return a newer, empty store
            // if the knowledge base were dropped or remodelled meanwhile, and every
            // document would then pass as "unchanged" against the old store's state.
            collector.store = embeddingStoreFactory.getOrCreateForIngestion(ragConfigId, knowledgeBase);
            current = EmbeddingStoreFactory.fingerprintOf(collector.store);
        } else {
            current = embeddingStoreFactory.previewIngestionFingerprint(ragConfigId, knowledgeBase);
        }
        if (current == null) {
            return;
        }
        String recorded = stateStore.storeFingerprint(sourceKey).orElse(null);
        if (current.equals(recorded)) {
            return;
        }
        collector.reembedAll = true;
        if (mode != Mode.INGEST) {
            return;
        }
        LOGGER.infof("Source '%s' of knowledge base '%s' last wrote to %s vector store than the one it writes to now "
                + "(it was restarted, rebuilt, or its embedding configuration changed); every document is embedded again",
                LogSanitizer.sanitize(source.getName()), LogSanitizer.sanitize(knowledgeBaseId),
                recorded == null ? "an unrecorded" : "a different");
        stateStore.invalidateContent(sourceKey);
        stateStore.recordStoreFingerprint(sourceKey, current);
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
        if (sourceRun.coverage() == Coverage.INCREMENTAL) {
            // A change feed reports deletions as DELETE items, already applied.
            // Absence from it means nothing.
            return 0;
        }
        if (!sourceRun.coveredWholeSource()) {
            collector.tombstoningSkipped = true;
            LOGGER.infof("Not reconciling deletions for source '%s': the run stopped at %s rather than covering "
                    + "the source", LogSanitizer.sanitize(source.getName()),
                    sourceRun.incompleteReason() != null ? sourceRun.incompleteReason() : sourceRun.stopReason());
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
        boolean sweptOrphans = false;
        for (DocumentState document : gone) {
            if (SWEEP_MARKER.equals(document.documentId())) {
                sweptOrphans = sweepOrphans(source, sourceKey, runId, collector);
                continue;
            }
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
        stateStore.markTombstoned(sourceKey, sweptOrphans ? withMarker(removedIds) : removedIds);
        return removedIds.size();
    }

    private static List<String> withMarker(List<String> removedIds) {
        List<String> ids = new ArrayList<>(removedIds);
        ids.add(SWEEP_MARKER);
        return ids;
    }

    /**
     * When this run starts from no state at all — the first run, or the first after
     * a purge or a rename — records the marker the orphan sweep waits on.
     *
     * <p>
     * A read or write failure here only means no sweep: it is housekeeping, and
     * failing the run over it would stop the run that rebuilds the knowledge base.
     */
    private void markForSweepIfFresh(IngestionSource source, String sourceKey, String runId) {
        try {
            if (stateStore.listDocuments(sourceKey, 1).isEmpty()) {
                stateStore.recordIngested(sourceKey, SWEEP_MARKER, SWEEP_MARKER, null, null, runId);
            }
        } catch (RuntimeException e) {
            LOGGER.warnf(e, "Could not check whether source '%s' starts from no state; chunks it no longer has "
                    + "from before a purge will not be swept this time", LogSanitizer.sanitize(source.getName()));
        }
    }

    /**
     * Removes the chunks of this source that no run since its state was cleared
     * wrote — once the sweep marker has been missed as often as any document must
     * be before it is tombstoned.
     *
     * <p>
     * A purge forgets which documents a source has ingested but leaves their
     * vectors answering, so retrieval has no gap while the next run rebuilds. Every
     * document a run finds is re-embedded, and replacing a document removes its
     * older chunks — but a document that had disappeared from the source before the
     * purge is never found again, and with its state row gone nothing would ever
     * reconcile it: its chunks stayed retrievable for good, attributed to a source
     * that no longer claims them.
     *
     * <p>
     * The marker is a state row no crawl ever sees, so it is "missed" by every
     * complete run and reaches {@code tombstoneAfterMissedRuns} exactly as a
     * vanished page would. Waiting for it gives a page that was only briefly absent
     * after the purge the same grace any other page gets: once it comes back it is
     * re-embedded by a run since the purge and survives the sweep. The sweep then
     * keeps every chunk written by a run since the state was cleared — the purge
     * took the older run history with it, so the source's run history is exactly
     * those runs — and removes the rest.
     *
     * <p>
     * Only on a run that read every document it found: a document that failed to
     * embed, or that the server would not serve, still has only its old chunks, and
     * they are the ones to keep. Otherwise the marker stays live and the next such
     * run sweeps. A failure that answered the question does not hold the sweep
     * back: a 404 or 410 says the page is gone, and a file that yields no text has
     * had its old version retired already — so one dead link, or one unreadable
     * upload, no longer keeps every orphan retrievable for good.
     *
     * @return whether the sweep ran, so the marker can be retired
     */
    private boolean sweepOrphans(IngestionSource source, String sourceKey, String runId, Collector collector) {
        if (collector.inconclusive > 0 || collector.superseded) {
            return false;
        }
        Set<String> runsSinceClear = new HashSet<>();
        runsSinceClear.add(runId);
        try {
            List<IngestionRun> history = stateStore.listRuns(sourceKey, SWEEP_RUN_LIMIT);
            if (history.size() >= SWEEP_RUN_LIMIT) {
                LOGGER.warnf("Not sweeping source '%s': more than %d runs since its state was cleared, so the list "
                        + "of runs whose chunks to keep might be incomplete", LogSanitizer.sanitize(source.getName()),
                        SWEEP_RUN_LIMIT);
                return false;
            }
            history.forEach(run -> runsSinceClear.add(run.runId()));
            collector.store().removeAll(metadataKey(METADATA_SOURCE_KEY).isEqualTo(sourceKey)
                    .and(metadataKey(METADATA_RUN_ID).isNotIn(runsSinceClear)));
            return true;
        } catch (UnsupportedFeatureException e) {
            collector.replaceUnsupported = true;
            return true;
        } catch (RuntimeException e) {
            LOGGER.warnf(e, "Could not remove chunks that source '%s' no longer has from before its state was "
                    + "cleared; they stay retrievable and the next complete run tries again",
                    LogSanitizer.sanitize(source.getName()));
            return false;
        }
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
        finish(mode, runId, sourceKey, report, status, null);
    }

    private void finish(Mode mode, String runId, String sourceKey, IngestionReport report,
                        IngestionRun.Status status, IngestionRun.Trigger trigger) {
        if (mode != Mode.INGEST) {
            return;
        }
        stateStore.finishRun(new IngestionRun(runId, sourceKey, status, null, Instant.now(),
                report.documentsSeen(), report.documentsIngested(), report.documentsUnchanged(),
                report.documentsFailed(), report.documentsTombstoned(), report.segmentsStored(),
                report.costUsd(), report.message(), report.documentsSkipped(),
                report.stopReason() == null ? null : report.stopReason().name(), trigger));
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
     * @return whether the chunks are gone, still there because the store cannot
     *         delete by metadata, or still there because the delete failed — the
     *         last of which the caller must not treat as done
     */
    public ForgetOutcome forgetDocument(String ragConfigId, RagConfiguration knowledgeBase,
                                        IngestionSource source, String documentId) {

        String sourceKey = stateKey(ragConfigId, source);
        // The state row goes first. If the removal below fails, a tombstoned row
        // means the next run re-ingests the document rather than reporting it
        // unchanged over vectors that were never deleted.
        stateStore.markTombstoned(sourceKey, List.of(documentId));
        try {
            embeddingStoreFactory.getOrCreate(ragConfigId, knowledgeBase)
                    .removeAll(metadataKey(METADATA_DOCUMENT_ID).isEqualTo(documentId)
                            .and(metadataKey(METADATA_SOURCE_KEY).isEqualTo(sourceKey)));
            return ForgetOutcome.REMOVED;
        } catch (UnsupportedFeatureException e) {
            LOGGER.warnf("The vector store of knowledge base '%s' cannot delete by metadata, so the chunks of "
                    + "document '%s' remain retrievable", LogSanitizer.sanitize(knowledgeBase.getName()),
                    LogSanitizer.sanitize(documentId));
            return ForgetOutcome.UNSUPPORTED;
        } catch (RuntimeException e) {
            // A store that is merely unwell. Returned, not thrown, and distinct from
            // UNSUPPORTED: the caller must keep the file. Deleting it anyway left the
            // chunks retrievable for good — the row is tombstoned, reconciliation
            // skips tombstoned rows, and no file is left to delete again. Kept, the
            // file can be deleted again once the store recovers; if a run gets to it
            // first, the tombstone makes it re-embed and so replace the chunks.
            LOGGER.errorf(e, "Could not remove the chunks of document '%s' from knowledge base '%s'",
                    LogSanitizer.sanitize(documentId), LogSanitizer.sanitize(knowledgeBase.getName()));
            return ForgetOutcome.FAILED;
        }
    }

    /** What became of a document's chunks. */
    public enum ForgetOutcome {
        REMOVED,
        /** The store cannot delete by metadata; the chunks are still retrievable. */
        UNSUPPORTED,
        /**
         * The store failed; the chunks are still retrievable and it is worth trying
         * again.
         */
        FAILED
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
            embeddingStoreFactory.getOrCreate(ragConfigId, knowledgeBase)
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
     * Releases a claim from {@link #claimForMaintenance}.
     *
     * <p>
     * Closed as {@link IngestionRun.Status#MAINTENANCE}, which the run history does
     * not list. It used to be closed as a {@code COMPLETED} run, so deleting a file
     * made the source's "last run" an empty success and pushed the real one — with
     * its errors — out of sight.
     */
    public void releaseClaim(String ragConfigId, IngestionSource source, String claimId) {
        stateStore.finishRun(new IngestionRun(claimId, stateKey(ragConfigId, source),
                IngestionRun.Status.MAINTENANCE, null, Instant.now(),
                0, 0, 0, 0, 0, 0, 0.0, null));
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
     * Coverage comes from the connector, because sources know it differently. A
     * crawl can only infer it — it stopped at a page cap, or it reached nothing at
     * all — and reports {@link Coverage#FULL_TRAVERSAL}, to which
     * {@link #learnedSomething} is added here. An upload source knows it outright:
     * it listed the store, and reports {@link Coverage#FULL_LISTING}. Applying the
     * crawl's inference to uploads would mean an operator who deletes the last file
     * of a source is told nothing was concluded, and its vectors would stay in the
     * knowledge base for good.
     *
     * @param coveredWholeSource
     *            whether absence from this run is evidence of deletion
     * @param incompleteReason
     *            the connector's explanation when coverage fell short, for the log
     */
    private record SourceRun(WebCrawler.StopReason stopReason, Coverage coverage, boolean coveredWholeSource,
            String incompleteReason) {
    }

    /**
     * Runs a connector's discovery over its source, processing every document as it
     * is found.
     *
     * <p>
     * Sequential, one document at a time, on the calling thread — exactly as the
     * crawl and the upload loop ran before connectors existed. A connector's run
     * level failure ends the run like any other exception.
     */
    private SourceRun discover(SourceConnector connector, IngestionSource source, String sourceKey, String runId,
                               Mode mode, Collector collector) {
        SourceContext context = new SourceContext(source, sourceKey, runId, mode == Mode.PREVIEW);
        DiscoveryResult result;
        try {
            List<Partition> partitions = connector.listPartitions(context);
            if (partitions.size() != 1) {
                // Several partitions need a cursor each and a coverage per partition,
                // which arrive with the work queue. Refused rather than half-run.
                throw new IllegalStateException("Source type '" + connector.type() + "' lists "
                        + partitions.size() + " partitions; this version runs exactly one per source");
            }
            Partition partition = partitions.get(0);
            connector.verifyAccess(context, partition);
            Instant deadline = uploadDeadline(Instant.now(), source);
            result = connector.discover(new Discovery(connector, context, partition, deadline, collector));
        } catch (SourceException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
        if (result.nextCursor() != null) {
            // Nothing persists cursors yet, so the next run lists in full — the safe
            // direction to be wrong in.
            LOGGER.debugf("Source '%s' returned a cursor, which this version does not store",
                    LogSanitizer.sanitize(source.getName()));
        }
        boolean covered = switch (result.coverage()) {
            case FULL_LISTING -> true;
            case FULL_TRAVERSAL -> learnedSomething(collector);
            case INCREMENTAL, PARTIAL -> false;
        };
        return new SourceRun(WebCrawler.StopReason.valueOf(result.stopReason().name()), result.coverage(), covered,
                result.incompleteReason());
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
     * The distinction is whether anything definitive was learned about a document —
     * a 404 or a 410, a file with no text — rather than only failures that hide the
     * content. That is counted where it is learned, in
     * {@link Collector#definitive}, not inferred from the failure totals: a failure
     * of the source as a whole concerns no document, and a run made only of those,
     * or of a page that would not embed, must not pass for one that saw the corpus
     * gone.
     *
     * <p>
     * This is a statement about traversal, which is why it applies to
     * {@link Coverage#FULL_TRAVERSAL} only. An upload source that lists an empty
     * store has learned something definitive: the operator deleted the files.
     * Applying a crawler's caution to it would leave a deleted document answering
     * questions for ever, because an upload source has no next crawl to correct it.
     */
    private static boolean learnedSomething(Collector collector) {
        boolean nothingUsable = collector.ingested + collector.unchanged == 0;
        return !(nothingUsable && collector.definitive == 0);
    }

    /**
     * When a run's discovery has to stop, handed to every connector as
     * {@link DiscoveryContext#deadline()}. The crawler enforces its own budget from
     * the same setting; the upload connector uses this one.
     *
     * <p>
     * The same budget a crawl gets. Without it a run over a large upload source can
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

    /**
     * The framework's side of a discovery: what the connector asks, answered from
     * the run's state, and what it finds, handed to the run's collector.
     */
    private static final class Discovery implements DiscoveryContext {

        private final SourceConnector connector;
        private final SourceContext context;
        private final Partition partition;
        private final Instant deadline;
        private final Collector collector;

        private Discovery(SourceConnector connector, SourceContext context, Partition partition, Instant deadline,
                Collector collector) {
            this.connector = connector;
            this.context = context;
            this.partition = partition;
            this.deadline = deadline;
            this.collector = collector;
        }

        @Override
        public SourceContext source() {
            return context;
        }

        @Override
        public Partition partition() {
            return partition;
        }

        @Override
        public Optional<String> cursor() {
            return Optional.empty();
        }

        @Override
        public Instant deadline() {
            return deadline;
        }

        @Override
        public Optional<KnownVersion> knownVersion(String documentId) {
            return collector.knownVersion(documentId);
        }

        @Override
        public void accept(WorkItem item) {
            collector.process(item, connector, context);
        }

        @Override
        public void reportFailure(String documentId, String location, SourceError error) {
            if (LOGGER.isDebugEnabled()) {
                LOGGER.debugf("Source '%s' could not read %s: %s (%s)", LogSanitizer.sanitize(context.source().getName()),
                        location == null ? "the source" : LogSanitizer.sanitize(location),
                        LogSanitizer.sanitize(error.reason()), error.getClass().getSimpleName());
            }
            collector.onFailure(documentId, error);
        }

        @Override
        public boolean isCancelled() {
            return collector.isCancelled();
        }
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
    /**
     * The start time before which a still-RUNNING run of this source counts as
     * abandoned — for {@link IIngestionStateStore#reapStaleRuns}. Every caller
     * reaps against this one cut-off, so a read that reaps cannot disagree with a
     * claim about which runs are dead.
     */
    public static Instant staleBefore(IngestionSource source) {
        return Instant.now().minus(staleRunThreshold(source));
    }

    /**
     * The deadline recorded on a run claimed now: from the budget it runs under, so
     * a later change to the source's settings cannot move it.
     */
    private static Instant staleAfterClaim(IngestionSource source) {
        return Instant.now().plus(staleRunThreshold(source));
    }

    private static Duration staleRunThreshold(IngestionSource source) {
        return Duration.ofMinutes(source.settings().timeBudgetMinutesOrDefault()).plus(STALE_RUN_MARGIN);
    }

    /**
     * Receives what a connector discovers and does the per-document work: fetch,
     * convert, compare, embed, record.
     */
    private final class Collector {

        private final RagConfiguration knowledgeBase;
        private final String knowledgeBaseId;
        /** Addresses the store; see {@link KnowledgeBaseStorage}. */
        private final String ragConfigId;
        /** What every chunk this run writes is tagged with. */
        private final String chunkKbId;
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
         * Failures that are themselves an answer about one document: the source said it
         * is gone or refused it for good, or a file yielded no text. The only failures
         * {@link #learnedSomething} accepts as evidence that a traversal saw the
         * source, so a source-level failure or a document that would not embed never
         * stands in for one.
         */
        private int definitive;
        /**
         * Failures after which a document's old chunks may still be its only copy: the
         * content could not be looked at, did not embed, or its superseded chunks could
         * not be removed. The orphan sweep waits for a run with none. Not
         * {@link #failed}, which also counts the definitive answers — a 404, a file
         * with no text.
         */
        private int inconclusive;
        private int segments;
        /**
         * Segments the provider embedded and the run then refused to publish because it
         * no longer owned its source. Not stored, but paid for: they count against the
         * run's segment budget and cost.
         */
        private int discardedSegments;
        private int tombstoned;
        private boolean replaceUnsupported;
        private boolean tombstoningSkipped;
        private boolean budgetExhausted;
        /**
         * The run no longer owns its source: it was reaped, or the source was purged or
         * removed under it. Everything it would still write belongs to nobody.
         */
        private boolean superseded;
        /**
         * The vector store is not the one this source's state was written against, so
         * no document may be judged unchanged — see {@link #reconcileStoreFingerprint}.
         */
        private boolean reembedAll;
        /**
         * Why the in-memory store refused this run's writes, once it has; the run stops
         * there rather than failing every remaining document the same way.
         */
        private String storeRefused;
        private ExtractionLimits extractionLimits;
        private Instant nextOwnershipCheck = Instant.MIN;
        private final IngestionRun.Trigger trigger;
        private Instant nextProgressReport = Instant.MIN;

        private Collector(RagConfiguration knowledgeBase, String ragConfigId, String knowledgeBaseId,
                IngestionSource source, String sourceKey, String runId, Mode mode, IngestionRun.Trigger trigger) {
            this.trigger = trigger;
            this.knowledgeBase = knowledgeBase;
            this.knowledgeBaseId = knowledgeBaseId;
            this.ragConfigId = ragConfigId;
            this.chunkKbId = KnowledgeBaseStorage.chunkKbId(ragConfigId, knowledgeBase);
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
                // For writing: an in-memory store built under another embedding
                // configuration is replaced rather than mixed with this run's vectors.
                store = embeddingStoreFactory.getOrCreateForIngestion(ragConfigId, knowledgeBase);
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

        /** See {@link DiscoveryContext#knownVersion}. */
        Optional<KnownVersion> knownVersion(String documentId) {
            if (reembedAll) {
                // A 304 would report the page unchanged, and the store no longer has it.
                return Optional.empty();
            }
            return stateStore.lookup(sourceKey, documentId)
                    // A tombstoned document has no vectors any more, so revalidating it
                    // would earn a 304 and leave it permanently unretrievable. Ask for
                    // the body.
                    .filter(state -> !state.tombstoned())
                    .map(state -> new KnownVersion(state.contentHash(), state.etag(), state.lastModified()));
        }

        boolean isCancelled() {
            return budgetExhausted || storeRefused != null || !stillOwnsRun(false);
        }

        /**
         * Whether this run is still the one in flight for its source.
         *
         * <p>
         * A run whose row was purged — the source was removed, its knowledge base
         * renamed, or its state purged — or that was reaped, used to carry on to the
         * end of its crawl, embedding into the store and inserting state rows for a
         * source that had just been cleared. Those inserts are the one write the fence
         * cannot stop, and the vectors outlived everything: a removed source's chunks
         * stayed retrievable, and a renamed knowledge base's next run found its
         * documents "unchanged" in a store that did not have them.
         *
         * @param now
         *            ask the store now rather than at most every
         *            {@link #OWNERSHIP_CHECK_INTERVAL} — before an embedding, which is
         *            the write worth the round trip
         */
        boolean stillOwnsRun(boolean now) {
            if (mode != Mode.INGEST || superseded) {
                return !superseded;
            }
            Instant clock = Instant.now();
            if (!now && clock.isBefore(nextOwnershipCheck)) {
                return true;
            }
            nextOwnershipCheck = clock.plus(OWNERSHIP_CHECK_INTERVAL);
            try {
                superseded = stateStore.activeRun(sourceKey)
                        .map(active -> !runId.equals(active.runId()))
                        .orElse(true);
            } catch (RuntimeException e) {
                // A store that cannot answer has not taken the run away, so the run is not
                // marked superseded and keeps going. But the checks made with {@code now}
                // guard a vector write that no fence covers: publishing on an unverified
                // answer could make a removed source's text retrievable again, so those
                // refuse until the store can say. The document is not recorded, and the
                // next run takes it.
                LOGGER.debugf(e, "Could not verify that run %s still owns its source", LogSanitizer.sanitize(runId));
                return !now;
            }
            if (superseded) {
                LOGGER.warnf("Ingestion run %s of source '%s' no longer owns its source — it was purged, removed "
                        + "or reaped — and stops here", LogSanitizer.sanitize(runId),
                        LogSanitizer.sanitize(source.getName()));
            }
            return !superseded;
        }

        /**
         * Writes the counters so far onto the run's row, at most every
         * {@link #progressReportInterval} unless {@code force}d, so the Manager can
         * show a run moving instead of a spinner. Never fails the run: a progress write
         * that does not land is only a stale number until the next one.
         */
        void reportProgress(boolean force) {
            if (mode != Mode.INGEST) {
                return;
            }
            Instant clock = Instant.now();
            if (!force && clock.isBefore(nextProgressReport)) {
                return;
            }
            nextProgressReport = clock.plus(progressReportInterval);
            try {
                stateStore.reportProgress(new IngestionRun(runId, sourceKey, IngestionRun.Status.RUNNING, null, null,
                        seen, ingested, unchanged, failed, tombstoned, segments, currentCost(), null, skipped, null,
                        trigger));
            } catch (RuntimeException e) {
                LOGGER.debugf(e, "Could not record the progress of ingestion run %s", LogSanitizer.sanitize(runId));
            }
        }

        private double currentCost() {
            Double rate = source.settings().getCostPerThousandSegments();
            return rate != null && rate > 0 ? ((segments + discardedSegments) / 1000.0) * rate : 0.0;
        }

        /**
         * One discovered document, handled before discovery moves on.
         */
        void process(WorkItem item, SourceConnector connector, SourceContext context) {
            switch (item.action()) {
                case UNCHANGED -> onUnchanged(item.documentId());
                case DELETE -> onDeleted(item.documentId());
                case UPSERT -> upsert(item, connector, context);
            }
        }

        private void upsert(WorkItem item, SourceConnector connector, SourceContext context) {
            SourceDocument document = item.prefetched();
            if (document == null) {
                // Asked before the content is fetched: an unchanged document needs
                // neither the download nor the extraction.
                if (item.versionToken() != null && unchangedVersion(item.documentId(), item.versionToken())) {
                    onUnchanged(item.documentId());
                    return;
                }
                try {
                    document = connector.fetch(item, context);
                } catch (SourceException e) {
                    if (e.error() instanceof SourceError.NotFound) {
                        // Gone between discovery and fetch. Not an error and not a miss:
                        // the next run will see it gone and reconcile it properly.
                        return;
                    }
                    onFailure(item.documentId(), e.error());
                    return;
                }
                if (!item.documentId().equals(document.documentId())) {
                    throw new IllegalStateException("Source type '" + connector.type() + "' fetched document '"
                            + document.documentId() + "' for work item '" + item.documentId() + "'");
                }
            }
            switch (document.content()) {
                case Content.HtmlPage page -> onPage(document, page);
                case Content.FileBytes file -> onFile(document, file);
                case Content.Text text -> onText(document, text);
            }
        }

        /**
         * Whether a version token the source reported matches what was embedded — known
         * without the content, so the document is neither fetched nor parsed.
         */
        private boolean unchangedVersion(String documentId, String versionToken) {
            if (reembedAll) {
                return false;
            }
            var known = stateStore.lookup(sourceKey, documentId);
            return known.isPresent() && !known.get().tombstoned() && !known.get().hasChanged(versionToken);
        }

        void onUnchanged(String documentId) {
            seen++;
            unchanged++;
            if (mode == Mode.INGEST) {
                stateStore.recordSeen(sourceKey, documentId, runId);
            }
            reportProgress(false);
        }

        /**
         * A failure the connector met and moved past.
         *
         * @param documentId
         *            null when it concerns the source as a whole, which says nothing
         *            about any document's content
         */
        void onFailure(String documentId, SourceError error) {
            boolean contentUnknown = documentId == null || !error.isDefinitive();
            failed++;
            reportProgress(false);
            meterRegistry.counter("eddi.ingestion.errors", metricTags).increment();
            if (contentUnknown) {
                inconclusive++;
            } else {
                definitive++;
            }

            if (mode != Mode.INGEST || documentId == null || !contentUnknown) {
                return;
            }
            // The server refused, failed, or asked us to come back later, so this run
            // learned nothing about whether the page still exists. Recording the run
            // against the document stops it counting as a miss: otherwise the same
            // tail pages of a rate-limited site are deleted after
            // tombstoneAfterMissedRuns runs, with every run reporting success.
            stateStore.recordUnreachable(sourceKey, documentId, runId);
        }

        /**
         * A document a change feed reports deleted: its chunks go now, without waiting
         * for missed runs. Vectors first, tombstone afterwards, as in
         * {@link #reconcileDeletions}.
         */
        void onDeleted(String documentId) {
            if (mode != Mode.INGEST || !stillOwnsRun(true)) {
                return;
            }
            try {
                store().removeAll(metadataKey(METADATA_DOCUMENT_ID).isEqualTo(documentId)
                        .and(metadataKey(METADATA_SOURCE_KEY).isEqualTo(sourceKey)));
            } catch (UnsupportedFeatureException e) {
                replaceUnsupported = true;
            } catch (RuntimeException e) {
                failed++;
                inconclusive++;
                meterRegistry.counter("eddi.ingestion.errors", metricTags).increment();
                LOGGER.warnf(e, "Could not remove the chunks of deleted document '%s' of source '%s'; they stay live",
                        LogSanitizer.sanitize(documentId), LogSanitizer.sanitize(source.getName()));
                return;
            }
            stateStore.markTombstoned(sourceKey, List.of(documentId));
            tombstoned++;
            reportProgress(false);
        }

        private void onPage(SourceDocument document, Content.HtmlPage page) {
            seen++;
            try {
                String markdown = converter.convert(page.html(), document.citationUrl(),
                        source.settings().maxContentLengthOrDefault());
                logIfTruncated(document);
                acceptDocument(document, markdown);
            } catch (RuntimeException e) {
                recordDocumentFailure(e);
            }
        }

        /**
         * A stored file: its text is extracted here, and a file that yields none is a
         * definitive failure rather than a skipped page.
         */
        private void onFile(SourceDocument document, Content.FileBytes file) {
            ExtractionLimits limits = extractionLimits();
            try {
                String markdown = extractors.extract(file.bytes(), file.mimeType(), limits);
                if (markdown.isBlank()) {
                    // A scan with no text layer, or a document with nothing in it. The file
                    // is the whole of this source's evidence, so this is a definitive
                    // answer, not a page that failed to load.
                    onUnreadable(document, DocumentExtractors.emptyDocumentReason(file.mimeType()));
                    return;
                }
                if (markdown.length() >= limits.maxCharacters()) {
                    // Said once per file rather than silently embedding the first third
                    // of a manual as if it were the whole thing.
                    LOGGER.infof("File '%s' of source '%s' was truncated at %d characters (maxContentLength)",
                            LogSanitizer.sanitize(document.displayName()), LogSanitizer.sanitize(source.getName()),
                            limits.maxCharacters());
                }
                logIfTruncated(document);
                seen++;
                try {
                    acceptDocument(document, markdown);
                } catch (RuntimeException e) {
                    recordDocumentFailure(e);
                }
            } catch (UnreadableDocumentException e) {
                onUnreadable(document, describe(e));
            }
        }

        private void onText(SourceDocument document, Content.Text text) {
            seen++;
            try {
                logIfTruncated(document);
                acceptDocument(document, text.text());
            } catch (RuntimeException e) {
                recordDocumentFailure(e);
            }
        }

        private void logIfTruncated(SourceDocument document) {
            if (document.truncated()) {
                // The body hit its size cap, so this document is incomplete. Said
                // once per page rather than silently embedding a fragment as if it
                // were the whole thing.
                LOGGER.infof("Document '%s' of source '%s' was truncated at the page size cap",
                        LogSanitizer.sanitize(document.documentId()), LogSanitizer.sanitize(source.getName()));
            }
        }

        private ExtractionLimits extractionLimits() {
            if (extractionLimits == null) {
                extractionLimits = ExtractionLimits.defaults()
                        .withMaxCharacters(source.settings().maxContentLengthOrDefault());
            }
            return extractionLimits;
        }

        /**
         * Hands a document's text to the shared path.
         *
         * <p>
         * The change key is the source's version token when it supplied one — for a
         * file, the hash of the bytes rather than of the extracted text, because the
         * text is re-derived on every run and an extractor improving its output would
         * otherwise re-embed every file in the knowledge base. Otherwise it is the hash
         * of the text.
         */
        private void acceptDocument(SourceDocument document, String markdown) {
            String changeKey = document.versionToken() != null
                    ? document.versionToken()
                    : ContentHashes.sha256(markdown);
            SourceDocument.ResponseValidators validators = document.validators();
            acceptDocument(document.documentId(), document.citationUrl(), document.title(), markdown, changeKey,
                    validators == null ? null : validators.etag(),
                    validators == null ? null : validators.lastModified(),
                    validators != null);
        }

        /**
         * A stored file that yields no text — it cannot be read, or it has none.
         *
         * <p>
         * Unlike a crawl error, this is definitive: the stored file is all there is,
         * and it has just been read. If it replaced a file that had been indexed, the
         * old version's chunks are removed now. They used to be kept — the error was
         * recorded as "could not look" — so re-uploading a document as a broken or
         * scanned copy left the previous text answering questions indefinitely while
         * the file list showed only the new one.
         */
        private void onUnreadable(SourceDocument document, String reason) {
            String documentId = document.documentId();
            seen++;
            failed++;
            definitive++;
            meterRegistry.counter("eddi.ingestion.errors", metricTags).increment();
            LOGGER.warnf("File '%s' of source '%s' was not ingested: %s",
                    LogSanitizer.sanitize(document.displayName()),
                    LogSanitizer.sanitize(source.getName()), LogSanitizer.sanitize(reason));
            if (mode != Mode.INGEST) {
                return;
            }
            var known = stateStore.lookup(sourceKey, documentId);
            if (known.isEmpty() || known.get().tombstoned()) {
                return;
            }
            if (!stillOwnsRun(true)) {
                ownershipUnverified(documentId, true);
                return;
            }
            try {
                store().removeAll(metadataKey(METADATA_DOCUMENT_ID).isEqualTo(documentId)
                        .and(metadataKey(METADATA_SOURCE_KEY).isEqualTo(sourceKey)));
            } catch (UnsupportedFeatureException e) {
                replaceUnsupported = true;
            } catch (RuntimeException e) {
                // Left as it was — still live, and counted as looked-at rather than
                // missing — so the next run tries again.
                LOGGER.warnf(e, "Could not remove the superseded chunks of file '%s' of source '%s'",
                        LogSanitizer.sanitize(document.displayName()), LogSanitizer.sanitize(source.getName()));
                inconclusive++;
                stateStore.recordUnreachable(sourceKey, documentId, runId);
                return;
            }
            stateStore.markTombstoned(sourceKey, List.of(documentId));
            tombstoned++;
        }

        private void recordDocumentFailure(RuntimeException e) {
            // Either refusal ends the run: no later document could be stored either.
            if ((e instanceof InMemoryStoreFullException || e instanceof InMemoryStoreRetiredException) && storeRefused == null) {
                storeRefused = e.getMessage();
                LOGGER.errorf("Ingestion of source '%s' stopped: %s", LogSanitizer.sanitize(source.getName()),
                        LogSanitizer.sanitize(storeRefused));
            }
            failed++;
            inconclusive++;
            reportProgress(false);
            meterRegistry.counter("eddi.ingestion.errors", metricTags).increment();
            LOGGER.warnf(e, "Failed to ingest a document of source '%s'",
                    LogSanitizer.sanitize(source.getName()));
        }

        /**
         * Everything that is the same whatever produced the text.
         *
         * @param fromResponse
         *            the text came from an HTTP response, whose validators replace the
         *            stored ones even when the content has not changed
         */
        private void acceptDocument(String documentId, String url, String title, String markdown,
                                    String changeKey, String etag, String lastModified, boolean fromResponse) {
            try {
                acceptDocumentCounted(documentId, url, title, markdown, changeKey, etag, lastModified, fromResponse);
            } finally {
                reportProgress(false);
            }
        }

        private void acceptDocumentCounted(String documentId, String url, String title, String markdown,
                                           String changeKey, String etag, String lastModified,
                                           boolean fromResponse) {

            if (markdown.isBlank()) {
                // A page of pure navigation converts to nothing; storing an empty
                // document would only pollute retrieval.
                skipped++;
                return;
            }

            var existing = stateStore.lookup(sourceKey, documentId);
            if (!reembedAll && existing.isPresent() && !existing.get().hasChanged(changeKey)) {
                unchanged++;
                if (mode == Mode.INGEST && fromResponse) {
                    // The page was downloaded again, so the validators the server sent
                    // this time are the ones worth sending back next time.
                    stateStore.recordSeen(sourceKey, documentId, runId, etag, lastModified);
                } else if (mode == Mode.INGEST) {
                    stateStore.recordSeen(sourceKey, documentId, runId);
                }
                return;
            }

            if (mode == Mode.PREVIEW) {
                ingested++;
                return;
            }
            if (!stillOwnsRun(true)) {
                // Nothing written for a source that is no longer this run's: the
                // chunks would belong to nobody. Nothing was embedded either, so
                // nothing is charged to the segment budget.
                ownershipUnverified(documentId, existing.isPresent());
                return;
            }

            List<TextSegment> textSegments = split(documentId, url, title, markdown);
            int budget = source.settings().maxSegmentsPerRunOrDefault();
            if (textSegments.size() > budget - segments - discardedSegments) {
                // Checked before the provider is paid, not after: the budget is
                // documented as a hard ceiling, and a check after embedding let the
                // document that crossed it through whole.
                deferOverBudget(documentId, existing.isPresent(), textSegments.size(), budget);
                return;
            }

            int stored = embed(documentId, textSegments);
            if (stored < 0) {
                discardedSegments += textSegments.size();
                ownershipUnverified(documentId, existing.isPresent());
                return;
            }
            segments += stored;
            ingested++;
            meterRegistry.counter("eddi.ingestion.segments.stored", metricTags).increment(stored);

            // Only now, with the vectors safely stored. Recording before this — or
            // while deciding whether to ingest, as the draft did — means one
            // provider timeout marks a page done forever.
            stateStore.recordIngested(sourceKey, documentId, changeKey, etag, lastModified, runId);

            if (segments + discardedSegments >= source.settings().maxSegmentsPerRunOrDefault()) {
                budgetExhausted = true;
                LOGGER.warnf("Ingestion of source '%s' stopped at its segment budget (%d)",
                        LogSanitizer.sanitize(source.getName()), segments);
            }
        }

        /**
         * A document left alone because the run could not confirm it still owns its
         * source.
         *
         * <p>
         * The run learned nothing about the document, so it must not count as missing:
         * left unmarked, the reconciliation at the end of the run bumps its miss
         * counter, and a store that stays unwell for {@code tombstoneAfterMissedRuns}
         * runs deletes pages that are still there. It is marked the way an unreachable
         * page is, and counted as inconclusive, which also keeps the orphan sweep from
         * running on an incomplete picture.
         */
        private void ownershipUnverified(String documentId, boolean known) {
            inconclusive++;
            if (known && mode == Mode.INGEST) {
                try {
                    stateStore.recordUnreachable(sourceKey, documentId, runId);
                } catch (RuntimeException e) {
                    // The same store that could not answer may not take this either; the
                    // run is inconclusive regardless, which keeps the sweep away.
                    LOGGER.debugf(e, "Could not mark document %s unreachable", LogSanitizer.sanitize(documentId));
                }
            }
        }

        /**
         * A document that does not fit in what is left of the segment budget.
         *
         * <p>
         * When it would fit in a fresh run it waits for the next one: the run stops
         * here, and the document is neither embedded nor recorded, so the next run
         * finds it new or changed and takes it first. A document larger than the whole
         * budget never fits; it is reported as failed with the number it needs, rather
         * than ingested in part and then recorded as done.
         *
         * <p>
         * Either way a document that already has a state row is stamped as looked at,
         * so leaving it for later does not count as a miss towards deleting it.
         */
        private void deferOverBudget(String documentId, boolean known, int needed, int budget) {
            if (known) {
                stateStore.recordUnreachable(sourceKey, documentId, runId);
            }
            if (needed > budget) {
                failed++;
                // Like every other failed document: its old chunks are all it has, so a sweep
                // this
                // run must not treat them as orphans.
                inconclusive++;
                meterRegistry.counter("eddi.ingestion.errors", metricTags).increment();
                LOGGER.warnf("Document '%s' of source '%s' was not ingested: it splits into %d segments, more than the "
                        + "source's maxSegmentsPerRun (%d)", LogSanitizer.sanitize(documentId),
                        LogSanitizer.sanitize(source.getName()), needed, budget);
                return;
            }
            budgetExhausted = true;
            LOGGER.warnf("Ingestion of source '%s' stopped at its segment budget (%d); the remaining documents wait "
                    + "for the next run", LogSanitizer.sanitize(source.getName()), budget);
        }

        /**
         * A document's chunks, carrying the metadata every one of them is stored with.
         */
        private List<TextSegment> split(String documentId, String url, String title, String markdown) {
            Metadata metadata = Metadata.from(METADATA_DOCUMENT_ID, documentId)
                    .put(METADATA_URL, url)
                    // Capped: the title comes from a third-party page and is copied onto
                    // every segment of the document.
                    .put(METADATA_TITLE, cap(title, MAX_TITLE_LENGTH))
                    .put(METADATA_SOURCE, String.valueOf(source.getName()))
                    .put(METADATA_SOURCE_KEY, sourceKey)
                    .put(METADATA_RUN_ID, runId)
                    .put(METADATA_INGESTED_AT, Instant.now().toString())
                    // What retrieval filters on in the per-id layout. Without it a
                    // crawled chunk would be invisible to its own knowledge base.
                    .put(KnowledgeBaseStorage.METADATA_KB_ID, chunkKbId);

            var splitter = DocumentSplitters.recursive(
                    knowledgeBase.chunkSizeOrDefault(), knowledgeBase.chunkOverlapOrDefault());

            return splitter.split(Document.from(markdown, metadata));
        }

        /**
         * Replaces a document's chunks, and returns how many were actually written —
         * not an estimate. The draft reported {@code markdown.length() / chunkSize},
         * which its own integration test then asserted on.
         *
         * @return the number of segments stored, or -1 when the run lost its source
         *         while the embeddings were being computed and nothing was written
         */
        private int embed(String documentId, List<TextSegment> textSegments) {
            if (textSegments.isEmpty()) {
                return 0;
            }
            EmbeddingStore<TextSegment> embeddingStore = store();

            // Add first, then drop what this run superseded.
            //
            // Removing first meant a provider failure between the delete and the add
            // left the document with no vectors at all, while its state row still
            // carried the old hash — so a page that changed, failed to embed, and then
            // reverted was reported "unchanged" for ever with nothing in the store. A
            // crash in that window did the same with no exception. This order costs a
            // moment of duplication instead, which the runId filter then removes.
            var embeddings = model().embedAll(textSegments).content();
            // Ownership is asked again after the provider answers. An embedding call
            // can stall past the claim's deadline; meanwhile the run is reaped and
            // the file deleted, vectors and all. Publishing now would make that
            // deleted text retrievable again, with nothing left to remove it.
            if (!stillOwnsRun(true)) {
                return -1;
            }
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

        IngestionReport toReport(WebCrawler.StopReason stopReason, String error, Instant startedAt) {
            double cost = currentCost();
            if (error == null && superseded) {
                error = "The run stopped: its source was purged, removed or reaped while it was running";
            }
            if (error == null && storeRefused != null) {
                error = storeRefused;
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
                    stopReason,
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
            COMPLETED, PREVIEW, FAILED, SKIPPED, ALREADY_RUNNING,
            /**
             * A run was claimed and handed to its own worker; its outcome goes to the
             * source's run history, not to whoever started it.
             */
            STARTED
        }

        static IngestionReport failed(String runId, String sourceId, String message) {
            return new IngestionReport(runId, sourceId, Outcome.FAILED, 0, 0, 0, 0, 0, 0, 0, 0.0,
                    false, false, null, Duration.ZERO, message);
        }

        static IngestionReport skipped(String sourceId, String message) {
            return new IngestionReport(null, sourceId, Outcome.SKIPPED, 0, 0, 0, 0, 0, 0, 0, 0.0,
                    false, false, null, Duration.ZERO, message);
        }

        static IngestionReport started(String runId, String sourceId) {
            return new IngestionReport(runId, sourceId, Outcome.STARTED, 0, 0, 0, 0, 0, 0, 0, 0.0,
                    false, false, null, Duration.ZERO,
                    "Run " + runId + " started; its outcome is recorded in the source's run history");
        }

        static IngestionReport alreadyRunning(String sourceId) {
            return new IngestionReport(null, sourceId, Outcome.ALREADY_RUNNING, 0, 0, 0, 0, 0, 0, 0, 0.0,
                    false, false, null, Duration.ZERO,
                    "A run is already in flight for this source");
        }

        public boolean isSuccess() {
            return outcome == Outcome.COMPLETED || outcome == Outcome.PREVIEW || outcome == Outcome.STARTED;
        }
    }
}
