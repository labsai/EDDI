/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.hitl.HitlTimeoutPolicy;
import ai.labs.eddi.configs.hitl.model.ApprovalContextBlock;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.hitl.context.ApprovalContextRenderer;
import ai.labs.eddi.engine.hitl.context.RenderedApprovalContext;
import ai.labs.eddi.engine.hitl.context.RenderedApprovalContext.Item;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.runtime.IConversationCoordinator;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.enterprise.event.Event;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code populateHitlTimeoutBookmark} runs before every pause persist (the
 * {@code say}, {@code CONVERSATION_START} and re-pause-on-resume call sites),
 * so the approval context is rendered there once per pause.
 */
@SuppressWarnings("unchecked")
class ConversationHitlServiceApprovalContextTest {

    private ConversationService conversationService;
    private IAgentStore agentStore;
    private ApprovalContextRenderer renderer;
    private ConversationHitlService service;
    private ConversationMemory memory;

    @BeforeEach
    void setUp() throws Exception {
        conversationService = mock(ConversationService.class);
        agentStore = mock(IAgentStore.class);
        renderer = mock(ApprovalContextRenderer.class);
        when(conversationService.approvalContextRenderer()).thenReturn(renderer);
        service = new ConversationHitlService(conversationService, mock(IConversationMemoryStore.class), mock(IConversationCoordinator.class),
                mock(IRuntime.class), mock(IContextLogger.class), new CallerIdentityContext(null, null), mock(AuditLedgerService.class),
                mock(IScheduleStore.class), agentStore, mock(IJsonSerialization.class), mock(Event.class), mock(Counter.class),
                mock(Counter.class), new SimpleMeterRegistry(), new ConcurrentHashMap<String, IConversationMemory>());
        memory = new ConversationMemory("conv-1", "agent-1", 3, "user-1");
        memory.setHitlPauseReason("Requires human approval");
    }

    private void agentWithContext(List<ApprovalContextBlock> blocks) throws Exception {
        var agent = new AgentConfiguration();
        var hitl = new AgentConfiguration.HitlConfig();
        hitl.setTimeoutPolicy(HitlTimeoutPolicy.AUTO_REJECT);
        hitl.setApprovalTimeout("PT4H");
        hitl.setApprovalContext(blocks);
        agent.setHitlConfig(hitl);
        when(agentStore.read("agent-1", 3)).thenReturn(agent);
    }

    private static RenderedApprovalContext context(String text) {
        return RenderedApprovalContext.of(List.of(new Item("Draft", text, false, "agent", null, null)), false);
    }

    @Test
    @DisplayName("the configured blocks are rendered onto the memory at pause commit, next to the timeout bookmark")
    void rendersAtPauseCommit() throws Exception {
        var blocks = List.of(new ApprovalContextBlock("Draft", "{properties.draft}", null));
        agentWithContext(blocks);
        when(renderer.renderForConversation(any(), any(), any())).thenReturn(context("the draft"));

        service.populateHitlTimeoutBookmark(memory);

        assertEquals("the draft", memory.getHitlApprovalContext().getItems().get(0).getText());
        assertEquals(HitlTimeoutPolicy.AUTO_REJECT, memory.getHitlTimeoutPolicy(), "the existing bookmark behaviour is unchanged");
        verify(renderer).renderForConversation(memory, blocks, null);
    }

    @Test
    @DisplayName("a re-pause overwrites the previous pause's context; a pause without blocks leaves none")
    void rePauseOverwrites() throws Exception {
        agentWithContext(List.of(new ApprovalContextBlock("Draft", "{x}", null)));
        when(renderer.renderForConversation(any(), any(), any())).thenReturn(context("first")).thenReturn(context("second"));

        service.populateHitlTimeoutBookmark(memory);
        assertEquals("first", memory.getHitlApprovalContext().getItems().get(0).getText());
        service.populateHitlTimeoutBookmark(memory);
        assertEquals("second", memory.getHitlApprovalContext().getItems().get(0).getText());

        when(renderer.renderForConversation(any(), any(), any())).thenReturn(null);
        service.populateHitlTimeoutBookmark(memory);
        assertNull(memory.getHitlApprovalContext(), "a stale context must not survive into a pause that renders none");
    }

    @Test
    @DisplayName("an agent without hitlConfig still lets the renderer use the pending batch's task-level toolApprovals")
    void noHitlConfigStillRendersTaskLevelRules() throws Exception {
        when(agentStore.read("agent-1", 3)).thenReturn(new AgentConfiguration());
        memory.setHitlApprovalContext(context("stale"));
        when(renderer.renderForConversation(any(), any(), any())).thenReturn(context("from the rule"));

        service.populateHitlTimeoutBookmark(memory);

        assertEquals("from the rule", memory.getHitlApprovalContext().getItems().get(0).getText());
        verify(renderer).renderForConversation(memory, null, null);
    }

    @Test
    @DisplayName("no renderer available (hand-built service) renders nothing and does not fail the pause")
    void noRenderer() throws Exception {
        agentWithContext(List.of(new ApprovalContextBlock("Draft", "{x}", null)));
        when(conversationService.approvalContextRenderer()).thenReturn(null);

        assertDoesNotThrow(() -> service.populateHitlTimeoutBookmark(memory));

        assertNull(memory.getHitlApprovalContext());
        assertEquals(HitlTimeoutPolicy.AUTO_REJECT, memory.getHitlTimeoutPolicy());
    }

    @Test
    @DisplayName("a renderer that throws never fails or changes the pause commit")
    void rendererFailureDoesNotFailThePause() throws Exception {
        agentWithContext(List.of(new ApprovalContextBlock("Draft", "{x}", null)));
        when(renderer.renderForConversation(any(), any(), any())).thenThrow(new IllegalStateException("render exploded"));

        assertDoesNotThrow(() -> service.populateHitlTimeoutBookmark(memory));

        assertNull(memory.getHitlApprovalContext());
        assertEquals(HitlTimeoutPolicy.AUTO_REJECT, memory.getHitlTimeoutPolicy(), "the bookmark was populated before the context");
        assertEquals("PT4H", memory.getHitlApprovalTimeout());
    }
}
