/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups;

import ai.labs.eddi.configs.groups.model.SharedArtifact;
import ai.labs.eddi.datastore.IResourceStore;

import java.util.List;

/**
 * Store for {@link SharedArtifact}s (I17) — runtime documents in their own
 * collection, one per co-edited artifact of a group discussion. Same
 * single-version, DB-agnostic shape as {@link IGroupConversationStore}.
 *
 * @author ginccc
 */
public interface ISharedArtifactStore {

    /**
     * Persists a new artifact and assigns its id.
     *
     * @return the new artifact's id
     */
    String create(SharedArtifact artifact) throws IResourceStore.ResourceStoreException;

    SharedArtifact read(String id) throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException;

    /**
     * Persists {@code artifact} only if the stored document's {@code version} still
     * equals {@code expectedVersion} — the deterministic CAS every accepted edit
     * goes through. The caller applies the edit (which bumps the in-memory version)
     * and presents the version it <em>read</em>.
     *
     * @throws IResourceStore.ResourceModifiedException
     *             lost the race — someone else's edit landed first (retry after a
     *             fresh read)
     * @throws ArtifactGoneException
     *             the artifact was deleted concurrently
     */
    void updateIfVersion(SharedArtifact artifact, long expectedVersion)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceModifiedException;

    void delete(String id) throws IResourceStore.ResourceStoreException;

    /**
     * All artifacts of one discussion, oldest first. Bounded by the group config's
     * {@code maxArtifactsPerDiscussion}, so no pagination surface.
     */
    List<SharedArtifact> listByGroupConversationId(String groupConversationId) throws IResourceStore.ResourceStoreException;

    /**
     * Cascade delete for a closing/deleted discussion.
     *
     * @return how many artifacts were removed
     */
    long deleteByGroupConversationId(String groupConversationId) throws IResourceStore.ResourceStoreException;

    /**
     * GDPR erasure sweep: permanently removes every artifact whose
     * {@code ownerUserId} is {@code userId}. Follows the group-conversation store's
     * erasure contract — pages until an empty pass, re-checks ownership by exact
     * match in Java, and throws rather than reporting a partial erasure as success.
     *
     * @return how many artifacts were removed
     */
    long deleteAllForUser(String userId) throws IResourceStore.ResourceStoreException;

    /**
     * Unchecked "deleted concurrently" — thrown by {@link #updateIfVersion} so CAS
     * call sites can distinguish a retryable conflict (409) from a gone document
     * (404) without a checked-exception cascade.
     */
    class ArtifactGoneException extends RuntimeException {
        public ArtifactGoneException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
