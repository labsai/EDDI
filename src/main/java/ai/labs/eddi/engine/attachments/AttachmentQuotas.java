/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments;

import ai.labs.eddi.engine.attachments.IAttachmentStore.AttachmentQuotaExceededException;

/**
 * The quota rule both attachment stores apply, once they hold the quota lock
 * for the scope and have counted its current usage.
 *
 * @since 6.0.0
 */
public final class AttachmentQuotas {

    private AttachmentQuotas() {
        // utility class
    }

    /**
     * {@link #check} with the usage as {@code {count, totalBytes}}.
     */
    public static void checkUsage(String scope, long[] usage, long incomingBytes, long maxCount, long maxBytes)
            throws AttachmentQuotaExceededException {
        check(scope, usage[0], usage[1], incomingBytes, maxCount, maxBytes);
    }

    /**
     * Throw when one more blob of {@code incomingBytes} would exceed a quota.
     *
     * @param scope
     *            {@link AttachmentQuotaExceededException#SCOPE_CONVERSATION} or
     *            {@link AttachmentQuotaExceededException#SCOPE_USER}
     * @param count
     *            blobs the scope holds now
     * @param totalBytes
     *            bytes the scope holds now
     * @param maxCount
     *            the file limit; zero or less disables it
     * @param maxBytes
     *            the byte limit; zero or less disables it
     */
    public static void check(String scope, long count, long totalBytes, long incomingBytes, long maxCount, long maxBytes)
            throws AttachmentQuotaExceededException {
        check(scope, count, totalBytes, 1, incomingBytes, maxCount, maxBytes);
    }

    /**
     * Throw when {@code incomingCount} more files of {@code incomingBytes} in total
     * would exceed a quota — the check of a {@linkplain QuotaReservation
     * reservation}, which may hold several files, or none (bytes only).
     *
     * @param count
     *            files the scope holds now, live reservations included
     * @param totalBytes
     *            bytes the scope holds now, live reservations included
     * @param incomingCount
     *            files to add; zero skips the file limit
     */
    public static void check(String scope, long count, long totalBytes, long incomingCount, long incomingBytes, long maxCount,
                             long maxBytes)
            throws AttachmentQuotaExceededException {
        // Compared as "incoming > limit - used", which cannot overflow for usage
        // within the limit; usage already over it fails on the first clause.
        if (maxCount > 0 && incomingCount > 0 && (count >= maxCount || incomingCount > maxCount - count)) {
            throw new AttachmentQuotaExceededException(scope,
                    "Attachment quota exceeded for %s: %d/%d files. Delete some attachments first."
                            .formatted(scope, count, maxCount));
        }
        if (maxBytes > 0 && (totalBytes > maxBytes || incomingBytes > maxBytes - totalBytes)) {
            throw new AttachmentQuotaExceededException(scope,
                    "Attachment storage quota exceeded for %s: %d + %d bytes exceeds limit of %d. Delete some attachments first."
                            .formatted(scope, totalBytes, incomingBytes, maxBytes));
        }
    }
}
