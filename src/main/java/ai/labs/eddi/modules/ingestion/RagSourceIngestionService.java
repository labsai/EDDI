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
import ai.labs.eddi.utils.LogSanitizer;
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

    @Inject
    public RagSourceIngestionService(IngestionPipeline pipeline, IIngestionStateStore stateStore,
            IScheduleStore scheduleStore, IRagStore ragStore) {
        this.pipeline = pipeline;
        this.stateStore = stateStore;
        this.scheduleStore = scheduleStore;
        this.ragStore = ragStore;
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
     * no {@code nextFire} at all, so after the first pass nothing matches. The row
     * is re-read immediately before it is written, so a second node arriving after
     * the first has armed a row sees the armed value and leaves it alone. Arming
     * goes through {@code setScheduleEnabled}, the existing store-agnostic re-arm,
     * rather than a new store method.
     *
     * <p>
     * That re-read narrows the window between two nodes to a single store
     * round-trip; it does not close it, because neither store offers a conditional
     * write and adding one means writing it twice, once per backend. The residual
     * race costs at most <em>one</em> occurrence of a cron that has never fired at
     * all — two nodes landing inside the same round-trip on opposite sides of a
     * cron boundary would store the later of two occurrences — on a schedule that
     * without this sweep would fire never rather than late. That is the trade this
     * sweep is deliberately making.
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
            // Re-read before writing. The listing above is a snapshot, and on a
            // cluster another node may have armed this row between the two: without
            // this check that node's occurrence would be overwritten with a later
            // one computed from a newer Instant.now(), skipping a fire. It is a
            // narrowing, not a lock — neither store offers a conditional write, and
            // adding one means writing it twice, once per backend, for a row that
            // would otherwise fire never rather than late.
            ScheduleConfiguration current = scheduleStore.readSchedule(schedule.getId());
            if (current == null || current.getNextFire() != null || !current.isEnabled()) {
                return false;
            }
            // Read the cron in the zone the POLLER will use for this row, not in
            // RagIngestionSchedules.ZONE. setScheduleEnabled writes only enabled and
            // nextFire, so a legacy row's null timeZone stays null, and every fire
            // after the first is re-armed through resolveTimeZone(null) — the
            // deployment's eddi.schedule.default-timezone. Arming in UTC regardless
            // would hand a non-UTC deployment exactly one interval of the wrong
            // length, which is the same drift this PR fixed in buildSchedule, just
            // moved onto the repair path.
            Instant nextFire = RagIngestionSchedules.firstFire(cron, pollerZoneOf(current));
            scheduleStore.setScheduleEnabled(schedule.getId(), true, nextFire);
            return true;
        } catch (IllegalArgumentException | IResourceStore.ResourceStoreException
                | IResourceStore.ResourceNotFoundException e) {
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

    /** Removes every ingestion schedule belonging to a knowledge base's sources. */
    public void removeSchedules(String ragConfigId, RagConfiguration knowledgeBase) {
        if (knowledgeBase == null || knowledgeBase.getSources() == null) {
            return;
        }
        for (IngestionSource source : knowledgeBase.getSources()) {
            deleteScheduleQuietly(ragConfigId, sourceIdOf(source));
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
