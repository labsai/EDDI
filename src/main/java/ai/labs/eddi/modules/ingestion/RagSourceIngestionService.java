/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion;

import ai.labs.eddi.configs.rag.IRagStore;
import ai.labs.eddi.configs.rag.model.IngestionSource;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.modules.ingestion.IIngestionStateStore.IngestionRun;
import ai.labs.eddi.modules.ingestion.IngestionPipeline.IngestionReport;
import ai.labs.eddi.modules.ingestion.IngestionPipeline.Mode;
import ai.labs.eddi.modules.ingestion.files.IIngestedFileStore;
import ai.labs.eddi.utils.LogSanitizer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Optional;
import java.util.concurrent.Semaphore;

/**
 * Runs a knowledge base's ingestion sources, on demand or on a cron, and keeps
 * their schedules in step with their configuration.
 *
 * <p>
 * A thin layer over {@link IngestionPipeline}: it decides <em>when</em> and
 * <em>on which thread</em>, never <em>what</em>.
 */
@ApplicationScoped
public class RagSourceIngestionService {

    private static final Logger LOGGER = Logger.getLogger(RagSourceIngestionService.class);

    /** Ceiling on how long a preview may block its caller. */
    private static final int PREVIEW_TIME_BUDGET_MINUTES = 2;

    /** Scheduled runs are not a user's action. */
    private static final String SCHEDULE_USER_ID = "system:scheduler";

    private final IngestionPipeline pipeline;
    private final IIngestionStateStore stateStore;
    private final IScheduleStore scheduleStore;
    private final IRagStore ragStore;
    private final IIngestedFileStore fileStore;

    @Inject
    public RagSourceIngestionService(IngestionPipeline pipeline, IIngestionStateStore stateStore,
            IScheduleStore scheduleStore, IRagStore ragStore, IIngestedFileStore fileStore) {
        this.pipeline = pipeline;
        this.stateStore = stateStore;
        this.scheduleStore = scheduleStore;
        this.ragStore = ragStore;
        this.fileStore = fileStore;
    }

    /**
     * Starts a run on a virtual thread and returns immediately.
     *
     * <p>
     * A crawl takes minutes; holding an HTTP request open for it would tie up a
     * worker and time out anyway. Progress is followed through {@link #listRuns}.
     *
     * @return the reserved run id, or empty when a run is already in flight for
     *         this source
     */
    /**
     * How many previews may crawl at once across this instance. Small on purpose:
     * each one holds a request thread and sends traffic to somebody else's site.
     */
    private static final int MAX_CONCURRENT_PREVIEWS = 3;

    private final Semaphore previewSlots = new Semaphore(MAX_CONCURRENT_PREVIEWS);

    public Optional<String> runAsync(String ragConfigId, RagConfiguration knowledgeBase, IngestionSource source) {
        String sourceKey = IngestionPipeline.stateKey(ragConfigId, source);
        // Reserved here, not inside the worker: two requests arriving together both
        // used to be answered "started", and whichever worker lost the claim crawled
        // nothing while its caller believed a run had begun.
        Optional<String> reserved = pipeline.reserveRun(ragConfigId, source);
        if (reserved.isEmpty()) {
            return Optional.empty();
        }
        String runId = reserved.get();

        // One virtual thread per run, as the existing RagIngestionService does for
        // single-document ingestion. Crawls block by design — on the fetch and on the
        // politeness delay — so they must never run on a shared pool.
        // One virtual thread, started directly: an ExecutorService per call was never
        // closed. Throwable rather than RuntimeException so an Error is logged instead
        // of disappearing into a dead thread.
        try {
            startWorker(ragConfigId, knowledgeBase, source, sourceKey, runId);
        } catch (RuntimeException e) {
            // The reservation is claimed but nothing will work on it, and a claimed run
            // blocks the source until it is reaped.
            pipeline.abandonReservation(ragConfigId, source, runId, "the ingestion worker could not be started");
            throw e;
        }
        return Optional.of(runId);
    }

    private void startWorker(String ragConfigId, RagConfiguration knowledgeBase, IngestionSource source,
                             String sourceKey, String runId) {

        Thread.ofVirtual().name("rag-ingestion-" + sourceKey).start(() -> {
            try {
                IngestionReport report = pipeline.run(ragConfigId, knowledgeBase, source, Mode.INGEST, runId);
                LOGGER.infof("Ingestion of source '%s' finished: %s, %d ingested, %d unchanged, %d tombstoned",
                        LogSanitizer.sanitize(source.getName()), report.outcome(),
                        report.documentsIngested(), report.documentsUnchanged(), report.documentsTombstoned());
            } catch (Throwable t) {
                LOGGER.errorf(t, "Ingestion of source '%s' threw", LogSanitizer.sanitize(source.getName()));
            }
        });
    }

    /** The run in flight for this source, if there is one. */
    public Optional<IIngestionStateStore.IngestionRun> activeRun(String ragConfigId, IngestionSource source) {
        return stateStore.activeRun(IngestionPipeline.stateKey(ragConfigId, source));
    }

    /**
     * Crawls and reports what would change, embedding and recording nothing.
     *
     * <p>
     * Deliberately capped well below the source's own limits. A preview blocks the
     * caller for the length of the crawl, and an uncapped one would hold a request
     * thread for up to the source time budget (a day, at the maximum) while sending
     * that much traffic to a third party — for every click, since nothing stops
     * previews running concurrently.
     */
    public IngestionReport preview(String ragConfigId, RagConfiguration knowledgeBase, IngestionSource source) {
        // A preview blocks a request thread for the length of a crawl, and nothing
        // stops an operator (or a script) starting them faster than they finish.
        // Bounded so a handful of previews cannot take the request pool with them;
        // a run is bounded instead by its own one-per-source claim.
        if (!previewSlots.tryAcquire()) {
            throw new PreviewBusyException(
                    "Too many previews are running. Try again in a moment, or run the source instead.");
        }
        try {
            return pipeline.run(ragConfigId, knowledgeBase, cappedForPreview(source), Mode.PREVIEW);
        } finally {
            previewSlots.release();
        }
    }

    /** A copy of the source with limits an operator can wait for. */
    private static IngestionSource cappedForPreview(IngestionSource source) {
        var capped = new IngestionSource();
        capped.setId(source.getId());
        capped.setName(source.getName());
        capped.setEnabled(true);
        capped.setType(source.getType());
        capped.setWeb(source.getWeb());
        capped.setUpload(source.getUpload());
        capped.setCron(null);

        var settings = new IngestionSource.IngestionSettings();
        var original = source.settings();
        settings.setMaxContentLength(original.getMaxContentLength());
        settings.setMaxBytesPerPage(original.getMaxBytesPerPage());
        settings.setTombstoneAfterMissedRuns(original.getTombstoneAfterMissedRuns());
        settings.setMaxSegmentsPerRun(original.getMaxSegmentsPerRun());
        settings.setTimeBudgetMinutes(Math.min(original.timeBudgetMinutesOrDefault(), PREVIEW_TIME_BUDGET_MINUTES));
        capped.setSettings(settings);
        return capped;
    }

    /** Run history for a source, newest first. */
    public List<IngestionRun> listRuns(String ragConfigId, IngestionSource source, int limit) {
        return stateStore.listRuns(IngestionPipeline.stateKey(ragConfigId, source), limit);
    }

    /**
     * Forgets everything ingested from a source. The caller is expected to have
     * checked EDIT access: this discards content every agent using the knowledge
     * base retrieves from.
     */
    public void purge(String ragConfigId, IngestionSource source) {
        stateStore.purgeSource(IngestionPipeline.stateKey(ragConfigId, source));
    }

    /**
     * Fired by the scheduler. Loads the source from its knowledge base and runs it
     * synchronously — the schedule machinery already owns the thread, the lease and
     * the retry.
     */
    public IngestionReport processScheduledFire(String ragConfigId, Integer version, String sourceId) {
        RagConfiguration knowledgeBase;
        try {
            knowledgeBase = ragStore.read(ragConfigId, version == null ? 1 : version);
        } catch (IResourceStore.ResourceStoreException | IResourceStore.ResourceNotFoundException e) {
            // The knowledge base was deleted while its schedule was in flight.
            return IngestionReport.failed(null, sourceId,
                    "Knowledge base " + ragConfigId + " v" + version + " could not be read: " + e.getMessage());
        }

        IngestionSource source = knowledgeBase.findSource(sourceId);
        if (source == null) {
            // The source was deleted while its schedule was in flight. Reported as a
            // failure so the fire log says what happened rather than silently
            // succeeding.
            return IngestionReport.failed(null, sourceId,
                    "Source " + sourceId + " no longer exists on this knowledge base");
        }
        return pipeline.run(ragConfigId, knowledgeBase, source, Mode.INGEST);
    }

    /**
     * Makes the stored schedules match the knowledge base's sources.
     *
     * <p>
     * Delete-by-name then create, per source with a cron. Deterministic names make
     * this an upsert with no scan and no orphans — see
     * {@link RagIngestionSchedules} for what the alternative cost the draft.
     */
    public void syncSchedules(String ragConfigId, Integer version, RagConfiguration knowledgeBase,
                              Collection<String> previousSourceIds) {

        // Sources that existed before and do not now must lose their schedules.
        // Walking only the new document left the removed source's schedule in place,
        // still naming the old version, still crawling a third party on a cron with
        // nothing in the configuration to show for it.
        Set<String> currentIds = new HashSet<>();
        if (knowledgeBase.getSources() != null) {
            for (IngestionSource source : knowledgeBase.getSources()) {
                currentIds.add(sourceIdOf(source));
            }
        }
        if (previousSourceIds != null) {
            for (String previousId : previousSourceIds) {
                if (previousId != null && !currentIds.contains(previousId)) {
                    deleteScheduleQuietly(ragConfigId, previousId);
                }
            }
        }

        if (knowledgeBase.getSources() == null) {
            return;
        }
        for (IngestionSource source : knowledgeBase.getSources()) {
            String sourceId = sourceIdOf(source);
            String name = RagIngestionSchedules.scheduleName(ragConfigId, sourceId);
            try {
                scheduleStore.deleteSchedulesByName(name);
                if (source.getCron() == null || source.getCron().isBlank() || !source.isEnabled()) {
                    continue;
                }
                scheduleStore.createSchedule(buildSchedule(ragConfigId, version, sourceId, name, source));
            } catch (IResourceStore.ResourceStoreException e) {
                // Surfaced, never swallowed: the draft returned 201 on create while its
                // schedule creation had failed, leaving a source that looked scheduled
                // and never ran.
                throw new IllegalStateException(
                        "Could not synchronise the ingestion schedule for source '" + source.getName() + "'", e);
            }
        }
    }

    /** The id a source is addressed and keyed by. */
    public static String sourceIdOf(IngestionSource source) {
        return source.getId() == null || source.getId().isBlank() ? source.getName() : source.getId();
    }

    private void deleteScheduleQuietly(String ragConfigId, String sourceId) {
        try {
            scheduleStore.deleteSchedulesByName(RagIngestionSchedules.scheduleName(ragConfigId, sourceId));
        } catch (IResourceStore.ResourceStoreException e) {
            LOGGER.errorf(e, "Could not remove the ingestion schedule for a removed source of knowledge base %s — "
                    + "it may keep crawling", LogSanitizer.sanitize(ragConfigId));
        }
    }

    /**
     * Removes every ingestion schedule belonging to a knowledge base's sources, and
     * everything its upload sources put into it — called once the knowledge base
     * itself has no readable version left.
     */
    public void removeSchedules(String ragConfigId, RagConfiguration knowledgeBase) {
        if (knowledgeBase == null || knowledgeBase.getSources() == null) {
            return;
        }
        for (IngestionSource source : knowledgeBase.getSources()) {
            deleteScheduleQuietly(ragConfigId, sourceIdOf(source));
            if (source.isUpload()) {
                discardSourceContent(ragConfigId, knowledgeBase, source);
            }
        }
    }

    /**
     * Takes back everything sources that are no longer there put into the knowledge
     * base.
     *
     * <p>
     * Two ways a source stops owning its documents: it is removed from
     * {@code sources[]}, or it stays but stops being an upload source. Both used to
     * delete the files and leave every vector they produced in the knowledge base —
     * retrievable by every agent, and unreachable by every endpoint, because the
     * source they belong to is gone. An operator who removes "HR policies 2023"
     * would have had agents keep citing it with no way to list or delete what was
     * left.
     *
     * <p>
     * The <em>previous</em> configuration is what says where those vectors are: the
     * store is addressed by the knowledge base's name, and that name may be part of
     * what just changed.
     */
    public void discardRemovedSources(String ragConfigId, RagConfiguration previous, RagConfiguration updated) {
        if (previous == null || previous.getSources() == null) {
            return;
        }
        for (IngestionSource source : previous.getSources()) {
            if (source == null || !source.isUpload()) {
                // Only upload sources hold anything of their own. A crawl's documents
                // come back on the next run against the same site.
                continue;
            }
            IngestionSource now = updated == null ? null : updated.findSource(sourceIdOf(source));
            if (now != null && now.isUpload()) {
                continue;
            }
            LOGGER.warnf("Source '%s' of knowledge base %s %s. Its uploaded files and everything the knowledge "
                    + "base learned from them are being removed.", LogSanitizer.sanitize(source.getName()),
                    LogSanitizer.sanitize(ragConfigId),
                    now == null ? "was removed" : "is no longer a file source");
            discardSourceContent(ragConfigId, previous, source);
        }
    }

    /** Vectors and ingestion state first, then the files that produced them. */
    private void discardSourceContent(String ragConfigId, RagConfiguration knowledgeBase, IngestionSource source) {
        try {
            pipeline.forgetSource(ragConfigId, knowledgeBase, source);
        } catch (RuntimeException e) {
            LOGGER.errorf(e, "Could not remove what source %s put into knowledge base %s; its chunks stay "
                    + "retrievable", LogSanitizer.sanitize(source.getName()), LogSanitizer.sanitize(ragConfigId));
        }
        try {
            long deleted = fileStore.deleteAll(IngestionPipeline.stateKey(ragConfigId, source));
            if (deleted > 0) {
                LOGGER.infof("Deleted %d uploaded file(s) of source %s", deleted,
                        LogSanitizer.sanitize(source.getName()));
            }
        } catch (RuntimeException e) {
            LOGGER.errorf(e, "Could not delete the uploaded files of source %s of knowledge base %s",
                    LogSanitizer.sanitize(source.getName()), LogSanitizer.sanitize(ragConfigId));
        }
    }

    private static ScheduleConfiguration buildSchedule(String ragConfigId, Integer version, String sourceId,
                                                       String name, IngestionSource source) {

        var schedule = new ScheduleConfiguration();
        schedule.setName(name);
        schedule.setTriggerType(ScheduleConfiguration.TriggerType.CRON);
        schedule.setCronExpression(source.getCron());
        schedule.setEnabled(true);
        schedule.setUserId(SCHEDULE_USER_ID);
        schedule.setMetadata(RagIngestionSchedules.metadata(ragConfigId, version, sourceId));
        return schedule;
    }
}
