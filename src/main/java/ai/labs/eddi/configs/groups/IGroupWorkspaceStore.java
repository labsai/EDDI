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
     * The group's workspace, created empty on first access. Creation is atomic: the
     * workspace is stored under the group id itself with an insert-only write, so
     * of two concurrent creators — on one node or several — exactly one inserts and
     * every other adopts that same document. All callers get the one workspace
     * every later read returns.
     */
    GroupWorkspace readOrCreate(String groupId) throws IResourceStore.ResourceStoreException;

    /** Deletes the group's workspace, if any. Idempotent. */
    void deleteByGroupId(String groupId) throws IResourceStore.ResourceStoreException;

    /**
     * Atomically claims (or settles) the workspace for one cadence discussion:
     * writes {@code workspace} (which must already carry the new
     * {@code runningDiscussionId} and pulled-task state) only if nothing changed
     * since the caller read it — the same revision guard as {@link #casRevision},
     * so a claim can neither drop a concurrent backlog edit nor be dropped by one
     * (H14c). An unchanged revision implies an unchanged claim, so the caller's
     * read of {@code runningDiscussionId} is what the write is conditioned on.
     * Returns {@code false} when any concurrent write landed first — the caller
     * re-reads and decides whether the claim is still its to take.
     */
    boolean casRunningDiscussion(GroupWorkspace workspace) throws IResourceStore.ResourceStoreException;

    /**
     * Optimistic-concurrency write: persists {@code workspace} only if its
     * {@code revision} still matches what this caller read, bumping it in the same
     * write. Use for every read-modify-write surface (backlog adds) so two
     * concurrent editors cannot silently drop each other's changes — the loser
     * re-reads and retries.
     * <p>
     * There is no unconditional write on this store: every write — backlog,
     * cadences, run claims, writebacks — goes through this revision guard (H14c). A
     * stored document with no {@code revision} (none that a release wrote — the
     * field has existed as long as workspaces have — but a hand-restored or
     * pre-release one) reads back as the {@code "0"} default; the write at
     * {@code "0"} also matches a stored document without the field, in the same
     * atomic compare, and stamps {@code "1"}. Of two writers racing on such a
     * document only the first lands, so it too never lands blind.
     *
     * @return {@code true} if the write landed; {@code false} if a concurrent
     *         writer changed the workspace first (re-read before retrying)
     */
    boolean casRevision(GroupWorkspace workspace) throws IResourceStore.ResourceStoreException;
}
