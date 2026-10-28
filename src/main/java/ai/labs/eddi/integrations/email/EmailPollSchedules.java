/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.email;

import java.util.Map;

/**
 * How a mailbox poll is marked on a {@code ScheduleConfiguration} — the same
 * convention as {@code RagIngestionSchedules}: a maintenance job, not a
 * conversation turn, that wants the schedule machinery's cluster-wide claim,
 * lease and fire log. {@code ScheduleFireExecutor} recognises the metadata and
 * hands the fire to {@code EmailMailboxPoller}.
 * <p>
 * The name is deterministic ({@code email-poll:<integrationId>}), so syncing is
 * a delete-by-name followed by a create, and the fire refuses a row whose name
 * does not match its metadata (a schedule body forged through the REST API is
 * refused there too).
 */
public final class EmailPollSchedules {

    /** Present on every schedule that drives a mailbox poll. */
    public static final String METADATA_TYPE_KEY = "emailPoll";

    public static final String METADATA_INTEGRATION_ID = "integrationId";

    private static final String NAME_PREFIX = "email-poll:";

    private EmailPollSchedules() {
    }

    public static boolean isEmailPollSchedule(Map<String, Object> metadata) {
        return metadata != null && Boolean.TRUE.equals(metadata.get(METADATA_TYPE_KEY));
    }

    /** Whether a schedule body claims to be one (any value of the marker). */
    public static boolean declaresEmailPoll(Map<String, Object> metadata) {
        return metadata != null && metadata.containsKey(METADATA_TYPE_KEY);
    }

    public static String scheduleName(String integrationId) {
        return NAME_PREFIX + integrationId;
    }

    public static Map<String, Object> metadata(String integrationId) {
        return Map.of(METADATA_TYPE_KEY, Boolean.TRUE, METADATA_INTEGRATION_ID, integrationId);
    }

    public static String integrationId(Map<String, Object> metadata) {
        return metadata == null || !(metadata.get(METADATA_INTEGRATION_ID) instanceof String id) ? null : id;
    }
}
