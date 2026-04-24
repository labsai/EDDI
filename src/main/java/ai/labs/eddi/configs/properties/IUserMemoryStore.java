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
 * @author ginccc
 * @since 6.0.0
 */
public interface IUserMemoryStore {

    // === Flat property view (global entries) ===

    Properties readProperties(String userId) throws IResourceStore.ResourceStoreException;

    void mergeProperties(String userId, Properties properties) throws IResourceStore.ResourceStoreException;

    void deleteProperties(String userId) throws IResourceStore.ResourceStoreException;

    // === Structured entries ===

    /**
     * Insert or update a memory entry. Upsert key depends on visibility:
     * <ul>
     * <li>{@code self/group}: {@code (userId, key, sourceAgentId)}</li>
     * <li>{@code global}: {@code (userId, key)}</li>
     * </ul>
     *
     * @return the entry ID (generated or existing)
     */
    String upsert(UserMemoryEntry entry) throws IResourceStore.ResourceStoreException;

    void deleteEntry(String entryId) throws IResourceStore.ResourceStoreException;

    // === Queries ===

    /**
     * Returns entries visible to the given agent in the given groups. Combines:
     * self(agentId) + group(groupIds) + global.
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

    void deleteAllForUser(String userId) throws IResourceStore.ResourceStoreException;

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
