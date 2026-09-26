/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion;

import ai.labs.eddi.configs.rag.IRagStore;
import ai.labs.eddi.configs.rag.model.IngestionSource;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.runtime.internal.CronParser;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.modules.ingestion.IIngestionStateStore.IngestionRun;
import ai.labs.eddi.modules.ingestion.IngestionPipeline.IngestionReport;
import ai.labs.eddi.modules.ingestion.IngestionPipeline.Mode;
import ai.labs.eddi.modules.ingestion.files.IIngestedFileStore;
import ai.labs.eddi.utils.LogSanitizer;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.function.Consumer;

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

    /**
     * How many schedules the startup repair reads per page, and how many pages it
     * is willing to walk. Both stores page this listing deterministically (sorted
     * by {@code createdAt} then id), so the walk cannot skip or repeat a row.
     */
    static final int REPAIR_PAGE_SIZE = 500;

    static final int REPAIR_MAX_PAGES = 40;

    private final IngestionPipeline pipeline;
    private final IIngestionStateStore stateStore;
    private final IScheduleStore scheduleStore;
    private final IRagStore ragStore;
    private final IIngestedFileStore fileStore;

    /**
     * Whether the startup repair below runs. There to be turned off, not tuned —
     * see {@link #repairUnarmedSchedules()}.
     */
    @ConfigProperty(name = "eddi.rag.ingestion.schedule-repair.enabled", defaultValue = "true")
    boolean scheduleRepairEnabled = true;

    /**
     * The zone the poller reads a schedule's cron in when the row does not name
     * one. Read here so the startup repair below can compute a first fire the
     * poller will agree with — see {@link #armIfUnarmed}.
     */
    @ConfigProperty(name = "eddi.schedule.default-timezone", defaultValue = "UTC")
    String defaultTimeZone = RagIngestionSchedules.ZONE.getId();

    /**
     * The shortest interval the schedule API accepts, applied to ingestion crons
     * too. Their schedules are written straight to the store rather than through
     * that API, so without this an operator's minimum held for every schedule but
     * these.
     */
    @ConfigProperty(name = "eddi.schedule.min-interval-seconds", defaultValue = "60")
    long minIntervalSeconds = 60;

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
     * How many previews may crawl at once across this instance. Small on purpose:
     * each one holds a request thread and sends traffic to somebody else's site.
     */
    private static final int MAX_CONCURRENT_PREVIEWS = 3;

    private final Semaphore previewSlots = new Semaphore(MAX_CONCURRENT_PREVIEWS);

    /**
     * The runs this instance's workers are carrying, by run id, so a shutdown can
     * close them instead of leaving each {@code RUNNING} until it is reaped.
     */
    private final Map<String, InFlight> inFlight = new ConcurrentHashMap<>();

    private record InFlight(String sourceKey, Thread worker) {
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
    public Optional<String> runAsync(String ragConfigId, RagConfiguration knowledgeBase, IngestionSource source) {
        return runAsync(ragConfigId, knowledgeBase, source, report -> {
        });
    }

    /**
     * As {@link #runAsync(String, RagConfiguration, IngestionSource)}, telling
     * {@code onFinished} what the run did once it is over.
     */
    public Optional<String> runAsync(String ragConfigId, RagConfiguration knowledgeBase, IngestionSource source,
                                     Consumer<IngestionReport> onFinished) {
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
            startWorker(ragConfigId, knowledgeBase, source, sourceKey, runId, onFinished);
        } catch (RuntimeException e) {
            // The reservation is claimed but nothing will work on it, and a claimed run
            // blocks the source until it is reaped.
            pipeline.abandonReservation(ragConfigId, source, runId, "the ingestion worker could not be started");
            throw e;
        }
        return Optional.of(runId);
    }

    private void startWorker(String ragConfigId, RagConfiguration knowledgeBase, IngestionSource source,
                             String sourceKey, String runId, Consumer<IngestionReport> onFinished) {

        Thread worker = Thread.ofVirtual().name("rag-ingestion-" + sourceKey).unstarted(() -> {
            IngestionReport report = null;
            try {
                report = pipeline.run(ragConfigId, knowledgeBase, source, Mode.INGEST, runId);
                LOGGER.infof("Ingestion of source '%s' finished: %s, %d ingested, %d unchanged, %d tombstoned",
                        LogSanitizer.sanitize(source.getName()), report.outcome(),
                        report.documentsIngested(), report.documentsUnchanged(), report.documentsTombstoned());
            } catch (Throwable t) {
                LOGGER.errorf(t, "Ingestion of source '%s' threw", LogSanitizer.sanitize(source.getName()));
                report = IngestionReport.failed(runId, source.effectiveId(), "The run threw: " + t);
            } finally {
                inFlight.remove(runId);
                try {
                    cleanUpAfterRun(ragConfigId, knowledgeBase, source);
                } catch (RuntimeException e) {
                    // Its own guard: settling a source that changed under the run touches
                    // the state store, and a failure there used to skip the report below
                    // — so a scheduled run that failed left no FAILED entry in the fire
                    // log, only a stack trace from a dead virtual thread.
                    LOGGER.warnf(e, "Could not settle source '%s' after ingestion run %s",
                            LogSanitizer.sanitize(source.getName()), LogSanitizer.sanitize(runId));
                }
                try {
                    onFinished.accept(report);
                } catch (RuntimeException e) {
                    LOGGER.warnf(e, "Could not report the outcome of ingestion run %s", LogSanitizer.sanitize(runId));
                }
            }
        });
        inFlight.put(runId, new InFlight(sourceKey, worker));
        try {
            worker.start();
        } catch (RuntimeException e) {
            inFlight.remove(runId);
            throw e;
        }
    }

    /**
     * Closes the runs this instance was carrying when it shuts down.
     *
     * <p>
     * A worker is a virtual thread that simply stops with the JVM, leaving its run
     * {@code RUNNING} until the next claim reaps it — its time budget plus a
     * quarter of an hour, so by default twenty-five minutes in which "Run now"
     * answers 409 and every scheduled fire reports the run as already going. On a
     * rolling restart that was every crawl in flight. Each is closed here as
     * {@code CANCELLED}, and its worker interrupted; a worker that wakes up after
     * this finds its run no longer active and stops before its next embedding.
     */
    void onShutdown(@Observes ShutdownEvent event) {
        cancelInFlightRuns();
    }

    int cancelInFlightRuns() {
        int cancelled = 0;
        for (var run : Map.copyOf(inFlight).entrySet()) {
            try {
                stateStore.finishRun(new IngestionRun(run.getKey(), run.getValue().sourceKey(),
                        IngestionRun.Status.CANCELLED, null, Instant.now(), 0, 0, 0, 0, 0, 0, 0.0,
                        "Stopped because the server shut down"));
                cancelled++;
            } catch (RuntimeException e) {
                LOGGER.warnf(e, "Could not close ingestion run %s at shutdown; it will be reaped",
                        LogSanitizer.sanitize(run.getKey()));
            }
            run.getValue().worker().interrupt();
        }
        if (cancelled > 0) {
            LOGGER.infof("Closed %d ingestion run(s) that were in flight at shutdown", cancelled);
        }
        return cancelled;
    }

    /**
     * Takes back what a run wrote for a source that changed under it.
     *
     * <p>
     * A run holds the configuration it started with. If the source was removed
     * while it ran, the save removed what the source had put into the knowledge
     * base — and then the run, which stops only at its next check, embedded a
     * document or two more under a source nobody lists any more. If the knowledge
     * base was renamed, the save cleared the source's state so the next run would
     * fill the new store, and then the run recorded documents it had just written
     * into the old one, so the next run found them "unchanged" and never embedded
     * them where retrieval now looks. Both are settled here, once the run is over
     * and cannot add more.
     */
    void cleanUpAfterRun(String ragConfigId, RagConfiguration ranWith, IngestionSource source) {
        RagConfiguration current;
        try {
            current = currentKnowledgeBase(ragConfigId).orElse(null);
        } catch (RuntimeException e) {
            LOGGER.warnf(e, "Could not re-read knowledge base %s after a run of source '%s'; if the source was "
                    + "removed while it ran, what it wrote in the meantime stays",
                    LogSanitizer.sanitize(ragConfigId), LogSanitizer.sanitize(source.getName()));
            return;
        }
        IngestionSource now = current == null ? null : current.findSource(sourceIdOf(source));
        if (now == null || now.isUpload() != source.isUpload()) {
            LOGGER.warnf("Source '%s' of knowledge base %s was removed while it was running; removing what the "
                    + "run wrote after that", LogSanitizer.sanitize(source.getName()),
                    LogSanitizer.sanitize(ragConfigId));
            discardSourceContent(ragConfigId, ranWith, source);
            return;
        }
        if (ranWith.getName() != null && !ranWith.getName().equals(current.getName())) {
            // Under the run claim, so a run that has started since is not purged from
            // under it; it would find nothing stale anyway, having started after the
            // rename.
            purge(ragConfigId, source);
        }
    }

    /**
     * The knowledge base as it is now, or empty when no version of it is left.
     *
     * @throws IllegalStateException
     *             when the store cannot say — which must not be mistaken for
     *             "gone", since the answer to "gone" is deleting what the source
     *             ingested
     */
    private Optional<RagConfiguration> currentKnowledgeBase(String ragConfigId) {
        IResourceStore.IResourceId currentId;
        try {
            currentId = ragStore.getCurrentResourceId(ragConfigId);
        } catch (IResourceStore.ResourceNotFoundException e) {
            return Optional.empty();
        }
        if (currentId == null || currentId.getVersion() == null) {
            throw new IllegalStateException("The store named no current version of knowledge base " + ragConfigId);
        }
        try {
            return Optional.of(ragStore.read(ragConfigId, currentId.getVersion()));
        } catch (IResourceStore.ResourceNotFoundException e) {
            return Optional.empty();
        } catch (IResourceStore.ResourceStoreException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
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
     *
     * <p>
     * Done under the source's run claim. Checking for a run first and purging
     * afterwards let a run start in between; the purge then deleted its
     * {@code RUNNING} row — the one thing stopping a second run — and the worker,
     * still going, wrote its state back over the purge.
     *
     * @return false when a run holds the source, and nothing was purged
     */
    public boolean purge(String ragConfigId, IngestionSource source) {
        Optional<String> claim = pipeline.claimForMaintenance(ragConfigId, source);
        if (claim.isEmpty()) {
            return false;
        }
        try {
            // Takes the claim's own row with it, which is what releases the claim.
            stateStore.purgeSource(IngestionPipeline.stateKey(ragConfigId, source));
        } catch (RuntimeException e) {
            pipeline.releaseClaim(ragConfigId, source, claim.get());
            throw e;
        }
        return true;
    }

    /**
     * Forgets what a source has ingested because its knowledge base was renamed,
     * whether or not a run is in flight.
     *
     * <p>
     * Not {@link #purge}: a rename cannot wait for a run to end, and the state has
     * to go either way, or the next run finds every document "unchanged" and never
     * fills the store the new name addresses. Purging takes the running row with
     * it, so a run in flight stops before its next embedding, and what it recorded
     * in between is cleared again once it has stopped — see
     * {@link #cleanUpAfterRun}.
     */
    public void forgetStateAfterRename(String ragConfigId, IngestionSource source) {
        stateStore.purgeSource(IngestionPipeline.stateKey(ragConfigId, source));
    }

    /**
     * Fired by the scheduler. Loads the source from its knowledge base, claims a
     * run and starts it on its own worker, then returns.
     *
     * <p>
     * It used to run the crawl on the scheduler's thread. The scheduler waits at
     * most its lease (five minutes by default) for a fire and then cancels it with
     * an interrupt, while a crawl's default budget is ten: every scheduled crawl of
     * any size was interrupted mid-run, stopped as {@code CANCELLED}, and never
     * reconciled a deletion — and an interrupt landing in a database call left its
     * run {@code RUNNING} until it was reaped. A run owns its time budget and its
     * one-per-source claim already; the fire only has to start it. Its outcome is
     * in the source's run history, like a manual run's.
     *
     * @return {@code STARTED}, or {@code ALREADY_RUNNING} when a run holds the
     *         source, or {@code FAILED}/{@code SKIPPED} when there is nothing to
     *         run
     */
    public IngestionReport processScheduledFire(String ragConfigId, Integer version, String sourceId) {
        return processScheduledFire(ragConfigId, version, sourceId, report -> {
        });
    }

    /**
     * As above, telling {@code onFinished} what the started run did once it is over
     * — how the fire log learns that a crawl it started failed.
     */
    public IngestionReport processScheduledFire(String ragConfigId, Integer version, String sourceId,
                                                Consumer<IngestionReport> onFinished) {
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
        if (!source.isEnabled()) {
            return IngestionReport.skipped(source.effectiveId(), "Source is disabled");
        }
        try {
            source.validate();
        } catch (IllegalArgumentException e) {
            return IngestionReport.failed(null, source.effectiveId(), e.getMessage());
        }
        return runAsync(ragConfigId, knowledgeBase, source, onFinished)
                .map(runId -> IngestionReport.started(runId, source.effectiveId()))
                .orElseGet(() -> IngestionReport.alreadyRunning(source.effectiveId()));
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
                String tooFrequent = intervalRefusal(source);
                if (tooFrequent != null) {
                    // Reached only by a writer that skipped requireAllowedIntervals —
                    // an import. Its other sources still get their schedules.
                    LOGGER.errorf("Ingestion source '%s' of knowledge base %s was NOT scheduled: %s",
                            LogSanitizer.sanitize(source.getName()), LogSanitizer.sanitize(ragConfigId),
                            tooFrequent);
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

    /**
     * Refuses a cron that fires more often than the deployment allows
     * ({@code eddi.schedule.min-interval-seconds}), at the write boundary where the
     * operator is waiting for an answer.
     *
     * @throws IllegalArgumentException
     *             naming the source, for the caller to turn into a 400
     */
    public void requireAllowedIntervals(RagConfiguration knowledgeBase) {
        if (knowledgeBase == null || knowledgeBase.getSources() == null) {
            return;
        }
        for (IngestionSource source : knowledgeBase.getSources()) {
            if (source == null || source.getCron() == null || source.getCron().isBlank()) {
                continue;
            }
            String refusal = intervalRefusal(source);
            if (refusal != null) {
                throw new IllegalArgumentException("Ingestion source '" + source.getName() + "': " + refusal);
            }
        }
    }

    /** Why this source's cron is too frequent, or null when it is not. */
    private String intervalRefusal(IngestionSource source) {
        long interval;
        try {
            interval = CronParser.computeMinIntervalSeconds(source.getCron(), RagIngestionSchedules.ZONE);
        } catch (RuntimeException e) {
            // An unparseable cron is requireValidCrons' to report, with its own message.
            return null;
        }
        if (interval < minIntervalSeconds) {
            return "its cron fires every " + interval + "s, more often than the minimum of " + minIntervalSeconds
                    + "s this deployment allows (eddi.schedule.min-interval-seconds)";
        }
        return null;
    }

    void onStartup(@Observes StartupEvent event) {
        repairUnarmedSchedules();
    }

    /**
     * Arms the ingestion schedules that were stored before their creator computed a
     * {@code nextFire}.
     *
     * <p>
     * Fixing {@code buildSchedule} only helps schedules written after the fix. The
     * rows already in the database read back {@code enabled=true} with a null
     * {@code nextFire}, which no poll can ever match, so without this an operator's
     * nightly crawl stays dead until somebody happens to re-save the knowledge base
     * — and nothing tells them to.
     *
     * <p>
     * Safe to run on every boot and on every node of a cluster: it only touches
     * rows that are enabled, marked as ingestion schedules, carry a cron and have
     * no {@code nextFire} at all, so after the first pass nothing matches.
     *
     * <p>
     * Two nodes do <em>not</em> compute the same occurrence: each uses its own
     * {@code Instant.now()}, so across a cron boundary one computes 10:01 and the
     * other 10:02, and an unconditional write let the slower node replace the
     * earlier fire with the later one — the schedule skips an occurrence. Arming
     * therefore goes through {@link IScheduleStore#armIfUnarmed}, which carries the
     * "still unarmed" condition in the write predicate itself. That is the only
     * place the nodes meet, so the first writer wins and every other one is a
     * no-op; a re-read before writing would only have narrowed the window, not
     * closed it.
     *
     * <p>
     * Failures are logged, never thrown: a repair that cannot read the store must
     * not stop the application from starting.
     *
     * @return what the sweep did, and whether it reached the end of the data —
     *         returned rather than only logged so a test can tell a finished sweep
     *         from a truncated one without reading log output
     */
    RepairResult repairUnarmedSchedules() {
        if (!scheduleRepairEnabled) {
            return new RepairResult(0, true);
        }
        int repaired = 0;
        boolean walkedEverything = false;
        try {
            for (int page = 0; page < REPAIR_MAX_PAGES; page++) {
                List<ScheduleConfiguration> batch = scheduleStore.readAllSchedules(REPAIR_PAGE_SIZE, page * REPAIR_PAGE_SIZE, true);
                if (batch == null || batch.isEmpty()) {
                    walkedEverything = true;
                    break;
                }
                for (ScheduleConfiguration schedule : batch) {
                    if (armIfUnarmed(schedule)) {
                        repaired++;
                    }
                }
                if (batch.size() < REPAIR_PAGE_SIZE) {
                    walkedEverything = true;
                    break;
                }
            }
        } catch (Exception e) {
            LOGGER.errorf(e, "Could not check stored ingestion schedules for a missing next fire time — "
                    + "any that were stored unarmed will not run until their knowledge base is saved again");
            return new RepairResult(repaired, false);
        }
        if (repaired > 0) {
            LOGGER.warnf("Armed %d ingestion schedule(s) that had been stored without a next fire time and "
                    + "could never have run", repaired);
        }
        if (!walkedEverything) {
            // Say so. A cap that truncates silently is worse than no cap: the
            // operator reads "armed 12 schedules" and has no way to tell a finished
            // repair from one that stopped a page short of the row they are waiting
            // on.
            LOGGER.warnf("Stopped checking stored schedules for a missing next fire time after %d rows (the "
                    + "startup sweep's own bound, not the end of the data). Any ingestion schedule beyond "
                    + "that point that was stored unarmed is still unarmed and will not run until its "
                    + "knowledge base is saved again. Re-run with a larger bound, or re-save the affected "
                    + "knowledge bases.", REPAIR_MAX_PAGES * REPAIR_PAGE_SIZE);
        }
        return new RepairResult(repaired, walkedEverything);
    }

    /**
     * What one startup sweep did.
     *
     * @param armed
     *            how many unarmed ingestion schedules were given a fire time
     * @param complete
     *            whether the walk reached the end of the data. {@code false} means
     *            it stopped at its own page bound (or on a store failure) and rows
     *            beyond that point were never examined — the difference between
     *            "there was nothing left to repair" and "we stopped looking", which
     *            a count alone cannot express
     */
    record RepairResult(int armed, boolean complete) {
    }

    /** @return whether this schedule was one of the broken ones, and was armed */
    private boolean armIfUnarmed(ScheduleConfiguration schedule) {
        if (schedule == null || schedule.getId() == null
                || !RagIngestionSchedules.isIngestionSchedule(schedule.getMetadata())
                || !schedule.isEnabled() || schedule.getNextFire() != null) {
            return false;
        }
        String cron = schedule.getCronExpression();
        if (cron == null || cron.isBlank()) {
            // No cron and no nextFire is a schedule that was never meant to fire on
            // its own; inventing a time for it would start crawling a third party
            // on a cadence nobody configured.
            return false;
        }
        try {
            // Read the cron in the zone the POLLER will use for this row, not in
            // RagIngestionSchedules.ZONE. armIfUnarmed writes only nextFire, so a
            // legacy row's null timeZone stays null, and every fire after the first
            // is re-armed through resolveTimeZone(null) — the deployment's
            // eddi.schedule.default-timezone. Arming in UTC regardless would hand a
            // non-UTC deployment exactly one interval of the wrong length, which is
            // the drift buildSchedule's setTimeZone fixed, moved onto the repair
            // path. The zone is read from the listed row rather than re-read: it is
            // the same field the poller resolves, and unlike nextFire nothing races
            // to change it.
            Instant nextFire = RagIngestionSchedules.firstFire(cron, pollerZoneOf(schedule));
            // No re-read first: the store's own predicate carries the "still unarmed"
            // condition, so a second round-trip would only narrow a window this write
            // already closes — and would still be reading a snapshot.
            //
            // A lost race is a success: another node armed the row a moment ago, so it
            // is armed. Only the count of rows THIS node repaired is affected, and that
            // number is a log line, not a decision.
            return scheduleStore.armIfUnarmed(schedule.getId(), nextFire);
        } catch (IllegalArgumentException | IResourceStore.ResourceStoreException e) {
            // One unrepairable row must not stop the sweep: the next one may be the
            // schedule somebody is waiting on.
            LOGGER.errorf(e, "Ingestion schedule %s has no next fire time and could not be given one — "
                    + "it will not run", LogSanitizer.sanitize(schedule.getId()));
            return false;
        }
    }

    /**
     * The zone {@code SchedulePollerService.resolveTimeZone} will read this
     * schedule's cron in: its own, when it names one, and otherwise the
     * deployment's default. Same fallback, same invalid-zone tolerance — a row
     * naming a zone the JDK does not know is re-armed by the poller in the default,
     * so arming it here in the default is what keeps the two agreeing.
     */
    private ZoneId pollerZoneOf(ScheduleConfiguration schedule) {
        String zone = schedule.getTimeZone();
        if (zone != null && !zone.isBlank()) {
            try {
                return ZoneId.of(zone);
            } catch (Exception e) {
                LOGGER.warnf("Ingestion schedule %s names time zone '%s', which is not a zone; arming it in %s, "
                        + "the same fallback the poller uses", LogSanitizer.sanitize(schedule.getId()),
                        LogSanitizer.sanitize(zone), defaultTimeZone);
            }
        }
        try {
            return ZoneId.of(defaultTimeZone);
        } catch (Exception e) {
            return RagIngestionSchedules.ZONE;
        }
    }

    /**
     * The id a source is addressed and keyed by.
     *
     * <p>
     * Delegates rather than repeating the rule: ingestion state, the schedule name
     * and the run reports all have to agree on it, and two copies of "id, or name
     * when it has none" is how they stop agreeing.
     */
    public static String sourceIdOf(IngestionSource source) {
        return source.effectiveId();
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
     * everything its sources put into it — called once the knowledge base itself
     * has no readable version left.
     */
    public void removeSchedules(String ragConfigId, RagConfiguration knowledgeBase) {
        if (knowledgeBase == null || knowledgeBase.getSources() == null) {
            return;
        }
        for (IngestionSource source : knowledgeBase.getSources()) {
            deleteScheduleQuietly(ragConfigId, sourceIdOf(source));
            discardSourceContent(ragConfigId, knowledgeBase, source);
        }
    }

    /**
     * Takes back everything sources that are no longer there put into the knowledge
     * base.
     *
     * <p>
     * Two ways a source stops owning its documents: it is removed from
     * {@code sources[]}, or it stays but changes type — a file source becoming a
     * crawl, or the reverse. An upload source used to lose its files and leave
     * every vector they produced in the knowledge base, and a web source was
     * skipped altogether on the theory that its documents "come back on the next
     * run" — but a removed source has no next run. Either way the chunks stayed
     * retrievable by every agent and unreachable by every endpoint, because the
     * source they belong to was gone: an operator who removes "HR policies 2023",
     * or the crawl of a site that should never have been ingested, had agents keep
     * citing it with no way to list or delete what was left.
     *
     * <p>
     * The <em>previous</em> configuration is what says where those vectors are: the
     * store is addressed by the knowledge base's name, and that name may be part of
     * what just changed.
     *
     * <p>
     * A run in flight for the source is not waited for. Removing the source's state
     * takes its running row, so the run stops before its next embedding, and what
     * it wrote in between is removed once it has stopped — see
     * {@link #cleanUpAfterRun}.
     */
    public void discardRemovedSources(String ragConfigId, RagConfiguration previous, RagConfiguration updated) {
        if (previous == null || previous.getSources() == null) {
            return;
        }
        for (IngestionSource source : previous.getSources()) {
            if (source == null) {
                continue;
            }
            IngestionSource now = updated == null ? null : updated.findSource(sourceIdOf(source));
            if (now != null && now.isUpload() == source.isUpload()) {
                continue;
            }
            LOGGER.warnf("Source '%s' of knowledge base %s %s. Everything the knowledge base learned from it%s "
                    + "is being removed.", LogSanitizer.sanitize(source.getName()),
                    LogSanitizer.sanitize(ragConfigId),
                    now == null ? "was removed" : "changed type",
                    source.isUpload() ? ", and its uploaded files," : "");
            discardSourceContent(ragConfigId, previous, source);
        }
    }

    /**
     * Vectors and ingestion state first, then — for a file source — the files that
     * produced them.
     */
    private void discardSourceContent(String ragConfigId, RagConfiguration knowledgeBase, IngestionSource source) {
        try {
            pipeline.forgetSource(ragConfigId, knowledgeBase, source);
        } catch (RuntimeException e) {
            LOGGER.errorf(e, "Could not remove what source %s put into knowledge base %s; its chunks stay "
                    + "retrievable", LogSanitizer.sanitize(source.getName()), LogSanitizer.sanitize(ragConfigId));
        }
        if (!source.isUpload()) {
            return;
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
        schedule.setTimeZone(RagIngestionSchedules.ZONE.getId());
        schedule.setEnabled(true);
        schedule.setUserId(SCHEDULE_USER_ID);
        // Armed here, exactly as every other creator that writes to the store
        // directly does (RestGroupWorkspace, ConversationHitlService,
        // GroupHitlCoordinator, HitlCrashRecoveryObserver). Neither store's
        // createSchedule computes one, and findDueSchedules never matches a null
        // nextFire, so an unarmed row is stored looking enabled and never fires.
        schedule.setNextFire(RagIngestionSchedules.firstFire(source.getCron()));
        schedule.setMetadata(RagIngestionSchedules.metadata(ragConfigId, version, sourceId));
        return schedule;
    }
}
