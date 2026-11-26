/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments;

/**
 * Which quotas a streamed upload counts against — the {@code quotaMode} of the
 * streaming {@link IAttachmentStore} {@code store} and of a mode-aware
 * {@link IAttachmentStore#reserve reservation}.
 */
public enum QuotaMode {

    /**
     * Today's rules: the file and its bytes count toward the conversation's quota
     * and, when the user is known, the user's; the file may be no larger than
     * {@code eddi.attachments.max-size-bytes}.
     */
    STANDARD,

    /**
     * A large file: it may be larger than {@code eddi.attachments.max-size-bytes},
     * its bytes are exempt from the per-conversation byte quota and counted against
     * the user's instead. The file still counts toward the conversation's file
     * count. Needs a known user — without one it would count against nothing.
     */
    LARGE_PER_USER;

    /**
     * The stored form: {@code null} for {@link #STANDARD}, which every older row
     * is.
     */
    public String stored() {
        return this == STANDARD ? null : name();
    }

    /**
     * Reads {@link #stored()}; {@code null} or an unknown value is
     * {@link #STANDARD}.
     */
    public static QuotaMode fromStored(String stored) {
        return LARGE_PER_USER.name().equals(stored) ? LARGE_PER_USER : STANDARD;
    }
}
