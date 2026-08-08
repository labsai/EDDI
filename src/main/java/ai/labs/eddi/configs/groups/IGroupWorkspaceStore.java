/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups;

import ai.labs.eddi.configs.groups.model.GroupWorkspace;
import ai.labs.eddi.datastore.IResourceStore;

/**
 * Store for {@link GroupWorkspace} documents (I13) — one per group config id,
 * own collection.
 *
 * @author ginccc
 */
public interface IGroupWorkspaceStore {

    /**
     * The group's workspace, or {@code null} if none exists yet. Never creates.
     */
    GroupWorkspace find(String groupId) throws IResourceStore.ResourceStoreException;

    /**
     * The group's workspace, created empty on first access. Creation races are
     * benign for an empty document: if two callers create concurrently, the later
     * writer's empty workspace wins and neither loses data it had.
     */
    GroupWorkspace readOrCreate(String groupId) throws IResourceStore.ResourceStoreException;

    void update(GroupWorkspace workspace) throws IResourceStore.ResourceStoreException;

    /** Deletes the group's workspace, if any. Idempotent. */
    void deleteByGroupId(String groupId) throws IResourceStore.ResourceStoreException;

    /**
     * Atomically claims the workspace for one cadence discussion: writes
     * {@code workspace} (which must already carry the new
     * {@code runningDiscussionId} and pulled-task state) only if the PERSISTED
     * {@code runningDiscussionId} still equals {@code expectedRunning}. Returns
     * {@code false} when another pod won the claim (or released it) in between —
     * the caller skips its run.
     */
    boolean casRunningDiscussion(GroupWorkspace workspace, String expectedRunning)
            throws IResourceStore.ResourceStoreException;

    /**
     * Optimistic-concurrency write: persists {@code workspace} only if its
     * {@code revision} still matches what this caller read, bumping it in the same
     * write. Use for every read-modify-write surface (backlog adds) so two
     * concurrent editors cannot silently drop each other's changes — the loser
     * re-reads and retries.
     * <p>
     * Known bound: a document created before the {@code revision} field existed
     * carries {@code null} — the first write on such a document is a plain
     * (unconditional) stamp, so two callers that BOTH read the pre-revision shape
     * can still race that one write. The window is one write per legacy document;
     * every write after the stamp is CAS'd.
     *
     * @return {@code true} if the write landed; {@code false} if a concurrent
     *         writer changed the workspace first (re-read before retrying)
     */
    boolean casRevision(GroupWorkspace workspace) throws IResourceStore.ResourceStoreException;
}
