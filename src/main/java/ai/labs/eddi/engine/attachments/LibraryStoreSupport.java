/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments;

import ai.labs.eddi.engine.attachments.AttachmentLockOrder.Lock;
import ai.labs.eddi.engine.attachments.IAttachmentStore.AttachmentQuotaExceededException;
import ai.labs.eddi.engine.attachments.IAttachmentStore.AttachmentStoreException;
import ai.labs.eddi.engine.attachments.IAttachmentStore.OwnerRef;
import ai.labs.eddi.utils.PageBounds;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What both attachment stores do the same way for library documents: the owner
 * check, the bucket lock, the quota rule, slug collisions, grant eviction and
 * paging. Pure; the stores do the I/O.
 *
 * @since 6.8.0
 */
public final class LibraryStoreSupport {

    /** Built-in default of {@code eddi.documents.library.max-per-user}. */
    public static final long DEFAULT_MAX_PER_USER = 50;
    /**
     * Built-in default of {@code eddi.documents.library.max-total-bytes-per-user}:
     * 2 GiB.
     */
    public static final long DEFAULT_MAX_TOTAL_BYTES_PER_USER = 2_147_483_648L;
    /** Built-in default of {@code eddi.documents.library.retention-days}. */
    public static final int DEFAULT_RETENTION_DAYS = 365;
    /**
     * Built-in default of {@code eddi.documents.library.max-grants-per-document}.
     */
    public static final int DEFAULT_MAX_GRANTS_PER_DOCUMENT = 1000;
    /** How many collision suffixes a slug tries before the save is refused. */
    static final int MAX_SLUG_ATTEMPTS = 1000;
    /**
     * The id prefix of a personal library's bucket lock (see {@link #bucketLock}).
     */
    static final String USER_LIBRARY_LOCK_PREFIX = "library:";

    private LibraryStoreSupport() {
        // utility class
    }

    /**
     * The operator's library limits.
     *
     * @param maxPerOwner
     *            documents per library; zero or less disables the limit
     * @param maxTotalBytesPerOwner
     *            bytes per library (documents and their media views); zero or less
     *            disables the limit
     * @param defaultRetentionDays
     *            the retention of a document saved without its own; zero or less
     *            keeps such documents until they are removed
     * @param maxGrantsPerDocument
     *            the grants a library document keeps before the oldest is evicted;
     *            zero or less disables eviction
     */
    public record Limits(long maxPerOwner, long maxTotalBytesPerOwner, int defaultRetentionDays, int maxGrantsPerDocument) {

        /** The built-in defaults. */
        public static Limits defaults() {
            return new Limits(DEFAULT_MAX_PER_USER, DEFAULT_MAX_TOTAL_BYTES_PER_USER, DEFAULT_RETENTION_DAYS, DEFAULT_MAX_GRANTS_PER_DOCUMENT);
        }
    }

    /**
     * Refuses an owner a library cannot have in this release: a conversation (a
     * library document is never conversation-owned), a team (the team library is
     * not available yet), or one without a key.
     *
     * @throws IllegalArgumentException
     *             for any of those
     */
    public static void requireLibraryOwner(OwnerRef owner) {
        if (owner == null || owner.scope() == null || owner.ownerKey() == null || owner.ownerKey().isBlank()) {
            throw new IllegalArgumentException("A library owner needs a scope and an owner key");
        }
        if (owner.scope() == OwnerScope.CONVERSATION) {
            throw new IllegalArgumentException("A library owner is a user or a team, never a conversation");
        }
        if (owner.scope() == OwnerScope.TEAM) {
            throw new IllegalArgumentException("Team libraries are not available in this version");
        }
    }

    /**
     * The lock of a library's quota bucket. A personal library locks in the
     * {@code USER} place of the order, under an id of its own
     * ({@value #USER_LIBRARY_LOCK_PREFIX}{@code <ownerKey>}), so it never shares a
     * lock with the user's upload bucket; a team library locks in the {@code TEAM}
     * place.
     */
    public static Lock bucketLock(OwnerScope scope, String ownerKey) {
        return scope == OwnerScope.TEAM
                ? new Lock(AttachmentLockOrder.Scope.TEAM, ownerKey)
                : new Lock(AttachmentLockOrder.Scope.USER, USER_LIBRARY_LOCK_PREFIX + ownerKey);
    }

    /**
     * Refuses a document of {@code incomingBytes} (with its media views) that would
     * not fit the library. The refusal names the numbers; nothing is evicted.
     *
     * @param count
     *            documents the library holds now, soft-removed ones included
     * @param totalBytes
     *            bytes it holds now
     * @param incomingCount
     *            documents to add: 1 for a new document, 0 for a media view
     */
    public static void checkQuota(long count, long totalBytes, long incomingCount, long incomingBytes, Limits limits)
            throws AttachmentQuotaExceededException {
        long maxCount = limits.maxPerOwner();
        if (maxCount > 0 && incomingCount > 0 && (count >= maxCount || incomingCount > maxCount - count)) {
            throw new AttachmentQuotaExceededException(AttachmentQuotaExceededException.SCOPE_LIBRARY,
                    "The library is full: %d of %d documents. Remove a document first.".formatted(count, maxCount));
        }
        long maxBytes = limits.maxTotalBytesPerOwner();
        if (maxBytes > 0 && (totalBytes > maxBytes || incomingBytes > maxBytes - totalBytes)) {
            throw new AttachmentQuotaExceededException(AttachmentQuotaExceededException.SCOPE_LIBRARY,
                    "The library is full: %d + %d bytes exceeds its limit of %d bytes. Remove a document first.".formatted(totalBytes,
                            incomingBytes, maxBytes));
        }
    }

    /** Whether a slug is taken, as the store reads it under the bucket lock. */
    @FunctionalInterface
    public interface SlugLookup {
        boolean taken(String slug) throws AttachmentStoreException;
    }

    /**
     * {@code wanted}, or the first free {@code wanted-2}, {@code wanted-3}, … in
     * the owner's library.
     *
     * @throws AttachmentStoreException
     *             if {@value #MAX_SLUG_ATTEMPTS} of them are taken
     */
    public static String uniqueSlug(String wanted, SlugLookup lookup) throws AttachmentStoreException {
        for (int n = 1; n <= MAX_SLUG_ATTEMPTS; n++) {
            String candidate = LibraryMeta.suffixed(wanted, n);
            if (!lookup.taken(candidate)) {
                return candidate;
            }
        }
        throw new AttachmentStoreException("Too many library documents are named '" + wanted + "'; choose another title");
    }

    /**
     * Which grants of a library document to evict so that at most {@code cap}
     * remain: the grant just written ({@code keep}) never; first grants that exist
     * only in the legacy shape (their age is unknown), then the oldest rows.
     *
     * @param legacyGrantees
     *            the grantees of the legacy shape
     * @param rowGranteesOldestFirst
     *            the grantees of the grant rows, oldest first
     * @param cap
     *            the most grants a document keeps; zero or less evicts nothing
     * @return the conversations to evict, in both shapes
     */
    public static List<String> grantsToEvict(List<String> legacyGrantees, List<String> rowGranteesOldestFirst, String keep, int cap) {
        if (cap <= 0) {
            return List.of();
        }
        Set<String> rows = new LinkedHashSet<>(rowGranteesOldestFirst);
        Set<String> all = new LinkedHashSet<>(rows);
        all.addAll(legacyGrantees);
        int excess = all.size() - cap;
        if (excess <= 0) {
            return List.of();
        }
        List<String> order = new ArrayList<>();
        for (String grantee : legacyGrantees) {
            if (!rows.contains(grantee) && !order.contains(grantee)) {
                order.add(grantee);
            }
        }
        order.addAll(rows);
        List<String> evict = new ArrayList<>();
        for (String grantee : order) {
            if (evict.size() == excess) {
                break;
            }
            if (!grantee.equals(keep)) {
                evict.add(grantee);
            }
        }
        return evict;
    }

    /** The first row of a listing page: a negative offset reads as 0. */
    public static int pageOffset(int offset) {
        return Math.max(0, offset);
    }

    /**
     * The rows of a listing page: at most {@link PageBounds#maxLimit()}; zero or
     * less reads as that ceiling.
     */
    public static int pageLimit(int limit) {
        int max = PageBounds.maxLimit();
        return limit <= 0 ? max : Math.min(limit, max);
    }
}
