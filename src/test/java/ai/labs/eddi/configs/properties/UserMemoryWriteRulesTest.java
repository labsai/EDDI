/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.properties;

import ai.labs.eddi.configs.properties.model.Property.Visibility;
import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The floor for user-memory writes from REST and MCP — each rule answers a
 * defect observed on a live instance.
 */
class UserMemoryWriteRulesTest {

    private static UserMemoryEntry entry(String key, Object value, String category, Visibility visibility, String agent, List<String> groups) {
        return new UserMemoryEntry(null, "user-1", key, value, category, visibility, agent, groups, null, false, 0, null, null);
    }

    @Test
    @DisplayName("a valid entry passes")
    void validEntry() {
        assertNull(UserMemoryWriteRules.validate(entry("lang", "German", "preference", Visibility.self, "agent-1", List.of())));
        assertNull(UserMemoryWriteRules.validate(entry("lang", "German", null, Visibility.global, null, List.of())));
        assertNull(UserMemoryWriteRules.validate(entry("plan", "ship", "context", Visibility.group, "agent-1", List.of("g1"))));
    }

    @Test
    @DisplayName("a missing visibility is a 400, not the NullPointerException (HTTP 500) it used to be")
    void visibilityRequired() {
        assertTrue(UserMemoryWriteRules.validate(entry("k", "v", "fact", null, "agent-1", List.of())).contains("visibility"));
    }

    @Test
    @DisplayName("self and group entries need an owning agent, group entries a group — otherwise no one can read them")
    void unreachableEntriesRejected() {
        assertTrue(UserMemoryWriteRules.validate(entry("k", "v", "fact", Visibility.self, null, List.of())).contains("sourceAgentId"));
        assertTrue(UserMemoryWriteRules.validate(entry("k", "v", "fact", Visibility.group, "agent-1", List.of())).contains("groupIds"));
    }

    @Test
    @DisplayName("unknown categories are rejected; the legacy ones stay accepted")
    void categories() {
        assertNotNull(UserMemoryWriteRules.validate(entry("k", "v", "weird", Visibility.global, null, List.of())));
        assertNull(UserMemoryWriteRules.validate(entry("k", "v", "legacy", Visibility.global, null, List.of())));
        assertNull(UserMemoryWriteRules.validate(entry("k", "v", "property", Visibility.global, null, List.of())));
    }

    @Test
    @DisplayName("key and value sizes are bounded — a 1 MB value used to be accepted and loaded into every conversation")
    void sizeLimits() {
        assertNotNull(UserMemoryWriteRules.validate(entry("k".repeat(256), "v", "fact", Visibility.global, null, List.of())));
        assertNotNull(UserMemoryWriteRules.validate(entry("k", "v".repeat(1_000_000), "fact", Visibility.global, null, List.of())));
        assertNotNull(UserMemoryWriteRules.validate(entry("k", Map.of("blob", "v".repeat(70_000)), "fact", Visibility.global, null, List.of())),
                "structured values are measured by their JSON size");
        assertNull(UserMemoryWriteRules.validate(entry("k", "v".repeat(5_000), "fact", Visibility.global, null, List.of())));
    }

    @Test
    @DisplayName("an absent category is stored as fact")
    void categoryDefault() {
        assertEquals("fact", UserMemoryWriteRules.withDefaults(entry("k", "v", null, Visibility.global, null, List.of())).category());
    }
}
