/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IConversationStepStack;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.schedule.model.ScheduleConfiguration;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A scheduled fire's {@code maxCostPerFire} through the conversation cost
 * ceiling the LLM spend gate (and the tool loop) already enforce.
 */
@DisplayName("LlmSpendGate — maxCostPerFire")
class LlmSpendGateFireCeilingTest {

    private final LlmSpendGate gate = new LlmSpendGate(null, new SimpleMeterRegistry());

    /**
     * @param allowance
     *            the fire's maxCostPerFire on the current step, or null for a turn
     *            that is no scheduled fire
     * @param agentCeiling
     *            the agent's maxCostPerConversationUsd, or null
     * @param priorSteps
     *            audit:cost of the earlier steps
     * @param currentStep
     *            audit:cost of the current (the fire's) step
     */
    @SuppressWarnings("unchecked")
    private static IConversationMemory memory(Double allowance, Double agentCeiling, double priorSteps, double currentStep) {
        IConversationMemory memory = mock(IConversationMemory.class);
        when(memory.getConversationId()).thenReturn("0123456789abcdef01234567");
        when(memory.getConversationCostCeilingUsd())
                .thenReturn(agentCeiling != null ? OptionalDouble.of(agentCeiling) : OptionalDouble.empty());
        IWritableConversationStep step = mock(IWritableConversationStep.class);
        when(memory.getCurrentStep()).thenReturn(step);
        if (allowance != null) {
            IData<Object> context = mock(IData.class);
            when(context.getResult()).thenReturn(new Context(Context.ContextType.object, allowance));
            lenient().when(step.getData("context:" + ScheduleConfiguration.CONTEXT_MAX_COST_PER_FIRE)).thenReturn((IData) context);
        }
        IData<Object> current = mock(IData.class);
        when(current.getResult()).thenReturn(currentStep);
        lenient().when(step.getData(MemoryKeys.AUDIT_COST)).thenReturn((IData) current);

        IConversationStepStack steps = mock(IConversationStepStack.class);
        List<IData<Object>> costs = new ArrayList<>();
        IData<Object> prior = mock(IData.class);
        when(prior.getResult()).thenReturn(priorSteps);
        costs.add(prior);
        costs.add(current);
        lenient().when(steps.getExactDataPerStep(MemoryKeys.AUDIT_COST)).thenReturn((List) costs);
        lenient().when(memory.getAllSteps()).thenReturn(steps);
        return memory;
    }

    @Test
    @DisplayName("a fire past its allowance is refused, whatever the conversation spent before it")
    void fireOverItsAllowanceIsRefused() {
        // $1.00 spent before the fire, $0.60 by it, allowance $0.50: refuse.
        var refusal = gate.check(memory(0.5, null, 1.0, 0.6));

        assertNotNull(refusal, "maxCostPerFire must stop the fire's spend");
        assertEquals(LlmSpendGate.REASON_CONVERSATION_CEILING, refusal.reason());
    }

    @Test
    @DisplayName("a fire within its allowance proceeds, even though the conversation total exceeds it")
    void fireWithinItsAllowanceProceeds() {
        // Persistent heartbeat: $1.00 before, $0.20 now, allowance $0.50 — per fire,
        // not lifetime.
        assertNull(gate.check(memory(0.5, null, 1.0, 0.2)));
    }

    @Test
    @DisplayName("the agent's own ceiling still wins when it is the tighter one")
    void agentCeilingWinsWhenTighter() {
        assertEquals(OptionalDouble.of(1.1), LlmSpendGate.effectiveCeiling(memory(0.5, 1.1, 1.0, 0.0)));
        assertEquals(1.5, LlmSpendGate.effectiveCeiling(memory(0.5, 5.0, 1.0, 0.0)).getAsDouble(), 1e-9);
    }

    @Test
    @DisplayName("a turn that is no scheduled fire keeps exactly the agent's ceiling")
    void noFireNoChange() {
        assertTrue(LlmSpendGate.effectiveCeiling(memory(null, null, 1.0, 0.0)).isEmpty());
        assertEquals(OptionalDouble.of(2.0), LlmSpendGate.effectiveCeiling(memory(null, 2.0, 1.0, 0.0)));
        assertNull(gate.check(memory(null, null, 100.0, 50.0)));
    }
}
