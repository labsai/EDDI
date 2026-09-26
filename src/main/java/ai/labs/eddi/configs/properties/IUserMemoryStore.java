/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.properties;

import ai.labs.eddi.configs.properties.model.Properties;
import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
import ai.labs.eddi.datastore.IResourceStore;

import java.util.List;
import java.util.Optional;

/**
 * Unified store for all user-scoped persistent data. Supports both structured
 * memory entries (with visibility, categories, agent scoping) and a flat
 * key-value property view for REST and backward compatibility.
 *
 * <p>
 * Flat property methods ({@link #readProperties}, {@link #mergeProperties},
 * {@link #deleteProperties}) provide a simplified view of {@code global}
 * entries in the {@code usermemories} collection.
 *
 * <p>
 * Structured entry methods operate on the full {@code usermemories} collection
 * with visibility, categories, and agent scoping.
 *
 * @since 6.0.0
 */
public interface IUserMemoryStore {

    /**
     * Prefix of the keys EDDI keeps for its own GDPR bookkeeping, such as the Art.
     * 18 processing-restriction flag. See {@link #isReservedKey}.
     */
    String RESERVED_KEY_PREFIX = "_gdpr_";

    /**
     * Whether a key belongs to EDDI's GDPR bookkeeping rather than to the user's
     * memories. Such keys are written only through {@link #upsertReserved}, are
     * never evicted, pruned or consolidated, and survive
     * {@link #deleteOlderThan}/{@link #deleteProperties}; only the erasure cascade
     * ({@link #deleteAllForUser}) and the admin unrestrict path remove them.
     * <p>
     * Exact prefix match, case-sensitive, like the retention sweep's filter.
     */
    static boolean isReservedKey(String key) {
        return key != null && key.startsWith(RESERVED_KEY_PREFIX);
    }

    /**
     * Refuses a {@linkplain #isReservedKey reserved key}. For callers that validate
     * before reaching the store (REST, MCP, the LLM tool) so they can answer with a
     * readable refusal rather than a store failure.
     *
     * @throws ReservedMemoryKeyException
     *             if {@code key} is reserved
     */
    static void rejectReservedKey(String key) {
        if (isReservedKey(key)) {
            throw new ReservedMemoryKeyException(key);
        }
    }

    /**
     * A write named a key reserved for GDPR bookkeeping. Unchecked, and an
     * {@link IllegalArgumentException}, because it is a caller error that no retry
     * fixes — REST surfaces map it to 400.
     */
    final class ReservedMemoryKeyException extends IllegalArgumentException {

        public ReservedMemoryKeyException(String key) {
            super("Memory keys starting with '" + RESERVED_KEY_PREFIX + "' are reserved for GDPR bookkeeping and cannot be written "
                    + "or deleted here (key: '" + key + "').");
        }
    }

    /** Recall order that ranks primarily by {@code accessCount}. */
    String RECALL_ORDER_MOST_ACCESSED = "most_accessed";

    /**
     * How a {@link #RECALL_ORDER_MOST_ACCESSED} recall window is split between its
     * two ranking terms. Lives on the contract rather than in one store, because
     * every backend must answer the same recall order with the same entries —
     * otherwise {@code most_accessed} means something different depending on which
     * database a deployment happens to use.
     *
     * @param accessSlots
     *            slots filled by {@code accessCount} descending; {@code -1} means
     *            unlimited, {@code 0} means "skip that query entirely"
     * @param recencySlots
     *            slots reserved for the most recently updated entries, same
     *            encoding
     */
    record RecallWindow(int accessSlots, int recencySlots) {

        /**
         * Share of the window reserved for recency: 1/5th.
         * <p>
         * Without the reservation {@code most_accessed} is self-reinforcing: only
         * entries already inside the window get their {@code accessCount} incremented,
         * so a freshly written entry (count 0) can never climb in once the window is
         * full. The reserved slots are the recency term of the ranking — a new entry
         * always gets at least one chance to be recalled, and thereby to start
         * accumulating access counts.
         */
        private static final int RECENCY_RESERVATION_DIVISOR = 5;

        /**
         * @param maxEntries
         *            the caller's recall window; {@code <= 0} means "no limit"
         */
        public static RecallWindow forMaxEntries(int maxEntries) {
            if (maxEntries <= 0) {
                return new RecallWindow(-1, 0);
            }
            // Reserve recency slots only when the window can hold BOTH terms. At
            // maxEntries == 1 an unconditional Math.max(1, ...) consumed the entire
            // window, leaving zero access slots — so a `most_accessed` recall never
            // queried by access count at all and returned the most RECENT entry, the
            // exact opposite of the requested ordering. maxEntries is reachable as 1
            // from the agent's maxRecallEntries, the REST query param and the MCP
            // tool argument.
            int recencySlots = maxEntries > 1 ? Math.max(1, maxEntries / RECENCY_RESERVATION_DIVISOR) : 0;
            return new RecallWindow(maxEntries - recencySlots, recencySlots);
        }
    }

    // === Flat property view (global entries) ===

    Properties readProperties(String userId) throws IResourceStore.ResourceStoreException;

    /**
     * Upserts each pair as a {@code global} entry. Refuses a
     * {@linkplain #isReservedKey reserved key} with
     * {@link ReservedMemoryKeyException} before writing anything.
     */
    void mergeProperties(String userId, Properties properties) throws IResourceStore.ResourceStoreException;

    /**
     * Deletes the user's {@code global} entries, <strong>except</strong>
     * {@linkplain #isReservedKey reserved ones}: a "clear my properties" call must
     * not lift a GDPR Art. 18 restriction that only the admin unrestrict endpoint
     * may lift, and must not do it without an audit entry.
     */
    void deleteProperties(String userId) throws IResourceStore.ResourceStoreException;

    // === Structured entries ===

    /**
     * Insert or update a memory entry. Upsert key depends on visibility:
     * <ul>
     * <li>{@code self/group}: {@code (userId, key, sourceAgentId)}</li>
     * <li>{@code global}: {@code (userId, key)}</li>
     * </ul>
     *
     * <strong>Refuses a {@linkplain #isReservedKey reserved key}</strong> with
     * {@link ReservedMemoryKeyException}, whatever the entry's category. Those keys
     * are GDPR bookkeeping written only through {@link #upsertReserved}; a global
     * entry is keyed on {@code (userId, key)}, so an ordinary upsert of
     * {@code _gdpr_processing_restricted} would not merely add a row — it would
     * overwrite the admin's restriction in place. The check sits here, at the
     * store, so that every write path (LLM tool, property setter, MCP, REST,
     * migration, Dream) is covered, including the ones added later.
     *
     * @return the entry ID (generated or existing)
     */
    String upsert(UserMemoryEntry entry) throws IResourceStore.ResourceStoreException;

    /**
     * The one write path for {@linkplain #isReservedKey reserved keys}, for
     * {@code GdprComplianceService} alone. Refuses any other key with
     * {@link ReservedMemoryKeyException}, so it cannot be used as a way around the
     * checks {@link #upsert} applies to ordinary entries.
     *
     * @return the entry ID (generated or existing)
     */
    String upsertReserved(UserMemoryEntry entry) throws IResourceStore.ResourceStoreException;

    void deleteEntry(String entryId) throws IResourceStore.ResourceStoreException;

    /**
     * Finds a memory entry by its ID. Used for ownership validation before
     * deletion.
     *
     * @return the entry, or empty if not found
     * @since 6.1.0
     */
    Optional<UserMemoryEntry> findEntryById(String entryId) throws IResourceStore.ResourceStoreException;

    // === Queries ===

    /**
     * Returns entries visible to the given agent in the given groups. Combines the
     * user's own scope — self(agentId) + group(groupIds) + global — with,
     * additively, TEAM-OWNED entries (I8): lessons stored under the synthetic owner
     * {@link #TEAM_OWNER_PREFIX}{@code +groupId} with {@code group} visibility, for
     * each supplied group. The team branch never widens the user's own scope — a
     * personal entry of another human user is unreachable through it, because team
     * owner ids are derived from the supplied group ids, not caller-supplied.
     *
     * @param recallOrder
     *            "most_recent" (updatedAt DESC) or "most_accessed" (accessCount
     *            DESC)
     * @param maxEntries
     *            maximum entries to return
     */
    List<UserMemoryEntry> getVisibleEntries(String userId, String agentId, List<String> groupIds, String recallOrder, int maxEntries)
            throws IResourceStore.ResourceStoreException;

    /**
     * Owner prefix for TEAM-OWNED memory (I8): a group's retro lessons are stored
     * under the synthetic user {@code "group:"+groupId} so they belong to the team,
     * not to whichever human happened to run the discussion — and survive that
     * human's GDPR erasure without carrying their identity.
     */
    String TEAM_OWNER_PREFIX = "group:";

    /**
     * Text filter across keys and values (v1: regex, v2: semantic search).
     */
    List<UserMemoryEntry> filterEntries(String userId, String query) throws IResourceStore.ResourceStoreException;

    List<UserMemoryEntry> getEntriesByCategory(String userId, String category) throws IResourceStore.ResourceStoreException;

    Optional<UserMemoryEntry> getByKey(String userId, String key) throws IResourceStore.ResourceStoreException;

    /**
     * Returns all entries for a user (admin/export use case).
     */
    List<UserMemoryEntry> getAllEntries(String userId) throws IResourceStore.ResourceStoreException;

    // === GDPR ===

    /**
     * Deletes every entry of the user, reserved keys included. For the GDPR erasure
     * cascade; a "delete all my memories" surface uses
     * {@link #deleteAllExceptReserved} instead.
     */
    void deleteAllForUser(String userId) throws IResourceStore.ResourceStoreException;

    /**
     * Deletes every entry of the user except the {@linkplain #isReservedKey
     * reserved ones}, for the REST and MCP "delete all memories" surfaces. Those
     * are memory housekeeping, not an Art. 17 erasure: going through
     * {@link #deleteAllForUser} let a restricted user lift their own Art. 18
     * restriction by clearing their memories, with no admin and no audit entry.
     * <p>
     * Entry by entry rather than one filtered delete so the contract needs no new
     * query in every backend; a user's memories are bounded by
     * {@code maxEntriesPerUser}.
     *
     * @return how many entries were deleted
     */
    default long deleteAllExceptReserved(String userId) throws IResourceStore.ResourceStoreException {
        long deleted = 0;
        for (UserMemoryEntry entry : getAllEntries(userId)) {
            if (entry.id() != null && !isReservedKey(entry.key())) {
                deleteEntry(entry.id());
                deleted++;
            }
        }
        return deleted;
    }

    long countEntries(String userId) throws IResourceStore.ResourceStoreException;

    /**
     * Delete user memory entries older than the given number of days. Used by
     * scheduled retention cleanup.
     * <p>
     * <strong>Important:</strong> Entries with keys starting with {@code _gdpr_}
     * are excluded from deletion to prevent accidental lifting of GDPR processing
     * restrictions (Art. 18).
     *
     * @param olderThanDays
     *            entries with updatedAt older than this many days ago are deleted
     * @return number of entries deleted
     */
    long deleteOlderThan(int olderThanDays) throws IResourceStore.ResourceStoreException;
}
