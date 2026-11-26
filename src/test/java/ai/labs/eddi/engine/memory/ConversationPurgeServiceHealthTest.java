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
import ai.labs.eddi.engine.health.HealthErasureWriter;
import ai.labs.eddi.engine.health.HealthStoreException;
import ai.labs.eddi.engine.health.HealthTurnRecorder;
import ai.labs.eddi.engine.health.IHealthTurnStore;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.model.ConversationState;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Duration;
import java.util.List;

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

    @SuppressWarnings("unchecked")
    private static ConversationPurgeService service(IConversationMemoryStore memoryStore, IHealthTurnStore healthStore) throws Exception {
        var service = new ConversationPurgeService(memoryStore, mock(IConversationDescriptorStore.class), mock(IConversationService.class),
                mock(Instance.class), mock(Instance.class));
        Instance<IHealthTurnStore> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(healthStore);
        service.healthTurnStore = instance;
        HealthTurnRecorder recorder = mock(HealthTurnRecorder.class);
        when(recorder.awaitDrained(any())).thenReturn(true);
        service.useErasureGuard(new ErasureGuard(new InMemoryErasureTombstoneStore(), List.of(new HealthErasureWriter(recorder, healthStore)),
                new ErasureMetrics(new SimpleMeterRegistry()), Duration.ofDays(400), Duration.ofSeconds(1), Duration.ofMinutes(5),
                Clock.systemUTC()));
        return service;
    }

    @Test
    @DisplayName("purge erases the conversation's health rows through the erasure guard, before the snapshot")
    void purgeErasesRows() throws Exception {
        IConversationMemoryStore memoryStore = mock(IConversationMemoryStore.class);
        when(memoryStore.getConversationState("conv")).thenReturn(ConversationState.READY);
        IHealthTurnStore healthStore = mock(IHealthTurnStore.class);

        service(memoryStore, healthStore).purge("conv");

        InOrder order = inOrder(healthStore, memoryStore);
        order.verify(healthStore).deleteByConversationId("conv");
        order.verify(healthStore).countByConversationId("conv");
        order.verify(memoryStore).deleteConversationMemorySnapshot("conv");
        // Once, by the guard: no second, unguarded delete after the snapshot.
        verify(healthStore, times(1)).deleteByConversationId("conv");
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
    @DisplayName("the unguarded agent-test delete of health rows is best effort; an absent store is skipped")
    void bestEffort() throws Exception {
        IHealthTurnStore healthStore = mock(IHealthTurnStore.class);
        when(healthStore.deleteByConversationId("conv")).thenThrow(new HealthStoreException("down", null));
        ConversationPurgeService service = service(mock(IConversationMemoryStore.class), healthStore);
        assertDoesNotThrow(() -> service.deleteHealthTurns("conv"));

        service.healthTurnStore = null;
        assertDoesNotThrow(() -> service.deleteHealthTurns("conv"));
    }
}
