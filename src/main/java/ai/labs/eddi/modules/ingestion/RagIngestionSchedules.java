/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion;

import ai.labs.eddi.configs.rag.model.IngestionSource;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.engine.runtime.internal.CronParser;

import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;

/**
 * How a scheduled ingestion run is marked on a {@code ScheduleConfiguration}.
 *
 * <p>
 * Follows the convention the other non-conversation schedules already use —
 * HITL timeouts, Dream consolidation and team cadences all identify themselves
 * by free-form metadata that {@code ScheduleFireExecutor} recognises. Ingestion
 * is the same shape of work: a maintenance job, not a conversation turn, that
 * wants the schedule machinery's cluster-wide claim, lease, retry and fire log.
 *
 * <h2>Why the name is deterministic</h2>
 * <p>
 * A source's schedule is named {@code rag-ingestion:<ragConfigId>:<sourceId>},
 * so syncing it is a delete-by-name followed by a create — no scan, and no way
 * to leave an orphan behind. The draft this replaces searched
 * {@code readAllSchedules(1000)} for the matching row; past a thousand
 * schedules (HITL timeouts and Dream cycles each create one) it silently failed
 * to find it, then created a duplicate on update and left a schedule still
 * crawling a deleted source on delete.
 */
public final class RagIngestionSchedules {

    /** Present on every schedule that drives an ingestion run. */
    public static final String METADATA_TYPE_KEY = "ragIngestion";

    public static final String METADATA_RAG_CONFIG_ID = "ragConfigId";
    public static final String METADATA_RAG_CONFIG_VERSION = "ragConfigVersion";
    public static final String METADATA_SOURCE_ID = "sourceId";

    private static final String NAME_PREFIX = "rag-ingestion:";

    private RagIngestionSchedules() {
    }

    /** Whether this schedule drives an ingestion run. */
    public static boolean isIngestionSchedule(Map<String, Object> metadata) {
        return metadata != null && Boolean.TRUE.equals(metadata.get(METADATA_TYPE_KEY));
    }

    /**
     * The one name a source's schedule may have. Deterministic so that syncing is
     * an upsert by name rather than a search.
     */
    public static String scheduleName(String ragConfigId, String sourceId) {
        return NAME_PREFIX + ragConfigId + ":" + sourceId;
    }

    public static Map<String, Object> metadata(String ragConfigId, Integer version, String sourceId) {
        return Map.of(
                METADATA_TYPE_KEY, Boolean.TRUE,
                METADATA_RAG_CONFIG_ID, ragConfigId,
                METADATA_RAG_CONFIG_VERSION, version == null ? 1 : version,
                METADATA_SOURCE_ID, sourceId);
    }

    public static String ragConfigId(Map<String, Object> metadata) {
        return metadata == null ? null : (String) metadata.get(METADATA_RAG_CONFIG_ID);
    }

    public static Integer ragConfigVersion(Map<String, Object> metadata) {
        if (metadata == null) {
            return null;
        }
        Object version = metadata.get(METADATA_RAG_CONFIG_VERSION);
        return version instanceof Number number ? number.intValue() : null;
    }

    public static String sourceId(Map<String, Object> metadata) {
        return metadata == null ? null : (String) metadata.get(METADATA_SOURCE_ID);
    }

    /**
     * The zone an ingestion cron is read in.
     *
     * <p>
     * Fixed at UTC and written onto the schedule rather than left null, because
     * {@code SchedulePollerService} re-arms a fired schedule in
     * {@code resolveTimeZone(schedule.getTimeZone())}, which falls back to the
     * deployment's {@code eddi.schedule.default-timezone}. A schedule whose first
     * fire was computed here in UTC and whose every later fire was computed in some
     * other zone would drift by the offset on the first run and never say so.
     */
    public static final ZoneId ZONE = ZoneId.of("UTC");

    /**
     * When a source with this cron first runs.
     *
     * <p>
     * Every creator of a {@code ScheduleConfiguration} has to arm it: both stores'
     * {@code findDueSchedules} select on
     * {@code enabled = true AND nextFire <= now}, and a null {@code nextFire}
     * matches neither backend's comparison — BSON type bracketing excludes null on
     * Mongo, and {@code next_fire <= ?} is UNKNOWN for NULL on Postgres. An unarmed
     * row therefore reads back enabled, shows as scheduled on every screen, and
     * never fires.
     *
     * @throws IllegalArgumentException
     *             if the expression cannot be parsed, or is syntactically valid but
     *             can never match (e.g. {@code 0 0 30 2 *} — 30 February).
     *             {@code CronParser} signals the latter with
     *             {@link IllegalStateException}, which callers would otherwise
     *             surface as a 500
     */
    public static Instant firstFire(String cronExpression) {
        return firstFire(cronExpression, ZONE);
    }

    /**
     * When a source with this cron first runs, read in a given zone.
     *
     * <p>
     * The zone is a parameter and not always {@link #ZONE} because of the one case
     * that cannot store a zone at all: the startup repair of rows written before
     * {@code buildSchedule} armed anything. Those rows have a null
     * {@code timeZone}, and {@code IScheduleStore.setScheduleEnabled} takes only
     * {@code enabled} and {@code nextFire} — there is no way to write a zone
     * through it. The poller will therefore re-arm every later fire through
     * {@code resolveTimeZone(null)}, which is the deployment's
     * {@code eddi.schedule.default-timezone}. Computing the first fire in UTC
     * regardless would guarantee exactly one interval of the wrong length on any
     * deployment that sets a zone. So the repair computes it in the zone the poller
     * is going to use, which is the only choice that makes the row internally
     * consistent without a new store method.
     * </p>
     */
    public static Instant firstFire(String cronExpression, ZoneId zone) {
        try {
            return CronParser.computeNextFire(cronExpression, Instant.now(), zone);
        } catch (IllegalStateException e) {
            throw new IllegalArgumentException(
                    "cron '" + cronExpression + "' has no matching fire time within the next two years", e);
        }
    }

    /**
     * Rejects a cron the scheduler could accept but never act on, at the write
     * boundary where the operator is waiting for an answer.
     *
     * <p>
     * Shared by the REST write path and the ZIP import path on purpose: import used
     * to assign source ids and call {@code RagConfiguration.validate()} only, so an
     * archive could store a cron that {@code POST /ragstore/rags} refuses — and the
     * schedule it produced looked scheduled and never ran.
     *
     * @throws IllegalArgumentException
     *             naming the offending source, for the caller to turn into a 400
     */
    public static void requireValidCrons(RagConfiguration ragConfiguration) {
        if (ragConfiguration == null || ragConfiguration.getSources() == null) {
            return;
        }
        for (IngestionSource source : ragConfiguration.getSources()) {
            if (source == null) {
                continue;
            }
            String cron = source.getCron();
            if (cron == null || cron.isBlank()) {
                continue;
            }
            try {
                CronParser.validate(cron);
                // Not validate() alone: "0 0 30 2 *" parses and never matches a day.
                firstFire(cron);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(
                        "Ingestion source '" + source.getName() + "' has an invalid cron: " + e.getMessage(), e);
            }
        }
    }
}
