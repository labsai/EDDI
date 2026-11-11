/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import java.util.Set;
import java.util.Map;
import java.time.LocalDate;
import ai.labs.eddi.engine.health.HealthDirtyDays;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.health.HealthStoreException;
import ai.labs.eddi.engine.health.IHealthTurnStore;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.model.ConversationState;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ConversationPurgeService — a deleted conversation takes its Agent Health rows")
class ConversationPurgeServiceHealthTest {

    @SuppressWarnings("unchecked")
    private static ConversationPurgeService service(IConversationMemoryStore memoryStore, IHealthTurnStore healthStore) {
        var service = new ConversationPurgeService(memoryStore, mock(IConversationDescriptorStore.class), mock(IConversationService.class),
                mock(Instance.class), mock(Instance.class));
        Instance<IHealthTurnStore> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(healthStore);
        service.healthTurnStore = instance;
        return service;
    }

    @Test
    @DisplayName("purge deletes the conversation's health rows")
    void purgeDeletesRows() throws Exception {
        IConversationMemoryStore memoryStore = mock(IConversationMemoryStore.class);
        when(memoryStore.getConversationState("conv")).thenReturn(ConversationState.READY);
        IHealthTurnStore healthStore = mock(IHealthTurnStore.class);

        service(memoryStore, healthStore).purge("conv");

        verify(healthStore).deleteByConversationId("conv");
        verify(memoryStore).deleteConversationMemorySnapshot("conv");
    }

    @Test
    @DisplayName("a failing day lookup does not skip the delete; the days found are marked dirty at once")
    @SuppressWarnings("unchecked")
    void dayLookupIsBestEffort() {
        IHealthTurnStore healthStore = mock(IHealthTurnStore.class);
        when(healthStore.agentDaysOfConversation("conv")).thenThrow(new HealthStoreException("down", null));
        ConversationPurgeService service = service(mock(IConversationMemoryStore.class), healthStore);
        HealthDirtyDays dirty = mock(HealthDirtyDays.class);
        Instance<HealthDirtyDays> dirtyInstance = mock(Instance.class);
        when(dirtyInstance.isResolvable()).thenReturn(true);
        when(dirtyInstance.get()).thenReturn(dirty);
        service.healthDirtyDays = dirtyInstance;

        service.deleteHealthTurns("conv");
        verify(healthStore).deleteByConversationId("conv");
        verify(dirty).markDeleted(Map.of());

        Map<String, Set<LocalDate>> days = Map.of("agent", Set.of(LocalDate.of(2026, 11, 10)));
        IHealthTurnStore working = mock(IHealthTurnStore.class);
        when(working.agentDaysOfConversation("conv2")).thenReturn(days);
        ConversationPurgeService second = service(mock(IConversationMemoryStore.class), working);
        second.healthDirtyDays = dirtyInstance;
        second.deleteHealthTurns("conv2");
        verify(working).deleteByConversationId("conv2");
        verify(dirty).markDeleted(days);
    }

    @Test
    @DisplayName("a failing health store does not fail the delete; an absent one is skipped")
    void bestEffort() {
        IHealthTurnStore healthStore = mock(IHealthTurnStore.class);
        when(healthStore.deleteByConversationId("conv")).thenThrow(new HealthStoreException("down", null));
        ConversationPurgeService service = service(mock(IConversationMemoryStore.class), healthStore);
        assertDoesNotThrow(() -> service.deleteHealthTurns("conv"));

        service.healthTurnStore = null;
        assertDoesNotThrow(() -> service.deleteHealthTurns("conv"));
    }
}
