/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.properties.model;

import ai.labs.eddi.configs.properties.model.Property.Scope;
import ai.labs.eddi.configs.properties.model.Property.Visibility;
import ai.labs.eddi.configs.properties.model.UserMemoryEntry.Assessment;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link UserMemoryEntry} factory methods and
 * {@link Property#effectiveVisibility()}.
 */
class UserMemoryEntryTest {

    // === normalizeCategory ===

    @Test
    void normalizeCategory_shouldReturnFact() {
        assertEquals("fact", UserMemoryEntry.normalizeCategory("fact"));
    }

    @Test
    void normalizeCategory_shouldReturnPreference() {
        assertEquals("preference", UserMemoryEntry.normalizeCategory("preference"));
    }

    @Test
    void normalizeCategory_shouldReturnContext() {
        assertEquals("context", UserMemoryEntry.normalizeCategory("context"));
    }

    @Test
    void normalizeCategory_shouldDefaultNullToFact() {
        assertEquals("fact", UserMemoryEntry.normalizeCategory(null));
    }

    @Test
    void normalizeCategory_shouldDefaultBlankToFact() {
        assertEquals("fact", UserMemoryEntry.normalizeCategory("   "));
    }

    @Test
    void normalizeCategory_shouldDefaultUnknownToFact() {
        assertEquals("fact", UserMemoryEntry.normalizeCategory("banana"));
    }

    @Test
    void normalizeCategory_shouldBeCaseInsensitive() {
        assertEquals("preference", UserMemoryEntry.normalizeCategory("PREFERENCE"));
        assertEquals("context", UserMemoryEntry.normalizeCategory("Context"));
    }

    @Test
    void normalizeCategory_shouldTrimWhitespace() {
        assertEquals("fact", UserMemoryEntry.normalizeCategory("  fact  "));
    }

    // === fromProperty ===

    @Test
    void fromProperty_shouldConvertStringValue() {
        Property prop = new Property("name", "Alice", Scope.longTerm);
        prop.setVisibility(Visibility.self);

        UserMemoryEntry entry = UserMemoryEntry.fromProperty(prop, "user-1", "agent-1", "conv-1", null);

        assertEquals("user-1", entry.userId());
        assertEquals("name", entry.key());
        assertEquals("Alice", entry.value());
        assertEquals("fact", entry.category());
        assertEquals(Visibility.self, entry.visibility());
        assertEquals("agent-1", entry.sourceAgentId());
        assertEquals("conv-1", entry.sourceConversationId());
        assertFalse(entry.conflicted());
        assertEquals(0, entry.accessCount());
        assertNotNull(entry.createdAt());
        assertNotNull(entry.updatedAt());
    }

    @Test
    void fromProperty_shouldConvertMapValue() {
        Map<String, Object> map = Map.of("key", "value");
        Property prop = new Property("prefs", map, Scope.longTerm);

        UserMemoryEntry entry = UserMemoryEntry.fromProperty(prop, "user-1", "agent-1", "conv-1", null);

        assertEquals(map, entry.value());
    }

    @Test
    void fromProperty_shouldConvertListValue() {
        List<Object> list = List.of("a", "b", "c");
        Property prop = new Property("items", list, Scope.longTerm);

        UserMemoryEntry entry = UserMemoryEntry.fromProperty(prop, "user-1", "agent-1", "conv-1", null);

        assertEquals(list, entry.value());
    }

    @Test
    void fromProperty_shouldConvertIntValue() {
        Property prop = new Property("age", 30, Scope.longTerm);

        UserMemoryEntry entry = UserMemoryEntry.fromProperty(prop, "user-1", "agent-1", "conv-1", null);

        assertEquals(30, entry.value());
    }

    @Test
    void fromProperty_shouldConvertFloatValue() {
        Property prop = new Property("score", 4.5f, Scope.longTerm);

        UserMemoryEntry entry = UserMemoryEntry.fromProperty(prop, "user-1", "agent-1", "conv-1", null);

        assertEquals(4.5f, entry.value());
    }

    @Test
    void fromProperty_shouldConvertBooleanValue() {
        Property prop = new Property("active", true, Scope.longTerm);

        UserMemoryEntry entry = UserMemoryEntry.fromProperty(prop, "user-1", "agent-1", "conv-1", null);

        assertEquals(true, entry.value());
    }

    @Test
    void fromProperty_shouldHandleNullValue() {
        Property prop = new Property();
        prop.setName("empty");
        prop.setScope(Scope.longTerm);

        UserMemoryEntry entry = UserMemoryEntry.fromProperty(prop, "user-1", "agent-1", "conv-1", null);

        assertNull(entry.value());
    }

    @Test
    void fromProperty_shouldUsePropertyVisibility() {
        Property prop = new Property("key", "val", Scope.longTerm);
        prop.setVisibility(Visibility.global);

        UserMemoryEntry entry = UserMemoryEntry.fromProperty(prop, "user-1", "agent-1", "conv-1", null);

        assertEquals(Visibility.global, entry.visibility());
    }

    @Test
    void fromProperty_shouldFallBackToDefaultVisibility() {
        Property prop = new Property("key", "val", Scope.longTerm);
        // No visibility set on property

        UserMemoryEntry entry = UserMemoryEntry.fromProperty(prop, "user-1", "agent-1", "conv-1", Visibility.group);

        assertEquals(Visibility.group, entry.visibility());
    }

    @Test
    void fromProperty_shouldFallBackToSelfWhenBothNull() {
        Property prop = new Property("key", "val", Scope.longTerm);

        UserMemoryEntry entry = UserMemoryEntry.fromProperty(prop, "user-1", "agent-1", "conv-1", null);

        assertEquals(Visibility.self, entry.visibility());
    }

    // === fromToolCall ===

    @Test
    void fromToolCall_shouldCreateEntryWithDefaults() {
        UserMemoryEntry entry = UserMemoryEntry.fromToolCall("user-1", "agent-1", "conv-1", List.of("group-1"), "color", "blue", "preference",
                Visibility.self);

        assertEquals("user-1", entry.userId());
        assertEquals("color", entry.key());
        assertEquals("blue", entry.value());
        assertEquals("preference", entry.category());
        assertEquals(Visibility.self, entry.visibility());
        assertEquals("agent-1", entry.sourceAgentId());
        assertEquals(List.of("group-1"), entry.groupIds());
        assertEquals("conv-1", entry.sourceConversationId());
        assertFalse(entry.conflicted());
        assertEquals(0, entry.accessCount());
        assertNull(entry.id(), "ID should be null for new entries");
    }

    @Test
    void fromToolCall_shouldNormalizeBadCategory() {
        UserMemoryEntry entry = UserMemoryEntry.fromToolCall("user-1", "agent-1", "conv-1", null, "key", "val", "unknown", null);

        assertEquals("fact", entry.category());
    }

    @Test
    void fromToolCall_shouldDefaultNullVisibilityToSelf() {
        UserMemoryEntry entry = UserMemoryEntry.fromToolCall("user-1", "agent-1", "conv-1", null, "key", "val", "fact", null);

        assertEquals(Visibility.self, entry.visibility());
    }

    @Test
    void fromToolCall_shouldDefaultNullGroupIdsToEmptyList() {
        UserMemoryEntry entry = UserMemoryEntry.fromToolCall("user-1", "agent-1", "conv-1", null, "key", "val", "fact", Visibility.self);

        assertEquals(List.of(), entry.groupIds());
    }

    // === Property.effectiveVisibility ===

    @Test
    void effectiveVisibility_shouldReturnSelfWhenNull() {
        Property prop = new Property("key", "val", Scope.longTerm);
        assertEquals(Visibility.self, prop.effectiveVisibility());
    }

    @Test
    void effectiveVisibility_shouldReturnSetVisibility() {
        Property prop = new Property("key", "val", Scope.longTerm);
        prop.setVisibility(Visibility.global);
        assertEquals(Visibility.global, prop.effectiveVisibility());
    }

    // === DEFAULT_CATEGORIES ===

    @Test
    void defaultCategories_shouldContainExpectedValues() {
        assertTrue(UserMemoryEntry.DEFAULT_CATEGORIES.contains("preference"));
        assertTrue(UserMemoryEntry.DEFAULT_CATEGORIES.contains("fact"));
        assertTrue(UserMemoryEntry.DEFAULT_CATEGORIES.contains("context"));
        assertEquals(3, UserMemoryEntry.DEFAULT_CATEGORIES.size());
    }

    // === assessment ===

    @Test
    void legacyConstructor_isAccepted() {
        var entry = new UserMemoryEntry("1", "u", "k", "v", "fact", Visibility.self, "a", List.of(), null, false, 0, null, null);
        assertEquals(Assessment.ACCEPTED, entry.assessment());
        assertNull(entry.supersededBy());
    }

    @Test
    void nullAssessment_isAccepted() {
        var entry = new UserMemoryEntry("1", "u", "k", "v", "fact", Visibility.self, "a", List.of(), null, false, 0, null, null, null, "x");
        assertEquals(Assessment.ACCEPTED, entry.assessment());
        assertNull(entry.supersededBy(), "a pointer is only kept on a SUPERSEDED entry");
    }

    @Test
    void supersededBy_keptOnlyWhenSuperseded() {
        var superseded = new UserMemoryEntry("1", "u", "k", "v", "fact", Visibility.self, "a", List.of(), null, false, 0, null, null,
                Assessment.SUPERSEDED, "2");
        assertEquals("2", superseded.supersededBy());
        assertNull(superseded.withAssessment(Assessment.CONTESTED, "2").supersededBy());
    }

    @Test
    void withAssessment_keepsEverythingElse_andMirrorsConflicted() {
        Instant at = Instant.parse("2026-10-01T00:00:00Z");
        var entry = new UserMemoryEntry("1", "u", "k", "v", "fact", Visibility.group, "a", List.of("g"), "c", false, 3, at, at);

        var contested = entry.withAssessment(Assessment.CONTESTED, null);
        assertEquals(Assessment.CONTESTED, contested.assessment());
        assertTrue(contested.conflicted());
        assertEquals(at, contested.updatedAt());
        assertEquals(3, contested.accessCount());
        assertEquals(List.of("g"), contested.groupIds());

        assertFalse(contested.withAssessment(Assessment.ACCEPTED, null).conflicted());
    }

    @Test
    void parse_isLenient() {
        assertEquals(Assessment.ACCEPTED, Assessment.parse(null));
        assertEquals(Assessment.ACCEPTED, Assessment.parse(""));
        assertEquals(Assessment.ACCEPTED, Assessment.parse("DISPUTED"));
        assertEquals(Assessment.CONTESTED, Assessment.parse("contested"));
        assertEquals(Assessment.SUPERSEDED, Assessment.parse(" SUPERSEDED "));
    }

    @Test
    void json_withoutAssessment_readsAsAccepted_andNewFieldsRoundTrip() throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var legacy = mapper.readValue("""
                {"id":"1","userId":"u","key":"k","value":"v","category":"fact","visibility":"self","sourceAgentId":"a",
                 "groupIds":[],"conflicted":false,"accessCount":0}
                """, UserMemoryEntry.class);
        assertEquals(Assessment.ACCEPTED, legacy.assessment());

        var superseded = legacy.withAssessment(Assessment.SUPERSEDED, "2");
        String json = mapper.writeValueAsString(superseded);
        assertTrue(json.contains("\"assessment\":\"SUPERSEDED\""), json);
        assertTrue(json.contains("\"supersededBy\":\"2\""), json);
        assertEquals(superseded, mapper.readValue(json, UserMemoryEntry.class));
    }
}
