/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

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
