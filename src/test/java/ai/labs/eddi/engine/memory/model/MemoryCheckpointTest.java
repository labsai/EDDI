/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory.model;

import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.configs.properties.model.Property.Scope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("MemoryCheckpoint Tests")
class MemoryCheckpointTest {

    @Nested
    @DisplayName("Create Factory Method")
    class CreateTests {

        @Test
        @DisplayName("Should create checkpoint with all fields populated")
        void testCreatePopulatesAllFields() {
            Map<String, Property> properties = Map.of(
                    "key1", new Property("key1", "value1", Scope.conversation),
                    "key2", new Property("key2", 42, Scope.longTerm));

            MemoryCheckpoint checkpoint = MemoryCheckpoint.create(
                    "conv-123", 5, properties, "before_tool", "WebSearchTool");

            assertNotNull(checkpoint.checkpointId());
            assertFalse(checkpoint.checkpointId().isEmpty());
            assertEquals("conv-123", checkpoint.conversationId());
            assertNull(checkpoint.parentConversationId());
            assertEquals(5, checkpoint.stepIndex());
            assertNotNull(checkpoint.propertiesCopy());
            assertEquals(2, checkpoint.propertiesCopy().size());
            assertNotNull(checkpoint.createdAt());
            assertTrue(checkpoint.createdAt().isBefore(Instant.now().plusSeconds(1)));
            assertEquals("before_tool", checkpoint.triggeredBy());
            assertEquals("WebSearchTool", checkpoint.triggeredByClass());
        }

        @Test
        @DisplayName("Should create checkpoint with null properties")
        void testCreateWithNullProperties() {
            MemoryCheckpoint checkpoint = MemoryCheckpoint.create(
                    "conv-123", 0, null, "test", "TestClass");

            assertNotNull(checkpoint.propertiesCopy());
            assertTrue(checkpoint.propertiesCopy().isEmpty());
        }

        @Test
        @DisplayName("Should create immutable properties copy")
        void testCreateImmutableProperties() {
            Map<String, Property> properties = Map.of(
                    "key", new Property("key", "value", Scope.conversation));
            MemoryCheckpoint checkpoint = MemoryCheckpoint.create(
                    "conv-123", 0, properties, "test", "TestClass");

            assertThrows(UnsupportedOperationException.class,
                    () -> checkpoint.propertiesCopy().put("new",
                            new Property("new", "v", Scope.conversation)));
        }

        @Test
        @DisplayName("Should generate unique checkpoint IDs")
        void testCreateUniqueIds() {
            MemoryCheckpoint c1 = MemoryCheckpoint.create("conv-1", 0, Map.of(), "t", "C");
            MemoryCheckpoint c2 = MemoryCheckpoint.create("conv-1", 0, Map.of(), "t", "C");

            assertNotEquals(c1.checkpointId(), c2.checkpointId());
        }

        @Test
        @DisplayName("Should preserve Property scope through deep copy")
        void testCreatePreservesScope() {
            Map<String, Property> properties = Map.of(
                    "longTermProp", new Property("longTermProp", "persistent", Scope.longTerm),
                    "stepProp", new Property("stepProp", "ephemeral", Scope.step),
                    "secretProp", new Property("secretProp", "vault-value", Scope.secret));

            MemoryCheckpoint checkpoint = MemoryCheckpoint.create(
                    "conv-1", 0, properties, "test", "TestClass");

            assertEquals(Scope.longTerm, checkpoint.propertiesCopy().get("longTermProp").getScope());
            assertEquals(Scope.step, checkpoint.propertiesCopy().get("stepProp").getScope());
            assertEquals(Scope.secret, checkpoint.propertiesCopy().get("secretProp").getScope());
        }

        @Test
        @DisplayName("Should preserve Property visibility through deep copy")
        void testCreatePreservesVisibility() {
            Property globalProp = new Property("shared", "value", null, null, null, null, null,
                    Scope.longTerm, Property.Visibility.global);
            Map<String, Property> properties = Map.of("shared", globalProp);

            MemoryCheckpoint checkpoint = MemoryCheckpoint.create(
                    "conv-1", 0, properties, "test", "TestClass");

            assertEquals(Property.Visibility.global,
                    checkpoint.propertiesCopy().get("shared").getVisibility());
        }

        @Test
        @DisplayName("Should deep-copy properties (original mutation doesn't affect checkpoint)")
        void testCreateDeepCopiesProperties() {
            Property mutableProp = new Property("name", "original", Scope.conversation);
            Map<String, Property> properties = new java.util.LinkedHashMap<>();
            properties.put("name", mutableProp);

            MemoryCheckpoint checkpoint = MemoryCheckpoint.create(
                    "conv-1", 0, properties, "test", "TestClass");

            // Mutate original
            mutableProp.setValueString("mutated");

            // Checkpoint should still have original value
            assertEquals("original", checkpoint.propertiesCopy().get("name").getValueString());
        }
    }

    @Nested
    @DisplayName("WithParent")
    class WithParentTests {

        @Test
        @DisplayName("Should set parent conversation ID")
        void testWithParent() {
            Map<String, Property> props = Map.of(
                    "k", new Property("k", "v", Scope.conversation));
            MemoryCheckpoint original = MemoryCheckpoint.create(
                    "conv-child", 3, props, "fork", "ForkService");

            MemoryCheckpoint withParent = original.withParent("conv-parent");

            assertEquals("conv-parent", withParent.parentConversationId());
            assertEquals(original.checkpointId(), withParent.checkpointId());
            assertEquals(original.conversationId(), withParent.conversationId());
            assertEquals(original.stepIndex(), withParent.stepIndex());
            assertEquals(original.propertiesCopy(), withParent.propertiesCopy());
            assertEquals(original.triggeredBy(), withParent.triggeredBy());
        }
    }

    @Nested
    @DisplayName("Record Immutability")
    class ImmutabilityTests {

        @Test
        @DisplayName("Should be a proper record with equals/hashCode")
        void testRecordEquality() {
            Instant now = Instant.now();
            MemoryCheckpoint c1 = new MemoryCheckpoint(
                    "id-1", "conv-1", null, 0, Map.of(), now, "test", "TestClass");
            MemoryCheckpoint c2 = new MemoryCheckpoint(
                    "id-1", "conv-1", null, 0, Map.of(), now, "test", "TestClass");

            assertEquals(c1, c2);
            assertEquals(c1.hashCode(), c2.hashCode());
        }

        @Test
        @DisplayName("Should not be equal with different checkpoint IDs")
        void testRecordInequality() {
            Instant now = Instant.now();
            MemoryCheckpoint c1 = new MemoryCheckpoint(
                    "id-1", "conv-1", null, 0, Map.of(), now, "test", "TestClass");
            MemoryCheckpoint c2 = new MemoryCheckpoint(
                    "id-2", "conv-1", null, 0, Map.of(), now, "test", "TestClass");

            assertNotEquals(c1, c2);
        }
    }
}
