/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DynamicAgentConfig;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.ConversationResult;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.model.Deployment.Environment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Finding F18 — {@code converse_with_agent} had no guardrails at all:
 * {@code allowDelegation} was never checked, there was no target allowlist, and
 * nothing bounded delegation depth. Because a call without a
 * {@code conversationId} starts a FRESH conversation, the busy-guard could not
 * break an A→B→A cycle.
 * <p>
 * Finding I4 — {@code maxDelegationsPerTask} was documented as an enforced cap
 * but never read.
 */
@DisplayName("ConverseWithAgentTool — delegation guardrails (F18 / I4)")
class ConverseWithAgentToolGuardrailsTest {

    private IConversationService conversationService;

    @BeforeEach
    void setUp() throws Exception {
        conversationService = mock(IConversationService.class);
        lenient().when(conversationService.startConversation(any(), anyString(), any(), any()))
                .thenReturn(new ConversationResult("conv-1", null));
        // say(...) completes immediately with an empty snapshot
        lenient().doAnswer(invocation -> {
            IConversationService.ConversationResponseHandler handler = invocation.getArgument(8);
            handler.onComplete(new SimpleConversationMemorySnapshot());
            return null;
        }).when(conversationService).say(any(), anyString(), anyString(), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(), any());
    }

    private static DynamicAgentConfig config(boolean allowDelegation, int maxDepth, int maxPerTask, List<String> allowed) {
        var config = new DynamicAgentConfig();
        config.setEnabled(true);
        config.setAllowDelegation(allowDelegation);
        config.setMaxDelegationDepth(maxDepth);
        config.setMaxDelegationsPerTask(maxPerTask);
        config.setAllowedDelegationTargets(allowed);
        return config;
    }

    @Test
    @DisplayName("allowDelegation=false refuses the call and never reaches the conversation service")
    void allowDelegationIsEnforced() throws Exception {
        var tool = new ConverseWithAgentTool(conversationService, "user-1", config(false, 3, 3, null), 0);

        String result = tool.converseWithAgent("agent-b", "hello", null);

        assertTrue(result.contains("not enabled"), result);
        verify(conversationService, never()).startConversation(any(), anyString(), any(), any());
    }

    @Test
    @DisplayName("a target outside the allowlist is refused")
    void allowlistIsEnforced() throws Exception {
        var tool = new ConverseWithAgentTool(conversationService, "user-1", config(true, 3, 3, List.of("agent-approved")), 0);

        String result = tool.converseWithAgent("agent-rogue", "hello", null);

        assertTrue(result.contains("not an allowed delegation target"), result);
        verify(conversationService, never()).startConversation(any(), anyString(), any(), any());
    }

    @Test
    @DisplayName("a target inside the allowlist is permitted")
    void allowlistPermitsListedTarget() throws Exception {
        var tool = new ConverseWithAgentTool(conversationService, "user-1", config(true, 3, 3, List.of("agent-approved")), 0);

        String result = tool.converseWithAgent("agent-approved", "hello", null);

        assertTrue(!result.contains("not an allowed delegation target"), result);
        verify(conversationService).startConversation(any(), eq("agent-approved"), any(), any());
    }

    @Test
    @DisplayName("an A→B→A cycle terminates at the configured depth")
    void cycleTerminatesAtMaxDepth() throws Exception {
        var guardrails = config(true, 2, 10, null);

        // A (depth 0) delegates to B, B (depth 1) delegates back to A — both allowed…
        assertTrue(new ConverseWithAgentTool(conversationService, "u", guardrails, 0)
                .converseWithAgent("agent-b", "hi", null).contains("conv-1"));
        assertTrue(new ConverseWithAgentTool(conversationService, "u", guardrails, 1)
                .converseWithAgent("agent-a", "hi", null).contains("conv-1"));

        // …and at depth 2 A's next hop back to B would exceed maxDelegationDepth=2
        // and is refused, which is what terminates the cycle.
        String atLimit = new ConverseWithAgentTool(conversationService, "u", guardrails, 2)
                .converseWithAgent("agent-c", "hi", null);

        assertTrue(atLimit.contains("Maximum delegation depth"), atLimit);
        verify(conversationService, never()).startConversation(any(), eq("agent-c"), any(), any());
        verify(conversationService, times(2)).startConversation(any(), anyString(), any(), any());
    }

    @Test
    @DisplayName("the hop count is propagated to the callee so the next hop knows its depth")
    void propagatesDepthToCallee() throws Exception {
        var tool = new ConverseWithAgentTool(conversationService, "user-1", config(true, 5, 5, null), 1);

        tool.converseWithAgent("agent-b", "hello", null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Context>> captor = ArgumentCaptor.forClass(Map.class);
        verify(conversationService).startConversation(eq(Environment.production), eq("agent-b"), eq("user-1"), captor.capture());

        Context depth = captor.getValue().get(ConverseWithAgentTool.CONTEXT_DELEGATION_DEPTH);
        assertTrue(depth != null, "delegation depth must be propagated; without it the cycle guard cannot work");
        assertEquals("2", String.valueOf(depth.getValue()));
    }

    @Test
    @DisplayName("I4: maxDelegationsPerTask bounds how many delegations one task execution may make")
    void maxDelegationsPerTaskIsEnforced() throws Exception {
        var tool = new ConverseWithAgentTool(conversationService, "user-1", config(true, 5, 2, null), 0);

        assertTrue(tool.converseWithAgent("agent-b", "one", null).contains("conv-1"));
        assertTrue(tool.converseWithAgent("agent-b", "two", null).contains("conv-1"));

        String third = tool.converseWithAgent("agent-b", "three", null);

        assertTrue(third.contains("Maximum delegations for this task"), third);
        verify(conversationService, times(2)).startConversation(any(), anyString(), any(), any());
    }
}
