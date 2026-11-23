/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion;

import ai.labs.eddi.modules.ingestion.IngestionWorkQueue.WorkClaim;
import ai.labs.eddi.modules.ingestion.connector.WorkItem;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What a queued row carries so the drain can rebuild the {@link WorkItem}
 * discovery listed: the partition, the connector's hints and the citation — the
 * folder chain included, which the retrieval-time scope filter reads off every
 * chunk.
 *
 * <p>
 * The document id and the version token are columns of their own, so they are
 * not repeated here. Hints are connector-private; the framework stores them and
 * hands them back unread.
 */
final class WorkPayloads {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String PARTITION = "p";
    private static final String HINTS = "h";
    private static final String TITLE = "t";
    private static final String URL = "u";
    private static final String FOLDERS = "f";

    private WorkPayloads() {
    }

    /**
     * The payload of an item, or null when it cannot be queued: it carries a
     * prefetched document, or its payload would exceed
     * {@link IngestionWorkQueue#MAX_PAYLOAD_LENGTH}. Such an item is processed
     * where it was found, as before the work table.
     */
    static String encode(WorkItem item) {
        if (item.prefetched() != null || item.action() != WorkItem.Action.UPSERT) {
            return null;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put(PARTITION, item.partitionId());
        if (!item.hints().isEmpty()) {
            payload.put(HINTS, item.hints());
        }
        if (item.citation() != null) {
            putIfPresent(payload, TITLE, item.citation().title());
            putIfPresent(payload, URL, item.citation().url());
            putIfPresent(payload, FOLDERS, item.citation().folderIds());
        }
        try {
            String encoded = JSON.writeValueAsString(payload);
            return encoded.length() <= IngestionWorkQueue.MAX_PAYLOAD_LENGTH ? encoded : null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /**
     * The work item a claim stands for, or null when its payload cannot be read — a
     * row written by a build whose payload this one does not understand.
     */
    static WorkItem decode(WorkClaim claim) {
        if (claim.payload() == null) {
            return null;
        }
        Map<String, Object> payload;
        try {
            payload = JSON.readValue(claim.payload(), new TypeReference<>() {
            });
        } catch (JsonProcessingException e) {
            return null;
        }
        Object partition = payload.get(PARTITION);
        if (!(partition instanceof String partitionId)) {
            return null;
        }
        Map<String, String> hints = new LinkedHashMap<>();
        if (payload.get(HINTS) instanceof Map<?, ?> stored) {
            for (Map.Entry<?, ?> entry : stored.entrySet()) {
                if (entry.getKey() instanceof String key && entry.getValue() instanceof String value) {
                    hints.put(key, value);
                }
            }
        }
        String title = text(payload, TITLE);
        String url = text(payload, URL);
        String folders = text(payload, FOLDERS);
        WorkItem.Citation citation = title == null && url == null && folders == null
                ? null
                : new WorkItem.Citation(title, url, folders);
        return new WorkItem(claim.documentId(), claim.versionToken(), WorkItem.Action.UPSERT, partitionId, hints,
                null, citation);
    }

    private static void putIfPresent(Map<String, Object> payload, String key, String value) {
        if (value != null) {
            payload.put(key, value);
        }
    }

    private static String text(Map<String, Object> payload, String key) {
        return payload.get(key) instanceof String value ? value : null;
    }
}
