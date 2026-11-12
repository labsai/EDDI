/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rag.model;

import java.util.List;

/**
 * The {@code partitions} block shared by source types whose provider divides
 * content into drives, sites or mailboxes: which of them a source reads.
 *
 * <p>
 * Config model only in this version: no source type reads it yet. A connector
 * that adopts it applies {@link #validate} at save time.
 *
 * <pre>
 * "partitions": { "mode": "LISTED", "ids": ["0AbCdEf…", "0GhIjKl…"] }
 * "partitions": { "mode": "ALL_ACCESSIBLE" }
 * </pre>
 */
public class SourcePartitions {

    /** How the partitions to read are chosen. */
    public enum Mode {
        /**
         * Exactly the partitions in {@code ids}. The default: a credential that gains
         * access to a new drive does not silently widen the knowledge base.
         */
        LISTED,
        /**
         * Every partition the credential can see, re-listed each run. Convenient, and
         * the reason it is never the default: what is indexed then follows whatever
         * someone grants the service account.
         */
        ALL_ACCESSIBLE
    }

    private Mode mode = Mode.LISTED;

    private List<String> ids;

    /**
     * @throws IllegalArgumentException
     *             naming the field and the source
     */
    public void validate(String sourceName) {
        Mode effective = modeOrDefault();
        boolean hasIds = ids != null && !ids.isEmpty();
        if (effective == Mode.LISTED && !hasIds) {
            throw new IllegalArgumentException("partitions.ids of ingestion source '" + sourceName
                    + "' is empty; list the partitions to read, or set partitions.mode to ALL_ACCESSIBLE");
        }
        if (effective == Mode.ALL_ACCESSIBLE && hasIds) {
            // Refused rather than ignored: an operator who lists ids believes the source
            // is limited to them.
            throw new IllegalArgumentException("partitions.ids of ingestion source '" + sourceName
                    + "' is set while partitions.mode is ALL_ACCESSIBLE, which reads every partition");
        }
        if (hasIds && ids.stream().anyMatch(id -> id == null || id.isBlank())) {
            throw new IllegalArgumentException("partitions.ids of ingestion source '" + sourceName
                    + "' contains an empty entry");
        }
    }

    public Mode modeOrDefault() {
        return mode == null ? Mode.LISTED : mode;
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode;
    }

    public List<String> getIds() {
        return ids;
    }

    public void setIds(List<String> ids) {
        this.ids = ids;
    }
}
