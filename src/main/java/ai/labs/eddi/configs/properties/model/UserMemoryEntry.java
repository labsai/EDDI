/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.properties.model;

import ai.labs.eddi.configs.properties.model.Property.Visibility;

import java.time.Instant;
import java.util.List;

/**
 * A structured memory entry stored in the {@code usermemories} collection.
 * Represents a single fact, preference, or context item that an agent has
 * remembered about a user.
 *
 * <p>
 * Upsert key semantics vary by visibility:
 * <ul>
 * <li>{@code self/group}: {@code (userId, key, sourceAgentId)}</li>
 * <li>{@code global}: {@code (userId, key)}</li>
 * </ul>
 *
 * @author ginccc
 * @since 6.0.0
 */
public record UserMemoryEntry(String id, String userId, String key, Object value, String category, Visibility visibility, String sourceAgentId,
        List<String> groupIds, String sourceConversationId, boolean conflicted, int accessCount, Instant createdAt, Instant updatedAt) {

    /** Accepted memory categories. Unknown values default to {@code "fact"}. */
    public static final List<String> DEFAULT_CATEGORIES = List.of("preference", "fact", "context");

    /**
     * Creates an entry from a {@link Property} with user memory metadata. Used when
     * flushing longTerm properties with non-null visibility to the usermemories
     * collection.
     */
    public static UserMemoryEntry fromProperty(Property property, String userId, String agentId, String conversationId,
                                               Visibility defaultVisibility) {
        Visibility visibility = property.getVisibility() != null ? property.getVisibility() : defaultVisibility;
        return fromProperty(property, userId, agentId, conversationId, visibility, List.of());
    }

    /**
     * Creates an entry with an already RESOLVED visibility, for a conversation that
     * belongs to {@code groupIds}. Unlike the overload above, the property's own
     * visibility is not consulted again — the caller decided, possibly narrowing a
     * {@code group} property to {@code self} because the conversation has no group.
     * <p>
     * A {@code group} entry is only reachable through an overlap with the reader's
     * group ids, so one stored with none — which is what every {@code longTerm}
     * property got — could be read by nobody, the writing agent included.
     */
    public static UserMemoryEntry fromProperty(Property property, String userId, String agentId, String conversationId, Visibility visibility,
                                               List<String> groupIds) {
        Object value;
        if (property.getValueString() != null) {
            value = property.getValueString();
        } else if (property.getValueObject() != null) {
            value = property.getValueObject();
        } else if (property.getValueList() != null) {
            value = property.getValueList();
        } else if (property.getValueInt() != null) {
            value = property.getValueInt();
        } else if (property.getValueFloat() != null) {
            value = property.getValueFloat();
        } else if (property.getValueBoolean() != null) {
            value = property.getValueBoolean();
        } else {
            value = null;
        }

        Visibility vis = visibility != null ? visibility : Visibility.self;

        List<String> entryGroupIds = vis == Visibility.group && groupIds != null ? List.copyOf(groupIds) : List.of();
        return new UserMemoryEntry(null, userId, property.getName(), value, "fact", vis, agentId, entryGroupIds, conversationId, false, 0,
                Instant.now(), Instant.now());
    }

    /**
     * Creates an entry from an LLM tool call (rememberFact).
     */
    public static UserMemoryEntry fromToolCall(String userId, String agentId, String conversationId, List<String> groupIds, String key, Object value,
                                               String category, Visibility visibility) {
        return new UserMemoryEntry(null, userId, key, value, normalizeCategory(category), visibility != null ? visibility : Visibility.self, agentId,
                groupIds != null ? groupIds : List.of(), conversationId, false, 0, Instant.now(), Instant.now());
    }

    /**
     * Returns the category, defaulting unknown values to "fact".
     */
    public static String normalizeCategory(String category) {
        if (category == null || category.isBlank()) {
            return "fact";
        }
        String normalized = category.trim().toLowerCase();
        return DEFAULT_CATEGORIES.contains(normalized) ? normalized : "fact";
    }
}
