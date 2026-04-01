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

        return new UserMemoryEntry(null, userId, property.getName(), value, "fact", vis, agentId, List.of(), conversationId, false, 0, Instant.now(),
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
