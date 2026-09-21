/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion;

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
}
