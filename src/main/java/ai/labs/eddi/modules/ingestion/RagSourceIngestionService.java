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
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executors;

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

    /** Scheduled runs are not a user's action. */
    private static final String SCHEDULE_USER_ID = "system:scheduler";

    private final IngestionPipeline pipeline;
    private final IIngestionStateStore stateStore;
    private final IScheduleStore scheduleStore;
    private final IRagStore ragStore;

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
     * @return empty when a run is already in flight for this source
     */
    public Optional<String> runAsync(String ragConfigId, RagConfiguration knowledgeBase, IngestionSource source) {
        String sourceKey = IngestionPipeline.stateKey(ragConfigId, source);
        if (stateStore.activeRun(sourceKey).isPresent()) {
            // Checked here so the caller gets an immediate answer; the pipeline's own
            // claim is the real guard against the race.
            return Optional.empty();
        }

        // One virtual thread per run, as the existing RagIngestionService does for
        // single-document ingestion. Crawls block by design — on the fetch and on the
        // politeness delay — so they must never run on a shared pool.
        Executors.newVirtualThreadPerTaskExecutor().submit(() -> {
            try {
                IngestionReport report = pipeline.run(ragConfigId, knowledgeBase, source, Mode.INGEST);
                LOGGER.infof("Ingestion of source '%s' finished: %s, %d ingested, %d unchanged, %d tombstoned",
                        LogSanitizer.sanitize(source.getName()), report.outcome(),
                        report.documentsIngested(), report.documentsUnchanged(), report.documentsTombstoned());
            } catch (RuntimeException e) {
                LOGGER.errorf(e, "Ingestion of source '%s' threw", LogSanitizer.sanitize(source.getName()));
            }
        });
        return Optional.of(sourceKey);
    }

    /** Crawls and reports what would change, embedding and recording nothing. */
    public IngestionReport preview(String ragConfigId, RagConfiguration knowledgeBase, IngestionSource source) {
        return pipeline.run(ragConfigId, knowledgeBase, source, Mode.PREVIEW);
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
    public void syncSchedules(String ragConfigId, Integer version, RagConfiguration knowledgeBase) {
        if (knowledgeBase.getSources() == null) {
            return;
        }
        for (IngestionSource source : knowledgeBase.getSources()) {
            String sourceId = source.getId() == null || source.getId().isBlank() ? source.getName() : source.getId();
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

    /** Removes every ingestion schedule belonging to a knowledge base's sources. */
    public void removeSchedules(String ragConfigId, RagConfiguration knowledgeBase) {
        if (knowledgeBase == null || knowledgeBase.getSources() == null) {
            return;
        }
        for (IngestionSource source : knowledgeBase.getSources()) {
            String sourceId = source.getId() == null || source.getId().isBlank() ? source.getName() : source.getId();
            try {
                scheduleStore.deleteSchedulesByName(RagIngestionSchedules.scheduleName(ragConfigId, sourceId));
            } catch (IResourceStore.ResourceStoreException e) {
                LOGGER.errorf(e, "Could not remove the ingestion schedule for source '%s'",
                        LogSanitizer.sanitize(source.getName()));
            }
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
