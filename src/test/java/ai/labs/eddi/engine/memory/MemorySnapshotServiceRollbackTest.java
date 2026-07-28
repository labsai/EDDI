/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.configs.properties.model.Property.Scope;
import ai.labs.eddi.engine.memory.model.MemoryCheckpoint;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * G9 acceptance — a rollback must remove post-checkpoint properties from the
 * template view. Uses a REAL {@code ConversationMemory} (and therefore the real
 * {@code ConversationProperties}) so the {@code clear(); forEach(put)} sequence
 * in {@code restoreProperties} is exercised end to end.
 * <p>
 * I5 — the checkpoint retention is no longer hardcoded.
 */
class MemorySnapshotServiceRollbackTest {

    private MemorySnapshotService snapshotService;
    private IConversationCheckpointStore checkpointStore;

    @BeforeEach
    void setUp() throws Exception {
        checkpointStore = mock(IConversationCheckpointStore.class);
        snapshotService = new MemorySnapshotService();

        var storeField = MemorySnapshotService.class.getDeclaredField("checkpointStore");
        storeField.setAccessible(true);
        storeField.set(snapshotService, checkpointStore);

        var registryField = MemorySnapshotService.class.getDeclaredField("meterRegistry");
        registryField.setAccessible(true);
        registryField.set(snapshotService, new SimpleMeterRegistry());
    }

    @Test
    @DisplayName("G9 — rollback removes properties added after the checkpoint from {properties.x}")
    void rollbackDropsPostCheckpointPropertiesFromTemplates() {
        var memory = new ConversationMemory("aabbccddeeff112233445566", "agent-1", 1, "user-1");
        var properties = memory.getConversationProperties();
        properties.put("name", new Property("name", "Alice", Scope.conversation));

        var checkpoint = MemoryCheckpoint.create(memory.getConversationId(), 0,
                Map.of("name", new Property("name", "Alice", Scope.conversation)), "before_tool:x", "TestClass");
        when(checkpointStore.findById("ckpt-1")).thenReturn(checkpoint);

        // ... the tool run then adds a property that the rollback must undo
        properties.put("sideEffect", new Property("sideEffect", "leaked", Scope.conversation));
        assertEquals("leaked", properties.toMap().get("sideEffect"));

        assertTrue(snapshotService.rollbackToCheckpoint(memory, "ckpt-1"));

        assertFalse(properties.containsKey("sideEffect"), "the property must be gone from the map");
        assertNull(properties.toMap().get("sideEffect"), "and from the template view {properties.sideEffect}");
        assertEquals("Alice", properties.toMap().get("name"));
    }

    @Test
    @DisplayName("I5 — an explicit retention is honoured instead of the hardcoded default")
    void explicitRetentionIsHonoured() {
        var memory = new ConversationMemory("aabbccddeeff112233445566", "agent-1", 1, "user-1");

        snapshotService.createCheckpoint(memory, "before_tool:x", "TestClass", 25);
        verify(checkpointStore).pruneOldest(memory.getConversationId(), 25);

        snapshotService.createCheckpoint(memory, "before_tool:x", "TestClass");
        verify(checkpointStore).pruneOldest(memory.getConversationId(), MemorySnapshotService.DEFAULT_MAX_CHECKPOINTS);

        // a nonsensical retention must not prune everything
        snapshotService.createCheckpoint(memory, "before_tool:x", "TestClass", 0);
        verify(checkpointStore, times(2)).pruneOldest(memory.getConversationId(), MemorySnapshotService.DEFAULT_MAX_CHECKPOINTS);
        verify(checkpointStore, never()).pruneOldest(anyString(), eq(0));
    }
}
