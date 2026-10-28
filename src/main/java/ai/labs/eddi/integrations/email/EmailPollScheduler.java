/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import ai.labs.eddi.configs.channels.model.EmailChannelSettings;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import ai.labs.eddi.integrations.email.ledger.IEmailMailboxStateStore;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.Optional;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Keeps one poll schedule per trigger-mode email integration: a
 * {@code HEARTBEAT} every {@code pollSeconds}, named
 * {@code email-poll:<integrationId>} and tagged with
 * {@link EmailPollSchedules#metadata}. Created and replaced when the
 * integration is saved, removed when it is deleted, and re-created by the sweep
 * when it is missing (an integration imported or written around the REST
 * store).
 * <p>
 * The schedule machinery provides what a poller needs and EDDI must not build
 * twice: the cluster-wide claim (one node polls a mailbox at a time), the
 * lease, the fire log. No custom scheduler.
 */
@ApplicationScoped
public class EmailPollScheduler {

    private static final Logger LOGGER = Logger.getLogger(EmailPollScheduler.class);

    /**
     * The user id recorded on poll schedules; a poll runs no conversation as it.
     */
    static final String SCHEDULE_USER_ID = "system:scheduler";

    private final IScheduleStore scheduleStore;
    private final IEmailMailboxStateStore stateStore;

    @Inject
    public EmailPollScheduler(IScheduleStore scheduleStore, IEmailMailboxStateStore stateStore) {
        this.scheduleStore = scheduleStore;
        this.stateStore = stateStore;
    }

    /** Replaces the integration's schedule with one matching {@code settings}. */
    public void sync(String integrationId, EmailChannelSettings settings) throws IResourceStore.ResourceStoreException {
        String name = EmailPollSchedules.scheduleName(integrationId);
        scheduleStore.deleteSchedulesByName(name);
        if (settings == null || settings.mode() != EmailChannelSettings.Mode.TRIGGER) {
            return;
        }
        scheduleStore.createSchedule(build(integrationId, settings));
        LOGGER.infof("Email integration %s: polling every %d s", sanitize(integrationId), settings.pollSeconds());
    }

    /** Creates the schedule only when none exists. */
    public boolean ensure(String integrationId, EmailChannelSettings settings) throws IResourceStore.ResourceStoreException {
        Optional<ScheduleConfiguration> existing = scheduleStore.readScheduleByName(EmailPollSchedules.scheduleName(integrationId));
        if (existing.isPresent()) {
            return false;
        }
        sync(integrationId, settings);
        return true;
    }

    /** Removes the schedule and the polling state of a deleted integration. */
    public void remove(String integrationId) {
        try {
            scheduleStore.deleteSchedulesByName(EmailPollSchedules.scheduleName(integrationId));
        } catch (IResourceStore.ResourceStoreException e) {
            LOGGER.errorf(e, "Email integration %s: its poll schedule could not be removed — it will remove itself on its next fire",
                    sanitize(integrationId));
        }
        try {
            stateStore.delete(integrationId);
        } catch (RuntimeException e) {
            LOGGER.warnf("Email integration %s: its polling state could not be removed: %s", sanitize(integrationId), e.getMessage());
        }
    }

    static ScheduleConfiguration build(String integrationId, EmailChannelSettings settings) {
        var schedule = new ScheduleConfiguration();
        schedule.setName(EmailPollSchedules.scheduleName(integrationId));
        schedule.setTriggerType(ScheduleConfiguration.TriggerType.HEARTBEAT);
        schedule.setHeartbeatIntervalSeconds((long) settings.pollSeconds());
        schedule.setTimeZone("UTC");
        schedule.setEnabled(true);
        schedule.setUserId(SCHEDULE_USER_ID);
        // Armed: neither store computes a first fire, and an unarmed row never fires.
        schedule.setNextFire(Instant.now().plusSeconds(5));
        schedule.setMetadata(EmailPollSchedules.metadata(integrationId));
        return schedule;
    }
}
