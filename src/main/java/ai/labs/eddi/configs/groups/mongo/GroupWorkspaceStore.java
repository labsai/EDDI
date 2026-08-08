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
            var filter = new IResourceFilter.QueryFilters(
                    List.of(new IResourceFilter.QueryFilter("groupId", "^" + groupId + "$")));
            List<IResourceStore.IResourceId> ids = storage.findResources(
                    new IResourceFilter.QueryFilters[]{filter}, "lastModified", 0, 1);
            if (ids == null || ids.isEmpty()) {
                return null;
            }
            IResourceStorage.IResource<GroupWorkspace> resource = storage.read(ids.get(0).getId(), SINGLE_VERSION);
            if (resource == null) {
                return null;
            }
            GroupWorkspace workspace = resource.getData();
            workspace.setId(ids.get(0).getId());
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
            return workspace;
        } catch (IOException e) {
            throw new IResourceStore.ResourceStoreException("Failed to create group workspace: " + e.getMessage(), e);
        }
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
            LOGGER.warnf("Workspace %s disappeared during a run claim", workspace.getGroupId());
            return false;
        } catch (IOException e) {
            throw new IResourceStore.ResourceStoreException("Failed conditional workspace update: " + e.getMessage(), e);
        }
    }
}
