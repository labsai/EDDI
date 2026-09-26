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
     * collection. Equivalent to
     * {@link #fromProperty(Property, String, String, String, Visibility, List)}
     * with no fallback groups.
     */
    public static UserMemoryEntry fromProperty(Property property, String userId, String agentId, String conversationId,
                                               Visibility defaultVisibility) {
        return fromProperty(property, userId, agentId, conversationId, defaultVisibility, List.of());
    }

    /**
     * Creates an entry from a {@link Property}, carrying the groups a
     * {@code group}-visibility entry is shared with.
     * <p>
     * The groups used to be hard-coded to {@code []}, which broke group visibility
     * both ways (M-E2): a property written with {@code visibility: group} could
     * never be recalled, because recall matches on {@code groupIds}, and a
     * group-visible memory recalled into a conversation lost its groups the moment
     * the property was written back. Now: the property's own groups (set when it
     * was recalled) win; otherwise {@code fallbackGroupIds} — the groups the turn
     * runs in. Entries of any other visibility carry no groups, as before.
     *
     * @param fallbackGroupIds
     *            groups to use when the property carries none; may be {@code null}
     */
    public static UserMemoryEntry fromProperty(Property property, String userId, String agentId, String conversationId,
                                               Visibility defaultVisibility, List<String> fallbackGroupIds) {
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

        Visibility vis = property.getVisibility() != null ? property.getVisibility() : defaultVisibility;
        if (vis == null) {
            vis = Visibility.self;
        }

        List<String> groupIds = List.of();
        if (vis == Visibility.group) {
            if (property.getGroupIds() != null && !property.getGroupIds().isEmpty()) {
                groupIds = List.copyOf(property.getGroupIds());
            } else if (fallbackGroupIds != null) {
                groupIds = List.copyOf(fallbackGroupIds);
            }
        }

        return new UserMemoryEntry(null, userId, property.getName(), value, "fact", vis, agentId, groupIds, conversationId, false, 0, Instant.now(),
                Instant.now());
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
