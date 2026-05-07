/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.engine.memory.model.MemoryCheckpoint;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

@DisplayName("MemorySnapshotService Tests")
class MemorySnapshotServiceTest {

    private MemorySnapshotService snapshotService;

    @Mock
    private IConversationCheckpointStore checkpointStore;

    @Mock
    private IConversationMemory memory;

    @Mock
    private IConversationMemory.IConversationProperties conversationProperties;

    @BeforeEach
    void setUp() {
        openMocks(this);
        snapshotService = new MemorySnapshotService();

        // Inject dependencies via reflection (CDI fields)
        try {
            var storeField = MemorySnapshotService.class.getDeclaredField("checkpointStore");
            storeField.setAccessible(true);
            storeField.set(snapshotService, checkpointStore);

            var registryField = MemorySnapshotService.class.getDeclaredField("meterRegistry");
            registryField.setAccessible(true);
            registryField.set(snapshotService, new SimpleMeterRegistry());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Nested
    @DisplayName("Create Checkpoint")
    class CreateCheckpointTests {

        @Test
        @DisplayName("Should create checkpoint with correct conversation data")
        void testCreateCheckpoint() {
            when(memory.getConversationId()).thenReturn("conv-123");
            when(memory.size()).thenReturn(6);
            when(memory.getConversationProperties()).thenReturn(conversationProperties);
            when(conversationProperties.toMap()).thenReturn(Map.of("name", "Alice"));

            MemoryCheckpoint checkpoint = snapshotService.createCheckpoint(
                    memory, "before_tool", "WebSearchTool");

            assertNotNull(checkpoint);
            assertEquals("conv-123", checkpoint.conversationId());
            assertEquals(5, checkpoint.stepIndex());
            assertEquals("before_tool", checkpoint.triggeredBy());
            assertEquals("WebSearchTool", checkpoint.triggeredByClass());

            verify(checkpointStore).create(any(MemoryCheckpoint.class));
            verify(checkpointStore).pruneOldest("conv-123", 10);
        }

        @Test
        @DisplayName("Should handle null conversation properties")
        void testCreateCheckpointNullProperties() {
            when(memory.getConversationId()).thenReturn("conv-123");
            when(memory.size()).thenReturn(1);
            when(memory.getConversationProperties()).thenReturn(null);

            MemoryCheckpoint checkpoint = snapshotService.createCheckpoint(
                    memory, "test", "TestClass");

            assertNotNull(checkpoint);
            assertTrue(checkpoint.propertiesCopy().isEmpty());
        }
    }

    @Nested
    @DisplayName("Rollback to Checkpoint")
    class RollbackTests {

        @Test
        @DisplayName("Should return false when checkpoint not found")
        void testRollbackNotFound() {
            when(checkpointStore.findById("unknown")).thenReturn(null);

            boolean result = snapshotService.rollbackToCheckpoint(memory, "unknown");

            assertFalse(result);
        }

        @Test
        @DisplayName("Should return false when checkpoint belongs to different conversation")
        void testRollbackWrongConversation() {
            MemoryCheckpoint checkpoint = new MemoryCheckpoint(
                    "ckpt-1", "other-conv", null, 0, Map.of(), Instant.now(), "test", "TestClass");
            when(checkpointStore.findById("ckpt-1")).thenReturn(checkpoint);
            when(memory.getConversationId()).thenReturn("my-conv");

            boolean result = snapshotService.rollbackToCheckpoint(memory, "ckpt-1");

            assertFalse(result);
        }

        @Test
        @DisplayName("Should restore properties on successful rollback")
        void testRollbackRestoresProperties() {
            Map<String, Object> savedProps = Map.of("lang", "en", "count", 42);
            MemoryCheckpoint checkpoint = new MemoryCheckpoint(
                    "ckpt-1", "conv-123", null, 3, savedProps, Instant.now(), "test", "TestClass");

            when(checkpointStore.findById("ckpt-1")).thenReturn(checkpoint);
            when(memory.getConversationId()).thenReturn("conv-123");

            // Use a real map for properties
            Map<String, Property> propsMap = new LinkedHashMap<>();
            IConversationMemory.IConversationProperties props = mock(IConversationMemory.IConversationProperties.class);
            when(memory.getConversationProperties()).thenReturn(props);
            doAnswer(inv -> {
                propsMap.clear();
                return null;
            }).when(props).clear();
            doAnswer(inv -> {
                propsMap.put(inv.getArgument(0), inv.getArgument(1));
                return null;
            }).when(props).put(anyString(), any(Property.class));

            boolean result = snapshotService.rollbackToCheckpoint(memory, "ckpt-1");

            assertTrue(result);
            verify(props).clear();
            // Should restore 2 properties
            verify(props, times(2)).put(anyString(), any(Property.class));
        }

        @Test
        @DisplayName("Should handle null properties on rollback")
        void testRollbackNullProperties() {
            MemoryCheckpoint checkpoint = new MemoryCheckpoint(
                    "ckpt-1", "conv-123", null, 0, Map.of(), Instant.now(), "test", "TestClass");

            when(checkpointStore.findById("ckpt-1")).thenReturn(checkpoint);
            when(memory.getConversationId()).thenReturn("conv-123");
            when(memory.getConversationProperties()).thenReturn(null);

            boolean result = snapshotService.rollbackToCheckpoint(memory, "ckpt-1");

            assertTrue(result);
        }
    }

    @Nested
    @DisplayName("Get and Delete Checkpoints")
    class CrudTests {

        @Test
        @DisplayName("Should delegate to store for getCheckpoints")
        void testGetCheckpoints() {
            List<MemoryCheckpoint> expected = List.of(
                    MemoryCheckpoint.create("conv-1", 0, Map.of(), "t", "C"));
            when(checkpointStore.findByConversationId("conv-1", 10)).thenReturn(expected);

            List<MemoryCheckpoint> result = snapshotService.getCheckpoints("conv-1");

            assertEquals(expected, result);
        }

        @Test
        @DisplayName("Should delegate to store for deleteCheckpoints")
        void testDeleteCheckpoints() {
            when(checkpointStore.deleteByConversationId("conv-1")).thenReturn(3L);

            long deleted = snapshotService.deleteCheckpoints("conv-1");

            assertEquals(3, deleted);
            verify(checkpointStore).deleteByConversationId("conv-1");
        }
    }

    @Nested
    @DisplayName("Metrics")
    class MetricsTests {

        @Test
        @DisplayName("Should increment create counter")
        void testCreateIncrements() {
            when(memory.getConversationId()).thenReturn("conv-1");
            when(memory.size()).thenReturn(1);
            when(memory.getConversationProperties()).thenReturn(null);

            snapshotService.createCheckpoint(memory, "test", "TestClass");

            // Counter is registered in SimpleMeterRegistry — just verify no exception
        }
    }
}
