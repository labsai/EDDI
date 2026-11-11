/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.health.HealthDirtyDays;
import ai.labs.eddi.engine.health.HealthStoreException;
import ai.labs.eddi.engine.health.IHealthTurnStore;
import ai.labs.eddi.engine.health.model.HealthDeletedRows;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.model.ConversationListingSummary;
import ai.labs.eddi.engine.memory.model.ConversationState;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

    @SuppressWarnings("unchecked")
    private static HealthDirtyDays dirtyDays(ConversationPurgeService service) {
        HealthDirtyDays dirty = mock(HealthDirtyDays.class);
        Instance<HealthDirtyDays> dirtyInstance = mock(Instance.class);
        when(dirtyInstance.isResolvable()).thenReturn(true);
        when(dirtyInstance.get()).thenReturn(dirty);
        service.healthDirtyDays = dirtyInstance;
        return dirty;
    }

    @Test
    @DisplayName("purge deletes the conversation's health rows, passing the agent read before the snapshot went")
    void purgeDeletesRows() throws Exception {
        IConversationMemoryStore memoryStore = mock(IConversationMemoryStore.class);
        when(memoryStore.getConversationState("conv")).thenReturn(ConversationState.READY);
        when(memoryStore.loadListingSummaries(List.of("conv")))
                .thenReturn(Map.of("conv", new ConversationListingSummary("conv", "user", null, null, "agent", 1, 2)));
        IHealthTurnStore healthStore = mock(IHealthTurnStore.class);
        HealthDeletedRows deleted = new HealthDeletedRows(2L, null);
        when(healthStore.deleteConversationReturningDays("conv")).thenReturn(deleted);
        ConversationPurgeService service = service(memoryStore, healthStore);
        HealthDirtyDays dirty = dirtyDays(service);

        service.purge("conv");

        InOrder order = inOrder(memoryStore, healthStore);
        order.verify(memoryStore).loadListingSummaries(List.of("conv"));
        order.verify(memoryStore).deleteConversationMemorySnapshot("conv");
        order.verify(healthStore).deleteConversationReturningDays("conv");
        verify(dirty).markDeletedRows("conv", List.of("agent"), deleted);
    }

    @Test
    @DisplayName("an unreadable agent never stops the purge; the rows go only when their days are known")
    void agentLookupIsBestEffort() throws Exception {
        IConversationMemoryStore memoryStore = mock(IConversationMemoryStore.class);
        when(memoryStore.getConversationState("conv")).thenReturn(ConversationState.READY);
        when(memoryStore.loadListingSummaries(any())).thenThrow(new IllegalStateException("down"));
        IHealthTurnStore healthStore = mock(IHealthTurnStore.class);
        HealthDeletedRows deleted = new HealthDeletedRows(2L, Map.of("agent", Set.of(LocalDate.of(2026, 11, 10))));
        when(healthStore.deleteConversationIfDaysKnown("conv")).thenReturn(deleted);
        ConversationPurgeService service = service(memoryStore, healthStore);
        HealthDirtyDays dirty = dirtyDays(service);

        service.purge("conv");

        verify(memoryStore).deleteConversationMemorySnapshot("conv");
        verify(healthStore).deleteConversationIfDaysKnown("conv");
        verify(healthStore, never()).deleteConversationReturningDays(any());
        verify(dirty).markDeletedRows("conv", List.of(), deleted);
    }

    @Test
    @DisplayName("neither agent nor days known: the rows are kept for the orphan sweep and nothing is marked")
    void bothUnknownKeepsRows() throws Exception {
        IConversationMemoryStore memoryStore = mock(IConversationMemoryStore.class);
        when(memoryStore.getConversationState("conv")).thenReturn(ConversationState.READY);
        when(memoryStore.loadListingSummaries(any())).thenThrow(new IllegalStateException("down"));
        IHealthTurnStore healthStore = mock(IHealthTurnStore.class);
        when(healthStore.deleteConversationIfDaysKnown("conv")).thenReturn(null);
        ConversationPurgeService service = service(memoryStore, healthStore);
        HealthDirtyDays dirty = dirtyDays(service);

        assertDoesNotThrow(() -> service.purge("conv"));

        verify(memoryStore).deleteConversationMemorySnapshot("conv");
        verify(healthStore, never()).deleteConversationReturningDays(any());
        verify(dirty, never()).markDeletedRows(any(), any(), any());
    }

    @Test
    @DisplayName("the days the delete returned are handed on, whether known or not")
    void daysHandedOn() {
        Map<String, Set<LocalDate>> days = Map.of("agent", Set.of(LocalDate.of(2026, 11, 10)));
        IHealthTurnStore healthStore = mock(IHealthTurnStore.class);
        HealthDeletedRows known = new HealthDeletedRows(1L, days);
        when(healthStore.deleteConversationReturningDays("conv")).thenReturn(known);
        ConversationPurgeService service = service(mock(IConversationMemoryStore.class), healthStore);
        HealthDirtyDays dirty = dirtyDays(service);

        service.deleteHealthTurns("conv", "agent");
        verify(dirty).markDeletedRows("conv", List.of("agent"), known);

        when(healthStore.deleteConversationIfDaysKnown("conv")).thenReturn(known);
        service.deleteHealthTurns("conv");
        verify(dirty).markDeletedRows("conv", List.of(), known);
    }

    @Test
    @DisplayName("a failing health store does not fail the delete; an absent one is skipped")
    void bestEffort() {
        IHealthTurnStore healthStore = mock(IHealthTurnStore.class);
        when(healthStore.deleteConversationReturningDays("conv")).thenThrow(new HealthStoreException("down", null));
        ConversationPurgeService service = service(mock(IConversationMemoryStore.class), healthStore);
        assertDoesNotThrow(() -> service.deleteHealthTurns("conv", "agent"));

        service.healthTurnStore = null;
        assertDoesNotThrow(() -> service.deleteHealthTurns("conv"));
    }
}
