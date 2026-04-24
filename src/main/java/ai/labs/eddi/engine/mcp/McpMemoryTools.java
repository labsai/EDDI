/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.properties.model.Property.Visibility;
import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.engine.mcp.McpToolUtils.errorJson;
import static ai.labs.eddi.engine.mcp.McpToolUtils.requireRole;

/**
 * MCP tools for managing persistent user memories. Exposes CRUD, search, and
 * GDPR-compliant deletion as MCP-compliant tools.
 *
 * <p>
 * Phase 11a — Persistent User Memory MCP integration.
 *
 * @author ginccc
 * @since 6.0.0
 */
@ApplicationScoped
public class McpMemoryTools {

    private static final Logger LOGGER = Logger.getLogger(McpMemoryTools.class);

    private final IUserMemoryStore userMemoryStore;
    private final IJsonSerialization jsonSerialization;
    private final SecurityIdentity identity;
    private final boolean authEnabled;

    @Inject
    public McpMemoryTools(IUserMemoryStore userMemoryStore, IJsonSerialization jsonSerialization, SecurityIdentity identity,
            @ConfigProperty(name = "authorization.enabled", defaultValue = "false") boolean authEnabled) {
        this.userMemoryStore = userMemoryStore;
        this.jsonSerialization = jsonSerialization;
        this.identity = identity;
        this.authEnabled = authEnabled;
    }

    @Tool(name = "list_user_memories", description = "List all persistent memory entries for a user. "
            + "Returns structured facts, preferences, and context that agents have remembered about the user.")
    public String listUserMemories(@ToolArg(description = "User ID (required)") String userId,
                                   @ToolArg(description = "Maximum number of entries to return (default: 50)") Integer limit) {
        requireRole(identity, authEnabled, "eddi-viewer");
        if (userId == null || userId.isBlank())
            return errorJson("userId is required");
        try {
            var entries = userMemoryStore.getAllEntries(userId);
            int maxEntries = limit != null && limit > 0 ? limit : 50;
            if (entries.size() > maxEntries) {
                entries = entries.subList(0, maxEntries);
            }

            var result = new LinkedHashMap<String, Object>();
            result.put("userId", userId);
            result.put("count", entries.size());
            result.put("memories", entries);
            return jsonSerialization.serialize(result);
        } catch (Exception e) {
            LOGGER.error("MCP list_user_memories failed for user: " + userId, e);
            return errorJson("Failed to list memories: " + e.getMessage());
        }
    }

    @Tool(name = "get_visible_memories", description = "Get memories visible to a specific agent, "
            + "considering self/group/global visibility scopes. "
            + "Returns the memories that would be injected into a conversation with this agent.")
    public String getVisibleMemories(@ToolArg(description = "User ID (required)") String userId,
                                     @ToolArg(description = "Agent ID to check visibility for (required)") String agentId,
                                     @ToolArg(description = "Comma-separated group IDs (optional)") String groupIds,
                                     @ToolArg(description = "Recall order: 'most_recent' or 'most_accessed' (default: most_recent)") String order,
                                     @ToolArg(description = "Maximum number of entries (default: 50)") Integer limit) {
        requireRole(identity, authEnabled, "eddi-viewer");
        if (userId == null || userId.isBlank())
            return errorJson("userId is required");
        if (agentId == null || agentId.isBlank())
            return errorJson("agentId is required");
        try {
            List<String> groups = groupIds != null && !groupIds.isBlank() ? List.of(groupIds.split(",")) : List.of();
            String recallOrder = order != null && !order.isBlank() ? order : "most_recent";
            int maxEntries = limit != null && limit > 0 ? limit : 50;

            var entries = userMemoryStore.getVisibleEntries(userId, agentId, groups, recallOrder, maxEntries);

            var result = new LinkedHashMap<String, Object>();
            result.put("userId", userId);
            result.put("agentId", agentId);
            result.put("count", entries.size());
            result.put("memories", entries);
            return jsonSerialization.serialize(result);
        } catch (Exception e) {
            LOGGER.error("MCP get_visible_memories failed", e);
            return errorJson("Failed to get visible memories: " + e.getMessage());
        }
    }

    @Tool(name = "search_user_memories", description = "Search user memories by keyword. " + "Filters across memory keys and values.")
    public String searchUserMemories(@ToolArg(description = "User ID (required)") String userId,
                                     @ToolArg(description = "Search query (required)") String query) {
        requireRole(identity, authEnabled, "eddi-viewer");
        if (userId == null || userId.isBlank())
            return errorJson("userId is required");
        if (query == null || query.isBlank())
            return errorJson("query is required");
        try {
            var entries = userMemoryStore.filterEntries(userId, query);

            var result = new LinkedHashMap<String, Object>();
            result.put("userId", userId);
            result.put("query", query);
            result.put("count", entries.size());
            result.put("memories", entries);
            return jsonSerialization.serialize(result);
        } catch (Exception e) {
            LOGGER.error("MCP search_user_memories failed", e);
            return errorJson("Failed to search memories: " + e.getMessage());
        }
    }

    @Tool(name = "get_memory_by_key", description = "Get a specific memory entry by its key name.")
    public String getMemoryByKey(@ToolArg(description = "User ID (required)") String userId,
                                 @ToolArg(description = "Memory key name (required)") String key) {
        requireRole(identity, authEnabled, "eddi-viewer");
        if (userId == null || userId.isBlank())
            return errorJson("userId is required");
        if (key == null || key.isBlank())
            return errorJson("key is required");
        try {
            var entry = userMemoryStore.getByKey(userId, key);
            if (entry.isPresent()) {
                return jsonSerialization.serialize(entry.get());
            } else {
                return errorJson("No memory found with key '" + key + "' for user: " + userId);
            }
        } catch (Exception e) {
            LOGGER.error("MCP get_memory_by_key failed", e);
            return errorJson("Failed to get memory: " + e.getMessage());
        }
    }

    @Tool(name = "upsert_user_memory", description = "Insert or update a user memory entry. "
            + "Upsert semantics: self/group memories are keyed by (userId, key, agentId); " + "global memories are keyed by (userId, key).")
    public String upsertUserMemory(@ToolArg(description = "User ID (required)") String userId,
                                   @ToolArg(description = "Memory key/name (required)") String key,
                                   @ToolArg(description = "Memory value (required)") String value,
                                   @ToolArg(description = "Source agent ID (required)") String agentId,
                                   @ToolArg(description = "Category: 'preference', 'fact', or 'context' (default: fact)") String category,
                                   @ToolArg(description = "Visibility: 'self', 'group', or 'global' (default: self)") String visibility) {
        requireRole(identity, authEnabled, "eddi-admin");
        if (userId == null || userId.isBlank())
            return errorJson("userId is required");
        if (key == null || key.isBlank())
            return errorJson("key is required");
        if (value == null || value.isBlank())
            return errorJson("value is required");
        if (agentId == null || agentId.isBlank())
            return errorJson("agentId is required");
        try {
            var vis = visibility != null && !visibility.isBlank() ? Visibility.valueOf(visibility.toLowerCase()) : Visibility.self;

            var entry = UserMemoryEntry.fromToolCall(userId, agentId, null, List.of(), key, value, category, vis);

            String id = userMemoryStore.upsert(entry);

            return jsonSerialization.serialize(Map.of("id", id, "key", key, "status", "upserted"));
        } catch (IllegalArgumentException e) {
            return errorJson("Invalid visibility value. Use: self, group, or global");
        } catch (Exception e) {
            LOGGER.error("MCP upsert_user_memory failed", e);
            return errorJson("Failed to upsert memory: " + e.getMessage());
        }
    }

    @Tool(name = "delete_user_memory", description = "Delete a specific memory entry by its database ID.")
    public String deleteUserMemory(@ToolArg(description = "Memory entry ID (required)") String entryId) {
        requireRole(identity, authEnabled, "eddi-admin");
        if (entryId == null || entryId.isBlank())
            return errorJson("entryId is required");
        try {
            userMemoryStore.deleteEntry(entryId);
            return jsonSerialization.serialize(Map.of("entryId", entryId, "status", "deleted"));
        } catch (Exception e) {
            LOGGER.error("MCP delete_user_memory failed", e);
            return errorJson("Failed to delete memory: " + e.getMessage());
        }
    }

    @Tool(name = "delete_all_user_memories", description = "Delete ALL memories for a user (GDPR right-to-erasure). "
            + "This action is irreversible!")
    public String deleteAllUserMemories(@ToolArg(description = "User ID (required)") String userId,
                                        @ToolArg(description = "Confirmation: must be 'CONFIRM' to proceed") String confirmation) {
        requireRole(identity, authEnabled, "eddi-admin");
        if (userId == null || userId.isBlank())
            return errorJson("userId is required");
        if (!"CONFIRM".equals(confirmation)) {
            return errorJson("You must pass confirmation='CONFIRM' to delete all memories. This action is irreversible.");
        }
        try {
            long count = userMemoryStore.countEntries(userId);
            userMemoryStore.deleteAllForUser(userId);
            return jsonSerialization.serialize(Map.of("userId", userId, "entriesDeleted", count, "status", "deleted"));
        } catch (Exception e) {
            LOGGER.error("MCP delete_all_user_memories failed", e);
            return errorJson("Failed to delete memories: " + e.getMessage());
        }
    }

    @Tool(name = "count_user_memories", description = "Count the number of memory entries for a user.")
    public String countUserMemories(@ToolArg(description = "User ID (required)") String userId) {
        requireRole(identity, authEnabled, "eddi-viewer");
        if (userId == null || userId.isBlank())
            return errorJson("userId is required");
        try {
            long count = userMemoryStore.countEntries(userId);
            return jsonSerialization.serialize(Map.of("userId", userId, "count", count));
        } catch (Exception e) {
            LOGGER.error("MCP count_user_memories failed", e);
            return errorJson("Failed to count memories: " + e.getMessage());
        }
    }
}
