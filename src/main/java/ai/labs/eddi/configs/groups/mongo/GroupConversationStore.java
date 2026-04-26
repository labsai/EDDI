/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.mongo;

import ai.labs.eddi.configs.groups.IGroupConversationStore;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.datastore.IResourceStorage;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * DB-agnostic store for group conversation transcripts. Uses
 * {@link IResourceStorageFactory} to create storage backed by either MongoDB or
 * PostgreSQL.
 * <p>
 * Group conversations are non-versioned entities (single version = 1).
 *
 * @author ginccc
 */
@ApplicationScoped
public class GroupConversationStore implements IGroupConversationStore {

    private static final Logger LOGGER = Logger.getLogger(GroupConversationStore.class);
    private static final int SINGLE_VERSION = 1;

    private final IResourceStorage<GroupConversation> storage;

    @Inject
    public GroupConversationStore(IResourceStorageFactory storageFactory, IDocumentBuilder documentBuilder) {
        this.storage = storageFactory.create("groupconversations", documentBuilder, GroupConversation.class, "groupId", "state");
    }

    @Override
    public String create(GroupConversation conversation) throws IResourceStore.ResourceStoreException {
        try {
            IResourceStorage.IResource<GroupConversation> resource = storage.newResource(conversation);
            storage.store(resource);
            String id = resource.getId();
            conversation.setId(id);
            return id;
        } catch (IOException e) {
            throw new IResourceStore.ResourceStoreException("Failed to create group conversation: " + e.getMessage(), e);
        }
    }

    @Override
    public GroupConversation read(String id) throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {
        try {
            IResourceStorage.IResource<GroupConversation> resource = storage.read(id, SINGLE_VERSION);
            if (resource == null) {
                throw new IResourceStore.ResourceNotFoundException("Group conversation not found: " + id);
            }
            GroupConversation conversation = resource.getData();
            conversation.setId(id);
            return conversation;
        } catch (IOException e) {
            throw new IResourceStore.ResourceStoreException("Failed to read group conversation: " + e.getMessage(), e);
        }
    }

    @Override
    public void update(GroupConversation conversation) throws IResourceStore.ResourceStoreException {
        try {
            IResourceStorage.IResource<GroupConversation> resource = storage.newResource(conversation.getId(), SINGLE_VERSION, conversation);
            storage.store(resource);
        } catch (IOException e) {
            throw new IResourceStore.ResourceStoreException("Failed to update group conversation: " + e.getMessage(), e);
        }
    }

    @Override
    public void delete(String id) throws IResourceStore.ResourceStoreException {
        storage.removeAllPermanently(id);
    }

    @Override
    public List<GroupConversation> listByGroupId(String groupId, int index, int limit) throws IResourceStore.ResourceStoreException {
        // For now, use a simple approach — filter by groupId.
        // The IResourceStorage.findResources() API can be used for more complex
        // queries.
        // This is a placeholder that works with both DB backends.
        var results = new ArrayList<GroupConversation>();
        try {
            var filter = new ai.labs.eddi.datastore.IResourceFilter.QueryFilters(
                    java.util.List.of(new ai.labs.eddi.datastore.IResourceFilter.QueryFilter("groupId", groupId)));
            var resourceIds = storage.findResources(new ai.labs.eddi.datastore.IResourceFilter.QueryFilters[]{filter}, "lastModified", index, limit);
            for (var resourceId : resourceIds) {
                try {
                    var resource = storage.read(resourceId.getId(), SINGLE_VERSION);
                    if (resource != null) {
                        GroupConversation gc = resource.getData();
                        gc.setId(resourceId.getId());
                        results.add(gc);
                    }
                } catch (IOException e) {
                    LOGGER.warnf("Failed to read group conversation %s: %s", resourceId.getId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            throw new IResourceStore.ResourceStoreException("Failed to list group conversations: " + e.getMessage(), e);
        }
        return results;
    }
}
