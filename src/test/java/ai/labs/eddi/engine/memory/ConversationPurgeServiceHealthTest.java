/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.memory;

import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.gdpr.erasure.ErasureGuard;
import ai.labs.eddi.engine.gdpr.erasure.ErasureMetrics;
import ai.labs.eddi.engine.gdpr.erasure.InMemoryErasureTombstoneStore;
import ai.labs.eddi.engine.health.HealthDirtyDays;
import ai.labs.eddi.engine.health.HealthErasureWriter;
import ai.labs.eddi.engine.health.HealthStoreException;
import ai.labs.eddi.engine.health.HealthTurnRecorder;
import ai.labs.eddi.engine.health.IHealthOutcomeStore;
import ai.labs.eddi.engine.health.IHealthTurnStore;
import ai.labs.eddi.engine.health.model.HealthDeletedRows;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.model.ConversationListingSummary;
import ai.labs.eddi.engine.memory.model.ConversationState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ConversationPurgeService — a deleted conversation takes its Agent Health rows")
class ConversationPurgeServiceHealthTest {

    private final HealthDirtyDays dirty = mock(HealthDirtyDays.class);

    @SuppressWarnings("unchecked")
    private static <T> Instance<T> instance(T bean) {
        Instance<T> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(bean != null);
        when(instance.get()).thenReturn(bean);
        return instance;
    }

    @SuppressWarnings("unchecked")
    private ConversationPurgeService service(IConversationMemoryStore memoryStore, IHealthTurnStore healthStore) throws Exception {
        var service = new ConversationPurgeService(memoryStore, mock(IConversationDescriptorStore.class), mock(IConversationService.class),
                mock(Instance.class), mock(Instance.class));
        service.healthTurnStore = instance(healthStore);
        service.healthDirtyDays = instance(dirty);
        HealthTurnRecorder recorder = mock(HealthTurnRecorder.class);
        when(recorder.awaitDrained(any())).thenReturn(true);
        HealthErasureWriter writer = new HealthErasureWriter(recorder, healthStore, instance(dirty), instance(memoryStore));
        service.useErasureGuard(new ErasureGuard(new InMemoryErasureTombstoneStore(), List.of(writer),
                new ErasureMetrics(new SimpleMeterRegistry()), Duration.ofDays(400), Duration.ofSeconds(1), Duration.ofMinutes(5),
                Clock.systemUTC()));
        return service;
    }

    @Test
    @DisplayName("purge erases the conversation's health rows through the erasure guard, before the snapshot, and marks their days")
    void purgeErasesRows() throws Exception {
        IConversationMemoryStore memoryStore = mock(IConversationMemoryStore.class);
        when(memoryStore.getConversationState("conv")).thenReturn(ConversationState.READY);
        when(memoryStore.loadListingSummaries(List.of("conv")))
                .thenReturn(Map.of("conv", new ConversationListingSummary("conv", "user", null, null, "agent", 1, 2)));
        IHealthTurnStore healthStore = mock(IHealthTurnStore.class);
        HealthDeletedRows deleted = new HealthDeletedRows(2L, null);
        when(healthStore.deleteConversationReturningDays("conv")).thenReturn(deleted);

        service(memoryStore, healthStore).purge("conv");

        InOrder order = inOrder(healthStore, memoryStore);
        order.verify(healthStore).deleteConversationReturningDays("conv");
        // Unknown days: the agent is read after the delete, before the snapshot goes.
        order.verify(memoryStore).loadListingSummaries(List.of("conv"));
        order.verify(healthStore).countByConversationId("conv");
        order.verify(memoryStore).deleteConversationMemorySnapshot("conv");
        // Once, by the guard: no second, unguarded delete after the snapshot.
        verify(healthStore, times(1)).deleteConversationReturningDays("conv");
        verify(healthStore, never()).deleteConversationIfDaysKnown(any());
        // Unknown days: the agent read while the conversation still existed is
        // re-rolled.
        verify(dirty).markDeletedRows("conv", List.of("agent"), deleted);
    }

    @Test
    @DisplayName("guarded: neither agent nor days known, the rows are still deleted (the guarantee needs them gone)")
    void guardedDeletesEvenWhenNothingIsKnown() throws Exception {
        IConversationMemoryStore memoryStore = mock(IConversationMemoryStore.class);
        when(memoryStore.getConversationState("conv")).thenReturn(ConversationState.READY);
        when(memoryStore.loadListingSummaries(any())).thenThrow(new IllegalStateException("down"));
        IHealthTurnStore healthStore = mock(IHealthTurnStore.class);
        HealthDeletedRows deleted = new HealthDeletedRows(2L, null);
        when(healthStore.deleteConversationReturningDays("conv")).thenReturn(deleted);
        ConversationPurgeService service = service(memoryStore, healthStore);

        assertDoesNotThrow(() -> service.purge("conv"));

        verify(healthStore).deleteConversationReturningDays("conv");
        verify(memoryStore).deleteConversationMemorySnapshot("conv");
        // markDeletedRows warns that nothing can be re-rolled.
        verify(dirty).markDeletedRows("conv", List.of(), deleted);
    }

    @Test
    @DisplayName("health rows that remain fail the delete, and the snapshot is kept for a retry")
    void remainingRowsFailTheDelete() throws Exception {
        IConversationMemoryStore memoryStore = mock(IConversationMemoryStore.class);
        when(memoryStore.getConversationState("conv")).thenReturn(ConversationState.READY);
        IHealthTurnStore healthStore = mock(IHealthTurnStore.class);
        when(healthStore.countByConversationId("conv")).thenReturn(1L);
        ConversationPurgeService service = service(memoryStore, healthStore);

        assertThrows(ResourceStoreException.class, () -> service.purge("conv"));

        verify(memoryStore, never()).deleteConversationMemorySnapshot("conv");
    }

    @Test
    @DisplayName("unguarded (agent test): neither agent nor days known, the rows are kept for the orphan sweep and nothing is marked")
    void bothUnknownKeepsRows() throws Exception {
        IHealthTurnStore healthStore = mock(IHealthTurnStore.class);
        when(healthStore.deleteConversationIfDaysKnown("conv")).thenReturn(null);
        ConversationPurgeService service = service(mock(IConversationMemoryStore.class), healthStore);

        assertDoesNotThrow(() -> service.deleteHealthTurns("conv"));

        verify(healthStore, never()).deleteConversationReturningDays(any());
        verify(dirty, never()).markDeletedRows(any(), any(), any());
    }

    @Test
    @DisplayName("unguarded (agent test): the days the delete returned are handed on, whether known or not")
    void daysHandedOn() throws Exception {
        Map<String, Set<LocalDate>> days = Map.of("agent", Set.of(LocalDate.of(2026, 11, 10)));
        IHealthTurnStore healthStore = mock(IHealthTurnStore.class);
        HealthDeletedRows known = new HealthDeletedRows(1L, days);
        when(healthStore.deleteConversationReturningDays("conv")).thenReturn(known);
        ConversationPurgeService service = service(mock(IConversationMemoryStore.class), healthStore);

        service.deleteHealthTurns("conv", "agent");
        verify(dirty).markDeletedRows("conv", List.of("agent"), known);

        when(healthStore.deleteConversationIfDaysKnown("conv")).thenReturn(known);
        service.deleteHealthTurns("conv");
        verify(dirty).markDeletedRows("conv", List.of(), known);
    }

    @Test
    @DisplayName("the unguarded agent-test delete of health rows is best effort; an absent store is skipped")
    void bestEffort() throws Exception {
        IHealthTurnStore healthStore = mock(IHealthTurnStore.class);
        when(healthStore.deleteConversationReturningDays("conv")).thenThrow(new HealthStoreException("down", null));
        when(healthStore.deleteConversationIfDaysKnown("conv")).thenThrow(new HealthStoreException("down", null));
        ConversationPurgeService service = service(mock(IConversationMemoryStore.class), healthStore);
        assertDoesNotThrow(() -> service.deleteHealthTurns("conv", "agent"));
        assertDoesNotThrow(() -> service.deleteHealthTurns("conv"));

        service.healthTurnStore = null;
        assertDoesNotThrow(() -> service.deleteHealthTurns("conv"));
    }

    @Test
    @DisplayName("unguarded (agent test): the conversation's sessions are deleted with its turn rows, best effort")
    void agentTestPurgeDeletesSessions() throws Exception {
        IHealthTurnStore healthStore = mock(IHealthTurnStore.class);
        when(healthStore.deleteConversationReturningDays("conv")).thenReturn(new HealthDeletedRows(1L, null));
        IHealthOutcomeStore outcomes = mock(IHealthOutcomeStore.class);
        ConversationPurgeService service = service(mock(IConversationMemoryStore.class), healthStore);
        service.healthOutcomeStore = instance(outcomes);

        service.deleteHealthTurns("conv", "agent");
        verify(outcomes).deleteByConversationId("conv");

        when(outcomes.deleteByConversationId("conv")).thenThrow(new HealthStoreException("down", null));
        assertDoesNotThrow(() -> service.deleteHealthTurns("conv", "agent"));
    }
}
