/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.model.Context;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ConversationService.cancelInFlightTurn — only the turn that carries the context")
class ConversationServiceCancelInFlightTurnTest {

    private ConversationService service;
    private ConcurrentHashMap<String, IConversationMemory> inFlight;

    @BeforeEach
    void setUp() throws Exception {
        service = mock(ConversationService.class, CALLS_REAL_METHODS);
        inFlight = new ConcurrentHashMap<>();
        Field field = ConversationService.class.getDeclaredField("inFlightConversations");
        field.setAccessible(true);
        field.set(service, inFlight);
    }

    @SuppressWarnings("unchecked")
    private IConversationMemory turnCarrying(String key, String value) {
        IConversationMemory memory = mock(IConversationMemory.class);
        IWritableConversationStep step = mock(IWritableConversationStep.class);
        when(memory.getCurrentStep()).thenReturn(step);
        if (key != null) {
            IData<Object> data = mock(IData.class);
            when(data.getResult()).thenReturn(new Context(Context.ContextType.string, value));
            when(step.getData("context:" + key)).thenReturn(data);
        }
        return memory;
    }

    @Test
    @DisplayName("the turn with this fire's run id is signalled")
    void signalsTheMatchingTurn() {
        IConversationMemory fire = turnCarrying("scheduleFireId", "s@1");
        inFlight.put("conv", fire);

        assertTrue(service.cancelInFlightTurn("conv", "scheduleFireId", "s@1"));

        verify(fire).setCancelled(true);
    }

    @Test
    @DisplayName("a human's turn, or another fire's, running in the same conversation is left alone")
    void leavesOtherTurnsAlone() {
        IConversationMemory human = turnCarrying(null, null);
        inFlight.put("conv", human);
        assertFalse(service.cancelInFlightTurn("conv", "scheduleFireId", "s@1"));
        verify(human, never()).setCancelled(true);

        IConversationMemory otherFire = turnCarrying("scheduleFireId", "s@2");
        inFlight.put("conv", otherFire);
        assertFalse(service.cancelInFlightTurn("conv", "scheduleFireId", "s@1"));
        verify(otherFire, never()).setCancelled(true);
    }

    @Test
    @DisplayName("nothing in flight, or no ids: nothing happens")
    void nothingInFlight() {
        assertFalse(service.cancelInFlightTurn("conv", "scheduleFireId", "s@1"));
        assertFalse(service.cancelInFlightTurn(null, "scheduleFireId", "s@1"));
        assertFalse(service.cancelInFlightTurn("conv", null, "s@1"));
        assertFalse(service.cancelInFlightTurn("conv", "scheduleFireId", null));
    }
}
