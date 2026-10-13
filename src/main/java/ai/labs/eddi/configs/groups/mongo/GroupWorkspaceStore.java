/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.mongo;

import ai.labs.eddi.configs.groups.IGroupWorkspaceStore;
import ai.labs.eddi.configs.groups.model.GroupWorkspace;
import ai.labs.eddi.datastore.IResourceFilter;
import ai.labs.eddi.datastore.IResourceStorage;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import ai.labs.eddi.utils.LogSanitizer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.regex.Pattern;

/**
 * DB-agnostic store for {@link GroupWorkspace} documents (I13). Follows
 * {@link GroupConversationStore}'s single-version pattern; workspaces are keyed
 * logically by {@code groupId} (one document per group), physically by the
 * storage id.
 *
 * @author ginccc
 */
@ApplicationScoped
public class GroupWorkspaceStore implements IGroupWorkspaceStore {

    private static final Logger LOGGER = Logger.getLogger(GroupWorkspaceStore.class);
    private static final int SINGLE_VERSION = 1;
    private static final String REVISION_FIELD = "revision";
    /**
     * {@link GroupWorkspace}'s field default — what a revision-less document
     * deserializes to.
     */
    private static final String INITIAL_REVISION = "0";

    /** Same reasoning as {@link GroupConversationStore}'s SAFE_ID. */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9-]+");

    private final IResourceStorage<GroupWorkspace> storage;

    @Inject
    public GroupWorkspaceStore(IResourceStorageFactory storageFactory, IDocumentBuilder documentBuilder) {
        this.storage = storageFactory.create("groupworkspaces", documentBuilder, GroupWorkspace.class, "groupId");
    }

    @Override
    public GroupWorkspace find(String groupId) throws IResourceStore.ResourceStoreException {
        if (groupId == null || groupId.isBlank() || !SAFE_ID.matcher(groupId).matches()) {
            return null;
        }
        try {
            // The canonical document first: since workspaces are keyed physically by
            // their group id (see readOrCreate), this is one primary-key read for every
            // workspace created from this release on.
            GroupWorkspace canonical = readCanonical(groupId);
            if (canonical != null) {
                return canonical;
            }
            // Legacy: workspaces created before that carry a generated storage id and
            // are found by their groupId field.
            List<IResourceStore.IResourceId> ids = findWorkspaceIds(groupId);
            if (ids.isEmpty()) {
                return null;
            }
            String survivorId = survivorId(ids);
            if (ids.size() > 1) {
                LOGGER.warnf("Group %s has %d workspace documents — a concurrent readOrCreate raced its "
                        + "duplicate guard; reading the deterministic survivor %s",
                        LogSanitizer.sanitize(groupId), ids.size(), survivorId);
            }
            IResourceStorage.IResource<GroupWorkspace> resource = storage.read(survivorId, SINGLE_VERSION);
            if (resource == null) {
                return null;
            }
            GroupWorkspace workspace = resource.getData();
            workspace.setId(survivorId);
            return workspace;
        } catch (IOException e) {
            throw new IResourceStore.ResourceStoreException("Failed to read group workspace: " + e.getMessage(), e);
        }
    }

    @Override
    public GroupWorkspace readOrCreate(String groupId) throws IResourceStore.ResourceStoreException {
        GroupWorkspace existing = find(groupId);
        if (existing != null) {
            return existing;
        }
        var workspace = new GroupWorkspace();
        workspace.setGroupId(groupId);
        workspace.setCreated(Instant.now());
        workspace.setLastModified(Instant.now());

        // Atomic path: the workspace's storage id IS its group id, and the insert is
        // insert-only (createNew), so the database's primary key admits exactly one
        // creator. Every other racer's insert fails and it adopts the winner's
        // document. The old path below needed a re-query to converge, and that left a
        // window: a racer whose re-query ran before a second insert landed returned
        // its own document and wrote a backlog task to it — and the "smallest id"
        // survivor rule could then elect the OTHER document, which every later read
        // follows, so the task was gone (review finding). Smallest id is not first
        // insert either: ObjectIds are minted client-side and UUIDs are random.
        Boolean created = createCanonical(workspace);
        if (Boolean.TRUE.equals(created)) {
            return workspace;
        }
        if (Boolean.FALSE.equals(created)) {
            GroupWorkspace winner = find(groupId);
            if (winner != null) {
                return winner;
            }
            // Deleted again between the failed insert and the read — vanishingly rare;
            // fall through and create through the legacy path rather than fail.
        }

        // Fallback for a group id this backend cannot use as a storage id (a group
        // migrated from the other backend keeps its foreign id format).
        try {
            IResourceStorage.IResource<GroupWorkspace> resource = storage.newResource(workspace);
            storage.store(resource);
            workspace.setId(resource.getId());
        } catch (IOException e) {
            throw new IResourceStore.ResourceStoreException("Failed to create group workspace: " + e.getMessage(), e);
        }
        // Duplicate-insert guard (review finding): the storage abstraction has no
        // unique constraint on groupId, so two concurrent creators can both miss
        // find() above and insert. Re-query: every racer that does NOT hold the
        // deterministic survivor removes ITS OWN insert and adopts the survivor —
        // all callers converge on one document. find() picks the same survivor,
        // so even a racer that crashes before this guard cannot split writes.
        List<IResourceStore.IResourceId> ids = findWorkspaceIds(groupId);
        if (ids.size() > 1) {
            String survivorId = survivorId(ids);
            if (!survivorId.equals(workspace.getId())) {
                LOGGER.infof("readOrCreate raced for group %s — removing this caller's duplicate %s, adopting %s",
                        LogSanitizer.sanitize(groupId), workspace.getId(), survivorId);
                storage.removeAllPermanently(workspace.getId());
                GroupWorkspace survivor = find(groupId);
                if (survivor != null) {
                    return survivor;
                }
            }
        }
        return workspace;
    }

    /**
     * Inserts {@code workspace} under its group id as the storage id.
     *
     * @return {@code TRUE} when this call created it; {@code FALSE} when the insert
     *         failed because another caller's document already holds the id (lost
     *         the race — adopt theirs); {@code null} when the group id cannot be a
     *         storage id on this backend, so the caller must use a generated one
     */
    private Boolean createCanonical(GroupWorkspace workspace) {
        String groupId = workspace.getGroupId();
        IResourceStorage.IResource<GroupWorkspace> resource;
        try {
            resource = storage.newResource(groupId, SINGLE_VERSION, workspace);
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
        try {
            storage.createNew(resource);
            workspace.setId(groupId);
            return Boolean.TRUE;
        } catch (RuntimeException e) {
            // A duplicate-key violation on either backend: the primary key is the
            // arbiter. Anything else means the id is unusable here — told apart by
            // whether a document now exists under it, not by backend error text.
            try {
                if (readCanonical(groupId) != null) {
                    LOGGER.debugf("readOrCreate raced for group %s — adopting the concurrently created workspace",
                            LogSanitizer.sanitize(groupId));
                    return Boolean.FALSE;
                }
            } catch (IOException readFailure) {
                LOGGER.debugf("Could not re-read workspace %s after a failed insert: %s", LogSanitizer.sanitize(groupId),
                        readFailure.getMessage());
            }
            LOGGER.debugf("Group id %s cannot key a workspace on this backend (%s); using a generated id",
                    LogSanitizer.sanitize(groupId), e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * The workspace stored under the group id itself, or {@code null}. An id the
     * backend cannot parse names nothing.
     */
    private GroupWorkspace readCanonical(String groupId) throws IOException {
        IResourceStorage.IResource<GroupWorkspace> resource;
        try {
            resource = storage.read(groupId, SINGLE_VERSION);
        } catch (RuntimeException e) {
            return null;
        }
        if (resource == null) {
            return null;
        }
        GroupWorkspace workspace = resource.getData();
        if (workspace == null || !groupId.equals(workspace.getGroupId())) {
            return null;
        }
        workspace.setId(groupId);
        return workspace;
    }

    private List<IResourceStore.IResourceId> findWorkspaceIds(String groupId) {
        var filter = new IResourceFilter.QueryFilters(
                List.of(new IResourceFilter.QueryFilter("groupId", "^" + groupId + "$")));
        // High enough that EVERY realistic racer set fits in one query — with a
        // small limit, three-plus concurrent creators could each see a different
        // subset and compute different survivors, and the documents would never
        // converge (review finding).
        List<IResourceStore.IResourceId> ids = storage.findResources(
                new IResourceFilter.QueryFilters[]{filter}, "lastModified", 0, 50);
        return ids != null ? ids : List.of();
    }

    /**
     * The lexicographically smallest id — for ObjectIds that is the EARLIEST
     * insert, and every caller (both racers and later readers) derives the same
     * answer with no coordination.
     */
    private static String survivorId(List<IResourceStore.IResourceId> ids) {
        return ids.stream().map(IResourceStore.IResourceId::getId).sorted().findFirst().orElseThrow();
    }

    @Override
    public void deleteByGroupId(String groupId) throws IResourceStore.ResourceStoreException {
        GroupWorkspace workspace = find(groupId);
        if (workspace != null && workspace.getId() != null) {
            storage.removeAllPermanently(workspace.getId());
            LOGGER.infof("Deleted workspace for group %s", LogSanitizer.sanitize(groupId));
        }
    }

    @Override
    public boolean casRevision(GroupWorkspace workspace) throws IResourceStore.ResourceStoreException {
        return conditionalWrite(workspace);
    }

    @Override
    public boolean casRunningDiscussion(GroupWorkspace workspace) throws IResourceStore.ResourceStoreException {
        return conditionalWrite(workspace);
    }

    /**
     * The single conditional-write scheme every workspace write goes through
     * (H14c).
     * <p>
     * There used to be three: {@code casRevision} compared the revision,
     * {@code casRunningDiscussion} compared the run claim, and cadence edits wrote
     * unconditionally — each a whole-document replace that ignored the others'
     * field. A backlog add read before a cadence claim then passed its revision
     * check after it, writing the claim away (runningDiscussionId cleared, the
     * pulled tasks back to PENDING): the run was orphaned and its tasks were pulled
     * a second time. The claim, in turn, wrote back a backlog that no longer held a
     * concurrently added task.
     * <p>
     * Now the revision is the one guard. Every write compares it and bumps it, so a
     * write lands only if nothing at all changed since the caller's read — which is
     * also exactly what "the claim is still what I read" means.
     * <p>
     * <b>A stored document without a revision.</b> Every release that shipped
     * workspaces (6.3.0 on) persists the field — it defaults to {@code "0"} and the
     * serializer writes it — but a document lacking it (hand-restored, or written
     * by a pre-release build) would otherwise match no write ever: Jackson fills
     * the absent field with the {@code "0"} default, the CAS compares that against
     * a missing stored value, and on both backends a missing value equals nothing.
     * Every backlog and cadence write would exhaust its retries and 409. So the
     * write at revision {@code "0"} (or an explicit {@code null}) also matches a
     * stored document with no revision, inside the same atomic write
     * ({@code storeIfFieldEqualsOrMissing}), and stamps {@code "1"}. It stays a
     * CAS: of two writers that both read the revision-less document, the first
     * stamps the field and the second then matches neither branch and loses. A
     * higher revision can only have been read from a stored field, so it keeps the
     * strict comparison. A non-numeric revision is a corrupt document and fails
     * loudly.
     *
     * @return {@code false} if any concurrent write landed first (re-read before
     *         retrying), or the workspace was deleted
     */
    private boolean conditionalWrite(GroupWorkspace workspace) throws IResourceStore.ResourceStoreException {
        String expected = workspace.getRevision();
        boolean mayBeUnstamped = expected == null || INITIAL_REVISION.equals(expected);
        String bumped;
        try {
            bumped = String.valueOf(Long.parseLong(expected != null ? expected : INITIAL_REVISION) + 1);
        } catch (NumberFormatException e) {
            // A corrupt revision must surface through the method's declared error
            // model, not as an uncaught runtime exception the REST layer's generic
            // handler turns into a bare 500 (CodeQL).
            throw new IResourceStore.ResourceStoreException(
                    "Workspace revision for group " + workspace.getGroupId() + " is not numeric: '" + expected + "'", e);
        }
        workspace.setRevision(bumped);
        workspace.setLastModified(Instant.now());
        try {
            IResourceStorage.IResource<GroupWorkspace> resource = storage.newResource(workspace.getId(), SINGLE_VERSION, workspace);
            // Conditional on the PERSISTED value — cross-process atomicity, same as
            // GroupConversationStore.updateIfState. An in-JVM check would only
            // serialize one pod's writers against each other.
            if (mayBeUnstamped) {
                storage.storeIfFieldEqualsOrMissing(resource, REVISION_FIELD, INITIAL_REVISION);
            } else {
                storage.storeIfFieldEquals(resource, REVISION_FIELD, expected);
            }
            return true;
        } catch (IResourceStore.ResourceModifiedException e) {
            workspace.setRevision(expected);
            return false;
        } catch (IResourceStore.ResourceNotFoundException e) {
            // Workspace deleted (group teardown) while a write was in flight — a lost
            // write rather than an error; a run claim in particular must not start.
            LOGGER.warnf("Workspace %s disappeared during a conditional write", LogSanitizer.sanitize(workspace.getGroupId()));
            workspace.setRevision(expected);
            return false;
        } catch (IOException e) {
            workspace.setRevision(expected);
            throw new IResourceStore.ResourceStoreException("Failed conditional workspace update: " + e.getMessage(), e);
        }
    }
}
