/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.properties.rest;

import ai.labs.eddi.configs.properties.IRestUserMemoryStore;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
import ai.labs.eddi.datastore.IResourceStore;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;

/**
 * REST implementation for persistent user memory operations.
 *
 * @author ginccc
 * @since 6.0.0
 */
@ApplicationScoped
public class RestUserMemoryStore implements IRestUserMemoryStore {

    private final IUserMemoryStore userMemoryStore;

    private static final Logger LOGGER = Logger.getLogger(RestUserMemoryStore.class);

    @Inject
    public RestUserMemoryStore(IUserMemoryStore userMemoryStore) {
        this.userMemoryStore = userMemoryStore;
    }

    @Override
    public List<UserMemoryEntry> getAllMemories(String userId) {
        try {
            return userMemoryStore.getAllEntries(userId);
        } catch (IResourceStore.ResourceStoreException e) {
            LOGGER.error("Failed to get all memories for user: " + userId, e);
            throw new InternalServerErrorException(e.getLocalizedMessage());
        }
    }

    @Override
    public List<UserMemoryEntry> getVisibleMemories(String userId, String agentId, List<String> groupIds, String recallOrder, int maxEntries) {
        try {
            return userMemoryStore.getVisibleEntries(userId, agentId, groupIds != null ? groupIds : List.of(), recallOrder, maxEntries);
        } catch (IResourceStore.ResourceStoreException e) {
            LOGGER.error("Failed to get visible memories for user: " + userId, e);
            throw new InternalServerErrorException(e.getLocalizedMessage());
        }
    }

    @Override
    public List<UserMemoryEntry> searchMemories(String userId, String query) {
        try {
            return userMemoryStore.filterEntries(userId, query);
        } catch (IResourceStore.ResourceStoreException e) {
            LOGGER.error("Failed to search memories for user: " + userId, e);
            throw new InternalServerErrorException(e.getLocalizedMessage());
        }
    }

    @Override
    public List<UserMemoryEntry> getMemoriesByCategory(String userId, String category) {
        try {
            return userMemoryStore.getEntriesByCategory(userId, category);
        } catch (IResourceStore.ResourceStoreException e) {
            LOGGER.error("Failed to get memories by category for user: " + userId, e);
            throw new InternalServerErrorException(e.getLocalizedMessage());
        }
    }

    @Override
    public Response getMemoryByKey(String userId, String key) {
        try {
            var entry = userMemoryStore.getByKey(userId, key);
            if (entry.isPresent()) {
                return Response.ok(entry.get()).build();
            } else {
                throw new NotFoundException("No memory with key '" + key + "' found for user: " + userId);
            }
        } catch (IResourceStore.ResourceStoreException e) {
            LOGGER.error("Failed to get memory by key for user: " + userId, e);
            throw new InternalServerErrorException(e.getLocalizedMessage());
        }
    }

    @Override
    public Response upsertMemory(UserMemoryEntry entry) {
        if (entry == null) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "Request body is required")).build();
        }
        if (entry.userId() == null || entry.userId().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "userId is required")).build();
        }
        if (entry.key() == null || entry.key().isBlank()) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "key is required")).build();
        }
        if (entry.key().length() > 255) {
            return Response.status(Response.Status.BAD_REQUEST).entity(Map.of("error", "key must not exceed 255 characters")).build();
        }
        try {
            String id = userMemoryStore.upsert(entry);
            return Response.ok(Map.of("id", id)).build();
        } catch (IResourceStore.ResourceStoreException e) {
            LOGGER.error("Failed to upsert memory entry", e);
            throw new InternalServerErrorException(e.getLocalizedMessage());
        }
    }

    @Override
    public Response deleteMemory(String entryId) {
        try {
            userMemoryStore.deleteEntry(entryId);
            return Response.noContent().build();
        } catch (IResourceStore.ResourceStoreException e) {
            LOGGER.error("Failed to delete memory entry: " + entryId, e);
            throw new InternalServerErrorException(e.getLocalizedMessage());
        }
    }

    @Override
    public Response deleteAllForUser(String userId) {
        try {
            userMemoryStore.deleteAllForUser(userId);
            return Response.noContent().build();
        } catch (IResourceStore.ResourceStoreException e) {
            LOGGER.error("Failed to delete all memories for user: " + userId, e);
            throw new InternalServerErrorException(e.getLocalizedMessage());
        }
    }

    @Override
    public Response countMemories(String userId) {
        try {
            long count = userMemoryStore.countEntries(userId);
            return Response.ok(Map.of("userId", userId, "count", count)).build();
        } catch (IResourceStore.ResourceStoreException e) {
            LOGGER.error("Failed to count memories for user: " + userId, e);
            throw new InternalServerErrorException(e.getLocalizedMessage());
        }
    }
}
