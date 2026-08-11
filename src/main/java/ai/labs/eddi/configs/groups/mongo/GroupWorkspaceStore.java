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
    public void update(GroupWorkspace workspace) throws IResourceStore.ResourceStoreException {
        try {
            workspace.setLastModified(Instant.now());
            IResourceStorage.IResource<GroupWorkspace> resource = storage.newResource(workspace.getId(), SINGLE_VERSION, workspace);
            storage.store(resource);
        } catch (IOException e) {
            throw new IResourceStore.ResourceStoreException("Failed to update group workspace: " + e.getMessage(), e);
        }
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
        String expected = workspace.getRevision();
        String bumped;
        if (expected == null) {
            bumped = "1";
        } else {
            try {
                bumped = String.valueOf(Long.parseLong(expected) + 1);
            } catch (NumberFormatException e) {
                // A corrupt revision must surface through the method's declared
                // error model, not as an uncaught runtime exception the REST
                // layer's generic handler turns into a bare 500 (CodeQL).
                throw new IResourceStore.ResourceStoreException(
                        "Workspace revision for group " + workspace.getGroupId() + " is not numeric: '"
                                + expected + "'",
                        e);
            }
        }
        workspace.setRevision(bumped);
        workspace.setLastModified(Instant.now());
        if (expected == null) {
            // Pre-revision document: no field to compare against. One plain write
            // stamps the revision; every later write on this document is CAS'd.
            update(workspace);
            return true;
        }
        try {
            IResourceStorage.IResource<GroupWorkspace> resource = storage.newResource(workspace.getId(), SINGLE_VERSION, workspace);
            storage.storeIfFieldEquals(resource, "revision", expected);
            return true;
        } catch (IResourceStore.ResourceModifiedException e) {
            workspace.setRevision(expected);
            return false;
        } catch (IResourceStore.ResourceNotFoundException e) {
            LOGGER.warnf("Workspace %s disappeared during a revision-checked write", LogSanitizer.sanitize(workspace.getGroupId()));
            workspace.setRevision(expected);
            return false;
        } catch (IOException e) {
            throw new IResourceStore.ResourceStoreException("Failed revision-checked workspace update: " + e.getMessage(), e);
        }
    }

    @Override
    public boolean casRunningDiscussion(GroupWorkspace workspace, String expectedRunning)
            throws IResourceStore.ResourceStoreException {
        try {
            workspace.setLastModified(Instant.now());
            IResourceStorage.IResource<GroupWorkspace> resource = storage.newResource(workspace.getId(), SINGLE_VERSION, workspace);
            // Conditional on the PERSISTED value — same cross-process atomicity as
            // GroupConversationStore.updateIfState. An in-JVM check would only
            // serialize one pod's cadence fires against itself.
            storage.storeIfFieldEquals(resource, "runningDiscussionId",
                    expectedRunning != null ? expectedRunning : GroupWorkspace.NO_RUNNING_DISCUSSION);
            return true;
        } catch (IResourceStore.ResourceModifiedException e) {
            return false;
        } catch (IResourceStore.ResourceNotFoundException e) {
            // Workspace deleted (group teardown) while a claim was in flight —
            // treat as a lost claim rather than an error; the run must not start.
            LOGGER.warnf("Workspace %s disappeared during a run claim", LogSanitizer.sanitize(workspace.getGroupId()));
            return false;
        } catch (IOException e) {
            throw new IResourceStore.ResourceStoreException("Failed conditional workspace update: " + e.getMessage(), e);
        }
    }
}
