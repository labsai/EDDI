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
import ai.labs.eddi.engine.model.InputData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Finding F18 (follow-up): the delegation-depth guardrail was inert in
 * production. The hop count only rode on the callee's {@code startConversation}
 * context, which {@code Conversation.init()} materializes on step <b>0</b> —
 * but the delegated message is processed on the <b>next</b> step, so
 * {@code AgentOrchestrator.resolveDelegationDepth} read nothing and every
 * delegated agent resolved depth 0. The depth must ride on every per-turn
 * {@link InputData} too, exactly as {@code GroupConversationService} does for
 * {@code groupDepth}.
 * <p>
 * The end-to-end half of this — replaying the callee's memory and resolving the
 * depth back out of it — lives in
 * {@code AgentOrchestratorBuiltInToolWiringTest}, where the package-private
 * resolver is visible.
 */
@DisplayName("ConverseWithAgentTool — delegation depth reaches the turn that processes the message")
class ConverseWithAgentToolDepthPropagationTest {

    private IConversationService conversationService;

    @BeforeEach
    void setUp() throws Exception {
        conversationService = mock(IConversationService.class);
        lenient().when(conversationService.startConversation(any(), anyString(), any(), any()))
                .thenReturn(new ConversationResult("conv-b", null));
        lenient().doAnswer(invocation -> {
            IConversationService.ConversationResponseHandler handler = invocation.getArgument(8);
            handler.onComplete(new SimpleConversationMemorySnapshot());
            return null;
        }).when(conversationService).say(any(), anyString(), anyString(), anyBoolean(), anyBoolean(), any(), any(), anyBoolean(), any());
    }

    private static DynamicAgentConfig permissive() {
        var config = new DynamicAgentConfig();
        config.setEnabled(true);
        config.setAllowDelegation(true);
        return config;
    }

    private InputData capturedInputData() throws Exception {
        ArgumentCaptor<InputData> captor = ArgumentCaptor.forClass(InputData.class);
        verify(conversationService).say(eq(Environment.production), anyString(), anyString(),
                anyBoolean(), anyBoolean(), any(), captor.capture(), anyBoolean(), any());
        return captor.getValue();
    }

    @Test
    @DisplayName("the message turn carries the hop count, not just the conversation start")
    void depthRidesOnThePerTurnInputData() throws Exception {
        var tool = new ConverseWithAgentTool(conversationService, "user-1", permissive(), 1);

        tool.converseWithAgent("agent-b", "hello", null);

        Map<String, Context> turnContext = capturedInputData().getContext();
        assertNotNull(turnContext, "the say turn carried no context at all — the callee resolves depth 0");
        Context depth = turnContext.get(ConverseWithAgentTool.CONTEXT_DELEGATION_DEPTH);
        assertNotNull(depth, "delegationDepth missing from the message turn");
        assertEquals("2", String.valueOf(depth.getValue()));
    }

    @Test
    @DisplayName("continuing an existing conversation also carries the hop count")
    void depthRidesOnAMultiTurnFollowUp() throws Exception {
        var tool = new ConverseWithAgentTool(conversationService, "user-1", permissive(), 2);

        tool.converseWithAgent("agent-b", "follow-up", "conv-existing");

        verify(conversationService, never()).startConversation(any(), anyString(), any(), any());
        Context depth = capturedInputData().getContext().get(ConverseWithAgentTool.CONTEXT_DELEGATION_DEPTH);
        assertNotNull(depth, "a follow-up turn used to send no depth at all");
        assertEquals("3", String.valueOf(depth.getValue()));
    }

    @Test
    @DisplayName("the start context keeps carrying the hop count too")
    void depthStillRidesOnTheStartContext() throws Exception {
        new ConverseWithAgentTool(conversationService, "user-1", permissive(), 0)
                .converseWithAgent("agent-b", "hi", null);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Context>> captor = ArgumentCaptor.forClass(Map.class);
        verify(conversationService).startConversation(eq(Environment.production), eq("agent-b"), eq("user-1"), captor.capture());

        Context depth = captor.getValue().get(ConverseWithAgentTool.CONTEXT_DELEGATION_DEPTH);
        assertNotNull(depth);
        assertEquals("1", String.valueOf(depth.getValue()));
    }

    @Test
    @DisplayName("a null config means permissive defaults, as the javadoc promises — not refuse-everything")
    void nullConfigIsPermissiveNotDisabled() throws Exception {
        var tool = new ConverseWithAgentTool(conversationService, "user-1", null, 0);

        String result = tool.converseWithAgent("agent-b", "hello", null);

        assertTrue(result.contains("conv-b"), "a null config must behave like the two-arg constructor: " + result);
        verify(conversationService).startConversation(any(), eq("agent-b"), eq("user-1"), any());
    }

    @Test
    @DisplayName("a null config is still bounded by the DynamicAgentConfig depth default")
    void nullConfigStillEnforcesDepth() throws Exception {
        var tool = new ConverseWithAgentTool(conversationService, "user-1", null, 3);

        String result = tool.converseWithAgent("agent-b", "hello", null);

        assertTrue(result.contains("Maximum delegation depth"), result);
        verify(conversationService, never()).startConversation(any(), anyString(), any(), any());
    }
}
