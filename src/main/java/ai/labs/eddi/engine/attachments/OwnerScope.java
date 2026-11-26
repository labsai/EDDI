/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments;

import java.util.Locale;

/**
 * Who an uploaded blob — and with it its whole lineage — belongs to.
 * <p>
 * Every upload is {@link #CONVERSATION}-owned. A file put into a personal
 * library ({@code IAttachmentStore.promote}) becomes {@link #USER}-owned with
 * its whole lineage. {@link #TEAM} (a team library) is declared so that the
 * owner key can be part of every dedup, cache, job and index key from the
 * start; nothing writes it yet.
 *
 * @since 6.8.0
 */
public enum OwnerScope {
    /** Owned by the conversation it was uploaded to; the owner key is its id. */
    CONVERSATION,
    /** Owned by a user's personal library; the owner key is the space tenant id. */
    USER,
    /** Owned by a team library; the owner key is the team space's tenant id. */
    TEAM;

    /**
     * The stored value back to a scope: absent means {@link #CONVERSATION} (every
     * blob stored before the column existed).
     *
     * @throws IllegalArgumentException
     *             for a value this release does not know
     */
    public static OwnerScope fromStored(String stored) {
        if (stored == null || stored.isBlank()) {
            return CONVERSATION;
        }
        return OwnerScope.valueOf(stored.strip().toUpperCase(Locale.ROOT));
    }
}
