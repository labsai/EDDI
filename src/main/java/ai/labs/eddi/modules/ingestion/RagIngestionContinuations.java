/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.utils.LogSanitizer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;

/**
 * The one-shot schedules that wake a source's drain when its work table still
 * holds work after a run.
 *
 * <p>
 * A source's cron lists it; between listings, the work it queued is drained by
 * continuation fires — one pending one-shot per source, named
 * {@link RagIngestionSchedules#continuationName}, armed for the earliest time a
 * row may be claimed. The scheduler's cluster-wide claim and lease decide which
 * node fires it, so a large backlog drains across the cluster with no scheduler
 * of its own, and a source without a cron still finishes what "Run now" queued.
 *
 * <p>
 * Arming replaces: it deletes the source's continuation by name and writes the
 * new one, so there is never more than one. Best effort both ways — a failure
 * is logged, never thrown into the run: the next listing finds the work again.
 */
@ApplicationScoped
public class RagIngestionContinuations {

    private static final Logger LOGGER = Logger.getLogger(RagIngestionContinuations.class);

    /** Owner of every continuation, as of the cron schedules. */
    private static final String SCHEDULE_USER_ID = "system:scheduler";

    private final IScheduleStore scheduleStore;

    @Inject
    public RagIngestionContinuations(IScheduleStore scheduleStore) {
        this.scheduleStore = scheduleStore;
    }

    /** Arms the source's continuation for {@code at}, replacing any pending one. */
    public void arm(String ragConfigId, String sourceId, Instant at) {
        String name = RagIngestionSchedules.continuationName(ragConfigId, sourceId);
        try {
            scheduleStore.deleteSchedulesByName(name);
            var schedule = new ScheduleConfiguration();
            schedule.setName(name);
            schedule.setUserId(SCHEDULE_USER_ID);
            schedule.setOneTimeAt(at.toString());
            schedule.setNextFire(at);
            schedule.setEnabled(true);
            schedule.setCreatedAt(Instant.now());
            schedule.setMetadata(RagIngestionSchedules.continuationMetadata(ragConfigId, sourceId));
            scheduleStore.createSchedule(schedule);
        } catch (IResourceStore.ResourceStoreException | RuntimeException e) {
            LOGGER.warnf(e, "Could not arm the continuation of ingestion source %s of knowledge base %s; its "
                    + "queued documents wait for its next run", LogSanitizer.sanitize(sourceId),
                    LogSanitizer.sanitize(ragConfigId));
        }
    }

    /** Withdraws the source's continuation, if any. */
    public void disarm(String ragConfigId, String sourceId) {
        try {
            scheduleStore.deleteSchedulesByName(RagIngestionSchedules.continuationName(ragConfigId, sourceId));
        } catch (IResourceStore.ResourceStoreException | RuntimeException e) {
            LOGGER.warnf(e, "Could not withdraw the continuation of ingestion source %s of knowledge base %s",
                    LogSanitizer.sanitize(sourceId), LogSanitizer.sanitize(ragConfigId));
        }
    }
}
