/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory.model;

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
            Map<String, Object> properties = Map.of("key1", "value1", "key2", 42);

            MemoryCheckpoint checkpoint = MemoryCheckpoint.create(
                    "conv-123", 5, properties, "before_tool", "WebSearchTool");

            assertNotNull(checkpoint.checkpointId());
            assertFalse(checkpoint.checkpointId().isEmpty());
            assertEquals("conv-123", checkpoint.conversationId());
            assertNull(checkpoint.parentConversationId());
            assertEquals(5, checkpoint.stepIndex());
            assertEquals(properties, checkpoint.propertiesCopy());
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
            Map<String, Object> properties = Map.of("key", "value");
            MemoryCheckpoint checkpoint = MemoryCheckpoint.create(
                    "conv-123", 0, properties, "test", "TestClass");

            assertThrows(UnsupportedOperationException.class,
                    () -> checkpoint.propertiesCopy().put("new", "value"));
        }

        @Test
        @DisplayName("Should generate unique checkpoint IDs")
        void testCreateUniqueIds() {
            MemoryCheckpoint c1 = MemoryCheckpoint.create("conv-1", 0, Map.of(), "t", "C");
            MemoryCheckpoint c2 = MemoryCheckpoint.create("conv-1", 0, Map.of(), "t", "C");

            assertNotEquals(c1.checkpointId(), c2.checkpointId());
        }
    }

    @Nested
    @DisplayName("WithParent")
    class WithParentTests {

        @Test
        @DisplayName("Should set parent conversation ID")
        void testWithParent() {
            MemoryCheckpoint original = MemoryCheckpoint.create(
                    "conv-child", 3, Map.of("k", "v"), "fork", "ForkService");

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
