/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.ConversationMemoryUtilities;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IPropertiesHandler;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.runtime.internal.Agent;
import ai.labs.eddi.engine.tenancy.TenantQuotaService;
import ai.labs.eddi.engine.tenancy.model.QuotaCheckResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.OptionalDouble;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The agent-level {@code maxCostPerConversationUsd}: stored-config
 * compatibility, the carrier that brings it onto memory, and the spend it is
 * compared against.
 */
@DisplayName("maxCostPerConversationUsd — config, carrier and conversation spend")
class ConversationCostCeilingTest {

    private static final String CONVERSATION_ID = "0123456789abcdef01234567";

    @Test
    @DisplayName("an agent stored before the field existed deserializes with no ceiling, and the field round-trips")
    void storedConfigCompatibility() throws Exception {
        ObjectMapper mapper = new ObjectMapper();

        AgentConfiguration old = mapper.readValue("{\"workflows\":[]}", AgentConfiguration.class);
        assertNull(old.getMaxCostPerConversationUsd());

        AgentConfiguration configured = mapper.readValue("{\"workflows\":[],\"maxCostPerConversationUsd\":0.5}", AgentConfiguration.class);
        assertEquals(0.5, configured.getMaxCostPerConversationUsd());
        AgentConfiguration roundTripped = mapper.readValue(mapper.writeValueAsString(configured), AgentConfiguration.class);
        assertEquals(0.5, roundTripped.getMaxCostPerConversationUsd());
    }

    @Test
    @DisplayName("every turn carries the running version's ceiling onto memory — and clears one the version does not have")
    void continueConversationCarriesTheCeiling() throws Exception {
        Agent agent = new Agent("0123456789abcdef01234567", 1);
        agent.setMaxCostPerConversationUsd(0.25);
        IConversationMemory memory = new ConversationMemory(CONVERSATION_ID, "agent", 1, "user");

        agent.continueConversation(memory, mock(IPropertiesHandler.class), null);
        assertEquals(0.25, memory.getConversationCostCeilingUsd().getAsDouble());

        Agent unlimited = new Agent("0123456789abcdef01234567", 2);
        unlimited.continueConversation(memory, mock(IPropertiesHandler.class), null);
        assertTrue(memory.getConversationCostCeilingUsd().isEmpty());
    }

    @Test
    @DisplayName("the conversation's spend is the sum of every step's audit:cost, the current one included")
    void conversationCostSumsAllSteps() {
        ConversationMemory memory = new ConversationMemory(CONVERSATION_ID, "agent", 1, "user");
        memory.getCurrentStep().storeData(new Data<>(MemoryKeys.AUDIT_COST, 0.10));
        memory.startNextStep();
        // An unpriced step contributes nothing.
        memory.startNextStep();
        memory.getCurrentStep().storeData(new Data<>(MemoryKeys.AUDIT_COST, 0.05));
        // The metering marker must not be counted as cost.
        memory.getCurrentStep().storeData(new Data<>(MemoryKeys.AUDIT_METERED_COST, 0.05));

        assertEquals(0.15, LlmSpendGate.conversationCost(memory), 1e-12);
    }

    /**
     * say → undo → say used to reset the spend: undo moved the priced step to the
     * redo cache and the next say cleared it, so a public /undo let one
     * conversation run past its ceiling without limit.
     */
    @Test
    @DisplayName("undo cannot lower the spend: undone steps count, and so do the ones a new turn discards — across a store round trip")
    void undoDoesNotDiscardSpend() {
        ConversationMemory memory = new ConversationMemory(CONVERSATION_ID, "agent", 1, "user");
        memory.getCurrentStep().storeData(new Data<>(MemoryKeys.AUDIT_COST, 0.30));
        memory.startNextStep();
        memory.getCurrentStep().storeData(new Data<>(MemoryKeys.AUDIT_COST, 0.40));

        memory.undoLastStep();
        assertEquals(0.70, LlmSpendGate.conversationCost(memory), 1e-12, "an undone step's spend still counts (a rerun runs now)");
        assertEquals(0.70, ConversationMemoryUtilities
                .convertSimpleConversationMemory(ConversationMemoryUtilities.convertConversationMemory(memory), false, false)
                .getConversationCost(), 1e-12, "the snapshot total does not drop between the undo and the next say");
        assertEquals(0.70, ConversationMemoryUtilities.convertSimpleConversationMemorySnapshot(memory, false, true, List.of())
                .getConversationCost(), 1e-12, "nor does the current-step projection's");
        memory.redoLastStep();
        assertEquals(0.70, LlmSpendGate.conversationCost(memory), 1e-12, "redo neither adds nor removes");

        memory.undoLastStep();
        memory.startNextStep(); // the next say discards the redo cache
        assertEquals(0.70, LlmSpendGate.conversationCost(memory), 1e-12, "the discarded step's spend moved onto the new turn");

        // Persisted and reloaded, as every REST call does.
        IConversationMemory reloaded = ConversationMemoryUtilities
                .convertConversationMemorySnapshot(ConversationMemoryUtilities.convertConversationMemory(memory));
        assertEquals(0.70, LlmSpendGate.conversationCost(reloaded), 1e-12);

        // A second cycle: the step that inherited the discarded spend is itself undone
        // and discarded.
        ConversationMemory again = (ConversationMemory) reloaded;
        again.undoLastStep();
        again.startNextStep();
        assertEquals(0.70, LlmSpendGate.conversationCost(again), 1e-12);
        assertEquals(0.70, ConversationMemoryUtilities
                .convertSimpleConversationMemory(ConversationMemoryUtilities.convertConversationMemory(again), false, false)
                .getConversationCost(), 1e-12, "the snapshot's conversation total (group ceiling, schedules) agrees");
        assertEquals(0.70, ConversationMemoryUtilities.convertSimpleConversationMemorySnapshot(again, false, true, List.of())
                .getConversationCost(), 1e-12, "and so does the current-step projection's");

        again.setConversationCostCeilingUsd(0.70);
        assertNotNull(new LlmSpendGate(null, null).check(again), "the ceiling still holds after say → undo → say");
    }

    @Test
    @DisplayName("an unexpected exception from the tenant budget check refuses the model call instead of failing the turn")
    void tenantCheckThatThrowsFailsClosed() {
        TenantQuotaService tenant = mock(TenantQuotaService.class);
        when(tenant.getDefaultTenantId()).thenReturn("default");
        when(tenant.checkCostBudget(eq("default"), anyDouble())).thenThrow(new IllegalStateException("driver exploded"));
        var registry = new SimpleMeterRegistry();
        var gate = new LlmSpendGate(tenant, registry);

        LlmSpendGate.Refusal refusal = gate.check(new ConversationMemory(CONVERSATION_ID, "agent", 1, "user"));
        gate.check(new ConversationMemory(CONVERSATION_ID, "agent", 1, "user"));

        assertNotNull(refusal, "fail closed");
        assertEquals(LlmSpendGate.REASON_TENANT_CHECK_ERROR, refusal.reason(), "a defect must not pass for a store outage");
        assertEquals(2.0, registry.counter(LlmSpendGate.METRIC, "reason", LlmSpendGate.REASON_TENANT_CHECK_ERROR).count(),
                "counted on every refusal, even when the stack trace is rate-limited");
        assertEquals(0.0, registry.counter(LlmSpendGate.METRIC, "reason", LlmSpendGate.REASON_TENANT_ACCOUNTING_UNAVAILABLE).count());
    }

    @Test
    @DisplayName("the ceiling is checked before the tenant budget and refuses once reached; 0 refuses everything, negative is unlimited")
    void gateRefusesAtTheCeiling() {
        TenantQuotaService tenant = mock(TenantQuotaService.class);
        when(tenant.getDefaultTenantId()).thenReturn("default");
        when(tenant.checkCostBudget(eq("default"), anyDouble())).thenReturn(QuotaCheckResult.OK);
        var gate = new LlmSpendGate(tenant, new SimpleMeterRegistry());
        ConversationMemory memory = new ConversationMemory(CONVERSATION_ID, "agent", 1, "user");
        memory.getCurrentStep().storeData(new Data<>(MemoryKeys.AUDIT_COST, 0.30));

        memory.setConversationCostCeilingUsd(0.30);
        LlmSpendGate.Refusal refusal = gate.check(memory);
        assertNotNull(refusal);
        assertEquals(LlmSpendGate.REASON_CONVERSATION_CEILING, refusal.reason());
        verifyNoInteractions(tenant);

        memory.setConversationCostCeilingUsd(0.31);
        assertNull(gate.check(memory), "under the ceiling");

        ConversationMemory fresh = new ConversationMemory(CONVERSATION_ID, "agent", 1, "user");
        fresh.setConversationCostCeilingUsd(0.0);
        assertNotNull(new LlmSpendGate(null, null).check(fresh), "a zero ceiling allows no priced spend at all");

        assertTrue(LlmSpendGate.ceilingApplies(OptionalDouble.of(0.31)));
        assertFalse(LlmSpendGate.ceilingApplies(OptionalDouble.of(-1.0)));
        assertFalse(LlmSpendGate.ceilingApplies(OptionalDouble.empty()));
        assertFalse(LlmSpendGate.ceilingApplies(OptionalDouble.of(Double.NaN)));
    }
}
