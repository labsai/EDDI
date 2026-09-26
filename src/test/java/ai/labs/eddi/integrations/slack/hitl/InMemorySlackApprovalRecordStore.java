/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack.hitl;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link ISlackApprovalRecordStore} with the production contract —
 * insert-if-absent keyed by {@code (integrationName, subject, pauseEpoch)},
 * expired records treated as absent — for unit tests of the Slack handlers.
 */
public class InMemorySlackApprovalRecordStore implements ISlackApprovalRecordStore {

    private final Map<String, SlackApprovalRecord> records = new ConcurrentHashMap<>();
    private final Duration retention = Duration.ofDays(30);

    private static String key(String integrationName, String subject, String pauseEpoch) {
        return integrationName + '|' + subject + '|' + pauseEpoch;
    }

    @Override
    public boolean tryRecord(String integrationName, String subject, String pauseEpoch, String approvalChannelId) {
        Instant now = Instant.now();
        var fresh = new SlackApprovalRecord(integrationName, subject, pauseEpoch, approvalChannelId, now,
                now.plus(retention));
        String key = key(integrationName, subject, pauseEpoch);
        synchronized (records) {
            SlackApprovalRecord existing = records.get(key);
            if (existing != null && existing.expiresAt().isAfter(now)) {
                return false;
            }
            records.put(key, fresh);
            return true;
        }
    }

    /** Seed a record directly, e.g. an old or expired one. */
    public void put(SlackApprovalRecord record) {
        records.put(key(record.integrationName(), record.subject(), record.pauseEpoch()), record);
    }

    @Override
    public List<SlackApprovalRecord> findBySubject(String integrationName, String subject) {
        Instant now = Instant.now();
        List<SlackApprovalRecord> result = new ArrayList<>();
        for (SlackApprovalRecord record : records.values()) {
            if (record.integrationName().equals(integrationName) && record.subject().equals(subject)
                    && record.expiresAt().isAfter(now)) {
                result.add(record);
            }
        }
        return result;
    }

    @Override
    public void delete(String integrationName, String subject, String pauseEpoch) {
        records.remove(key(integrationName, subject, pauseEpoch));
    }

    public int size() {
        return records.size();
    }
}
