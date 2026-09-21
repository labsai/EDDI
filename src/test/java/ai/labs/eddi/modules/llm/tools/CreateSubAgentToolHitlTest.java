/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DynamicAgentConfig;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.ConversationResponseHandler;
import ai.labs.eddi.engine.api.IConversationService.ConversationResult;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.setup.AgentSetupService;
import ai.labs.eddi.engine.setup.SetupResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.stubbing.Answer;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * F15 / Finding 25: {@link CreateSubAgentTool}'s initial-message turn must NOT
 * report a SKIPPED turn as a real "Initial response" (the default
 * onSkipped→onComplete would surface the previous-turn output), and must report
 * a deliberate HITL pause as PAUSED_FOR_APPROVAL rather than a stale reply.
 */
class CreateSubAgentToolHitlTest {

    private AgentSetupService agentSetupService;
    private IConversationService conversationService;
    private DynamicAgentConfig config;
    private CreateSubAgentTool tool;

    @BeforeEach
    void setUp() throws Exception {
        agentSetupService = mock(AgentSetupService.class);
        conversationService = mock(IConversationService.class);

        config = new DynamicAgentConfig();
        config.setEnabled(true);
        config.setAllowCreation(true);

        tool = new CreateSubAgentTool(agentSetupService, conversationService,
                "parent-1", "user-1", config, new ArrayList<>(), new HashSet<>());

        // setupAgent succeeds and yields a deployed sub-agent
        SetupResult setupResult = SetupResult.builder()
                .action("setup_complete").agentId("sub-1").agentName("parent-1/helper")
                .provider("openai").model("gpt-4o").deployed(true).deploymentStatus("READY").build();
        when(agentSetupService.setupAgent(any())).thenReturn(setupResult);

        // A fresh conversation is started for the initial message
        when(conversationService.startConversation(any(), eq("sub-1"), any(), any()))
                .thenReturn(new ConversationResult("conv-init", URI.create("eddi://conv/conv-init")));
    }

    @Test
    void initialTurnSkipped_busy_reportsRetry_notStaleReply() throws Exception {
        // F15/H7: the initial say() is skipped (busy) — the previous-turn output must
        // NOT be reported as the "Initial response".
        var snapshot = new SimpleConversationMemorySnapshot();
        snapshot.setConversationState(ConversationState.IN_PROGRESS);

        Answer<Void> skip = invocation -> {
            ConversationResponseHandler handler = invocation.getArgument(8);
            handler.onSkipped(snapshot);
            return null;
        };
        doAnswer(skip).when(conversationService).say(any(), eq("sub-1"), eq("conv-init"),
                any(), any(), any(), any(), anyBoolean(), any());

        String result = tool.createSubAgent("helper", "You help.", null, null, "bootstrap context", false);

        assertTrue(result.contains("Sub-agent created successfully"), result);
        assertTrue(result.contains("busy"), result);
        assertTrue(result.contains("not delivered"), result);
        assertFalse(result.contains("PAUSED_FOR_APPROVAL"), result);
    }

    @Test
    void initialTurnSkipped_ended_reportsNotActive() throws Exception {
        var snapshot = new SimpleConversationMemorySnapshot();
        snapshot.setConversationState(ConversationState.ENDED);

        Answer<Void> skip = invocation -> {
            ConversationResponseHandler handler = invocation.getArgument(8);
            handler.onSkipped(snapshot);
            return null;
        };
        doAnswer(skip).when(conversationService).say(any(), eq("sub-1"), eq("conv-init"),
                any(), any(), any(), any(), anyBoolean(), any());

        String result = tool.createSubAgent("helper", "You help.", null, null, "bootstrap context", false);

        assertTrue(result.contains("no longer active"), result);
        assertTrue(result.contains("not delivered"), result);
    }

    @Test
    void initialTurnPausesForApproval_reportsPaused() throws Exception {
        // The initial turn pauses for approval — report the pending approval, not a
        // skip/stale reply.
        var snapshot = new SimpleConversationMemorySnapshot();
        snapshot.setConversationState(ConversationState.AWAITING_HUMAN);

        Answer<Void> pause = invocation -> {
            ConversationResponseHandler handler = invocation.getArgument(8);
            handler.onComplete(snapshot);
            return null;
        };
        doAnswer(pause).when(conversationService).say(any(), eq("sub-1"), eq("conv-init"),
                any(), any(), any(), any(), anyBoolean(), any());

        String result = tool.createSubAgent("helper", "You help.", null, null, "bootstrap context", false);

        assertTrue(result.contains("PAUSED_FOR_APPROVAL"), result);
        assertTrue(result.contains("conv-init"), result);
        assertTrue(result.contains("/resume"), result);
    }
}
