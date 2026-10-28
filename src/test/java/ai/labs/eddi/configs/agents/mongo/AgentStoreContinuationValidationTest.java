/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.agents.mongo;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.engine.continuation.ContinuationPolicy;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IPropertiesHandler;
import ai.labs.eddi.engine.runtime.internal.Agent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

@DisplayName("AgentStore self-continuation validation")
class AgentStoreContinuationValidationTest {

    private static AgentConfiguration with(Integer maxContinuations, String input) {
        var config = new AgentConfiguration();
        config.setMaxContinuations(maxContinuations);
        config.setContinuationInput(input);
        return config;
    }

    @Test
    @DisplayName("unset, 0 and the bounds are accepted")
    void acceptsTheRange() {
        assertDoesNotThrow(() -> AgentStore.validateContinuation(with(null, null)));
        assertDoesNotThrow(() -> AgentStore.validateContinuation(with(0, "")));
        assertDoesNotThrow(() -> AgentStore.validateContinuation(with(AgentStore.MAX_CONTINUATIONS, "x".repeat(
                AgentStore.MAX_CONTINUATION_INPUT_LENGTH))));
    }

    @Test
    @DisplayName("a negative count or one above the cap is refused with an actionable message")
    void refusesOutOfRange() {
        var negative = assertThrows(IllegalArgumentException.class, () -> AgentStore.validateContinuation(with(-1, null)));
        assertTrue(negative.getMessage().contains("maxContinuations"));
        var tooMany = assertThrows(IllegalArgumentException.class,
                () -> AgentStore.validateContinuation(with(AgentStore.MAX_CONTINUATIONS + 1, null)));
        assertTrue(tooMany.getMessage().contains(String.valueOf(AgentStore.MAX_CONTINUATIONS)));
    }

    @Test
    @DisplayName("an overlong continuationInput is refused")
    void refusesOverlongInput() {
        var e = assertThrows(IllegalArgumentException.class,
                () -> AgentStore.validateContinuation(with(1, "x".repeat(AgentStore.MAX_CONTINUATION_INPUT_LENGTH + 1))));
        assertTrue(e.getMessage().contains("continuationInput"));
    }

    @Test
    @DisplayName("both fields survive the JSON round trip of a stored agent")
    void jsonRoundTrip() throws Exception {
        var mapper = new ObjectMapper();
        var configured = mapper.readValue("{\"workflows\":[],\"maxContinuations\":20,\"continuationInput\":\"continue\"}",
                AgentConfiguration.class);
        var roundTripped = mapper.readValue(mapper.writeValueAsString(configured), AgentConfiguration.class);
        assertEquals(20, roundTripped.getMaxContinuations());
        assertEquals("continue", roundTripped.getContinuationInput());
    }

    @Test
    @DisplayName("every turn carries the running version's settings onto memory, and a version without them switches it off")
    void agentCarriesThePolicyOntoEveryTurn() throws Exception {
        var agent = new Agent("0123456789abcdef01234567", 1);
        agent.setContinuationPolicy(new ContinuationPolicy(4, "go"));
        IConversationMemory memory = new ConversationMemory("0123456789abcdef89abcdef", "agent", 1, "user");

        agent.continueConversation(memory, mock(IPropertiesHandler.class), null);
        assertEquals(4, memory.getContinuationPolicy().maxContinuations());

        new Agent("0123456789abcdef01234567", 2).continueConversation(memory, mock(IPropertiesHandler.class), null);
        assertFalse(memory.getContinuationPolicy().enabled());
    }

    @Test
    @DisplayName("the deployed policy: unset is off, a count switches it on, the input defaults to empty")
    void policyOfTheStoredSettings() {
        assertSame(ContinuationPolicy.OFF, ContinuationPolicy.of(null, null));
        assertFalse(ContinuationPolicy.of(0, null).enabled());
        var on = ContinuationPolicy.of(5, null);
        assertTrue(on.enabled());
        assertEquals(5, on.maxContinuations());
        assertEquals("", on.input());
        assertEquals("go", ContinuationPolicy.of(2, "go").input());
    }
}
