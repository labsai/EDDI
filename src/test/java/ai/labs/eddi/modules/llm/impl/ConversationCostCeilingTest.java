/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.engine.memory.ConversationMemory;
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

import java.util.OptionalDouble;

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

    @Test
    @DisplayName("the ceiling is checked before the tenant budget and refuses once reached; 0 refuses everything, negative is unlimited")
    void gateRefusesAtTheCeiling() {
        TenantQuotaService tenant = mock(TenantQuotaService.class);
        when(tenant.getDefaultTenantId()).thenReturn("default");
        when(tenant.checkCostBudget("default")).thenReturn(QuotaCheckResult.OK);
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
