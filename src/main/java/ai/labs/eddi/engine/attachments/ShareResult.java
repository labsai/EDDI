/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What one {@link AttachmentShareService#share} call shared and what it
 * refused. Everything in it may be shown to the model: it names files by their
 * display names and never carries a storage reference in text.
 *
 * @param shared
 *            the files shared, in request order
 * @param refused
 *            the requests refused, with a reason the model may read
 * @since 6.7.0
 */
public record ShareResult(List<Shared> shared, List<Refused> refused) {

    /** Step-record key: the shared file's display name. */
    public static final String RECORD_FILE_NAME = "fileName";
    /** Step-record key: the agent the file was shared with. */
    public static final String RECORD_TARGET_AGENT_ID = "targetAgentId";
    /** Step-record key: the {@link ShareReason}. */
    public static final String RECORD_REASON = "reason";

    public ShareResult {
        shared = shared == null ? List.of() : List.copyOf(shared);
        refused = refused == null ? List.of() : List.copyOf(refused);
    }

    /**
     * A shared file.
     *
     * @param selector
     *            what was shared — for server code (injecting it into the target's
     *            turn), never for the model
     * @param fileName
     *            the display name
     * @param mimeType
     *            the MIME type
     * @param sizeBytes
     *            the size of the stored blob
     */
    public record Shared(AttachmentSelector selector, String fileName, String mimeType, long sizeBytes) {
    }

    /**
     * A refused request.
     *
     * @param requestedName
     *            what was asked for, as the model wrote it (or a display name)
     * @param reason
     *            why, in text the model may read
     */
    public record Refused(String requestedName, String reason) {
    }

    /**
     * The rows for {@code MemoryKeys.ATTACHMENTS_SHARED}: one per shared file with
     * its display name, the target agent and the reason — no storage references.
     * The caller holds the memory and stores them on the source conversation's
     * current step.
     */
    public List<Map<String, String>> toStepRecords(String targetAgentId, ShareReason reason) {
        List<Map<String, String>> records = new ArrayList<>();
        for (Shared s : shared) {
            Map<String, String> row = new LinkedHashMap<>();
            row.put(RECORD_FILE_NAME, s.fileName());
            if (targetAgentId != null) {
                row.put(RECORD_TARGET_AGENT_ID, targetAgentId);
            }
            if (reason != null) {
                row.put(RECORD_REASON, reason.name());
            }
            records.add(row);
        }
        return records;
    }
}
