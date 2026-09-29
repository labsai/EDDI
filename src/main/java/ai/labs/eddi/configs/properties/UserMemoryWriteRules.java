/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.properties;

import ai.labs.eddi.configs.properties.model.Property.Visibility;
import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Set;

/**
 * Validation for user-memory writes that arrive from outside a conversation —
 * the REST API and the MCP tools. The LLM tool has its own, tighter per-agent
 * guardrails ({@code userMemoryConfig.guardrails}); these are the
 * deployment-wide floor every other writer must clear.
 * <p>
 * Each rule answers a defect observed on a live instance: a missing
 * {@code visibility} crashed the store with a NullPointerException (HTTP 500),
 * a 1 MB value was accepted and then loaded into every conversation of the
 * user, a {@code self} or {@code group} entry without an owning agent or group
 * could be written but never read by anyone, and REST and MCP disagreed on the
 * key limit.
 */
public final class UserMemoryWriteRules {

    /** Longest accepted key, in characters. */
    public static final int MAX_KEY_LENGTH = 255;
    /**
     * Largest accepted value — the length of its JSON form. Generous next to the
     * LLM tool's default of 1,000 characters, because admin writes carry structured
     * values; bounded because every recalled entry is loaded into every
     * conversation of the user.
     */
    public static final int MAX_VALUE_CHARS = 65_536;
    /**
     * Categories an external writer may use: the three the memory tools use, plus
     * {@code legacy} (migrated v5 properties) and {@code property} (the flat
     * properties API).
     */
    public static final List<String> CATEGORIES = List.of("preference", "fact", "context", "legacy", "property");

    private static final Set<String> CATEGORY_SET = Set.copyOf(CATEGORIES);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private UserMemoryWriteRules() {
    }

    /**
     * @return a message naming the first violated rule, or {@code null} when the
     *         entry may be written
     */
    public static String validate(UserMemoryEntry entry) {
        if (entry == null) {
            return "Request body is required";
        }
        if (entry.userId() == null || entry.userId().isBlank()) {
            return "userId is required";
        }
        if (entry.key() == null || entry.key().isBlank()) {
            return "key is required";
        }
        if (entry.key().length() > MAX_KEY_LENGTH) {
            return "key must not exceed " + MAX_KEY_LENGTH + " characters";
        }
        if (entry.visibility() == null) {
            return "visibility is required: self, group or global";
        }
        if (entry.visibility() != Visibility.global && (entry.sourceAgentId() == null || entry.sourceAgentId().isBlank())) {
            return "sourceAgentId is required for " + entry.visibility() + " visibility — without an owning agent no one can read the entry";
        }
        if (entry.visibility() == Visibility.group && (entry.groupIds() == null || entry.groupIds().isEmpty())) {
            return "groupIds is required for group visibility — without a group no one can read the entry";
        }
        if (entry.category() != null && !CATEGORY_SET.contains(entry.category())) {
            return "category must be one of " + CATEGORIES;
        }
        if (valueLength(entry.value()) > MAX_VALUE_CHARS) {
            return "value must not exceed " + MAX_VALUE_CHARS + " characters";
        }
        return null;
    }

    /** The entry with an absent category set to {@code fact}. */
    public static UserMemoryEntry withDefaults(UserMemoryEntry entry) {
        if (entry.category() != null) {
            return entry;
        }
        return new UserMemoryEntry(entry.id(), entry.userId(), entry.key(), entry.value(), "fact", entry.visibility(), entry.sourceAgentId(),
                entry.groupIds(), entry.sourceConversationId(), entry.conflicted(), entry.accessCount(), entry.createdAt(), entry.updatedAt());
    }

    private static int valueLength(Object value) {
        if (value == null) {
            return 0;
        }
        if (value instanceof String s) {
            return s.length();
        }
        try {
            return MAPPER.writeValueAsString(value).length();
        } catch (JsonProcessingException e) {
            return String.valueOf(value).length();
        }
    }
}
