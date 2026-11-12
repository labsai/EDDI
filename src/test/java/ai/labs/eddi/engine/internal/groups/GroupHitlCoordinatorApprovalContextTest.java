/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.IGroupConversationStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.PhaseType;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.configs.groups.model.GroupConversation.HitlPauseType;
import ai.labs.eddi.configs.hitl.model.ApprovalContextBlock;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionEventListener;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.hitl.context.GroupApprovalContextRenderer;
import ai.labs.eddi.engine.hitl.context.RenderedApprovalContext;
import ai.labs.eddi.engine.hitl.context.RenderedApprovalContext.Item;
import ai.labs.eddi.engine.internal.GroupConversationService;
import ai.labs.eddi.engine.lifecycle.GroupConversationEventSink;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The group pause commit renders {@code approvalContext} once, persists it with
 * the pause, hands it to the channel listener and clears it with the pause.
 */
@DisplayName("GroupHitlCoordinator approval context")
class GroupHitlCoordinatorApprovalContextTest {

    private IGroupConversationStore conversationStore;
    private GroupConversationService facade;
    private GroupApprovalContextRenderer renderer;
    private GroupHitlCoordinator coordinator;
    private AgentGroupConfiguration config;
    private GroupConversation gc;
    private final DiscussionPhase phase = new DiscussionPhase("Synthesis", PhaseType.SYNTHESIS);

    @BeforeEach
    void setUp() {
        conversationStore = mock(IGroupConversationStore.class);
        facade = mock(GroupConversationService.class);
        renderer = mock(GroupApprovalContextRenderer.class);
        when(facade.approvalContextRenderer()).thenReturn(renderer);
        coordinator = new GroupHitlCoordinator(mock(IAgentGroupStore.class), conversationStore, mock(IScheduleStore.class),
                mock(AuditLedgerService.class), new GroupSigningGuard(null, null, "default"), new ConcurrentHashMap<>(),
                mock(ExecutorService.class), new CallerIdentityContext(null, null), facade,
                new SimpleMeterRegistry().counter("test.hitl.pause"), new SimpleMeterRegistry().counter("test.hitl.resume"),
                new SimpleMeterRegistry().counter("test.group.failure"));
        gc = new GroupConversation();
        gc.setId("gc-1");
        gc.setGroupId("group-1");
        gc.setUserId("user-1");
        gc.setState(GroupConversationState.IN_PROGRESS);
        config = new AgentGroupConfiguration();
        config.setPhases(List.of(phase));
        var hitl = new AgentGroupConfiguration.HitlConfig();
        hitl.setApprovalContext(List.of(new ApprovalContextBlock("Draft", "{group.question}", null)));
        config.setHitlConfig(hitl);
    }

    private static RenderedApprovalContext context(String text) {
        return RenderedApprovalContext.of(List.of(new Item("Draft", text, false, "group", null, null)), false);
    }

    @Test
    @DisplayName("commitPause renders the context, stores it on the conversation before persisting, and hands it to the listener")
    void commitPauseRendersPersistsAndNotifies() throws Exception {
        when(renderer.render(any(), any(), anyInt(), anyString())).thenReturn(context("the draft"));
        var listener = mock(GroupDiscussionEventListener.class);

        coordinator.commitPause(gc, 0, phase, "PHASE", 3, listener, config);

        assertEquals("the draft", gc.getHitlApprovalContext().getItems().get(0).getText());
        assertEquals(GroupConversationState.AWAITING_APPROVAL, gc.getState());
        verify(renderer).render(gc, config, 0, "PHASE");
        var event = ArgumentCaptor.forClass(GroupConversationEventSink.HitlPauseEvent.class);
        verify(listener).onHitlPause(event.capture());
        assertSame(gc.getHitlApprovalContext(), event.getValue().approvalContext());
    }

    @Test
    @DisplayName("a renderer that throws never fails the pause commit")
    void rendererFailureDoesNotFailTheCommit() throws Exception {
        when(renderer.render(any(), any(), anyInt(), anyString())).thenThrow(new IllegalStateException("render exploded"));
        var listener = mock(GroupDiscussionEventListener.class);

        assertDoesNotThrow(() -> coordinator.commitPause(gc, 0, phase, "PHASE", 3, listener, config));

        assertNull(gc.getHitlApprovalContext());
        assertEquals(GroupConversationState.AWAITING_APPROVAL, gc.getState());
        verify(listener).onHitlPause(any());
    }

    @Test
    @DisplayName("no renderer available means no context")
    void noRenderer() throws Exception {
        when(facade.approvalContextRenderer()).thenReturn(null);

        coordinator.commitPause(gc, 0, phase, "PHASE", 3, null, config);

        assertNull(gc.getHitlApprovalContext());
    }

    @Test
    @DisplayName("a re-pause replaces the previous pause's context")
    void rePauseReplaces() throws Exception {
        when(renderer.render(any(), any(), anyInt(), anyString())).thenReturn(context("first"), (RenderedApprovalContext) null);

        coordinator.commitPause(gc, 0, phase, "PHASE", 3, null, config);
        assertEquals("first", gc.getHitlApprovalContext().getItems().get(0).getText());
        coordinator.commitPause(gc, 0, phase, "PHASE", 3, null, config);

        assertNull(gc.getHitlApprovalContext(), "a stale context must not outlive its pause");
    }

    @Test
    @DisplayName("a no-progress failure clears the context with the pause")
    void failureClearsIt() throws Exception {
        gc.setHitlApprovalContext(context("stale"));
        gc.setPausedAt(Instant.now());

        coordinator.failDiscussionNoProgress(gc, 0, phase, null);

        assertNull(gc.getHitlApprovalContext());
        assertEquals(GroupConversationState.FAILED, gc.getState());
    }

    @Test
    @DisplayName("a restored pause (failed resume) gets its context again")
    void restoreRendersAgain() throws Exception {
        when(renderer.render(any(), any(), anyInt(), anyString())).thenReturn(context("again"));
        gc.setHitlApprovalContext(null);

        coordinator.restoreGroupPause(gc, 0, "Synthesis", HitlPauseType.PHASE, Instant.now(), config, null, null);

        assertEquals("again", gc.getHitlApprovalContext().getItems().get(0).getText());
        verify(renderer).render(eq(gc), eq(config), eq(0), eq("PHASE"));
    }

    @Test
    @DisplayName("the SSE payload of the pause event never carries the approver-facing text")
    void eventJsonOmitsContext() throws Exception {
        var event = new GroupConversationEventSink.HitlPauseEvent(0, "Synthesis", "reason", "PHASE", Instant.parse("2026-11-02T10:00:00Z"),
                context("secret-ish draft"));

        String json = new ObjectMapper().findAndRegisterModules().writeValueAsString(event);

        assertFalse(json.contains("secret-ish draft"), json);
        assertFalse(json.contains("approvalContext"), json);
        assertTrue(json.contains("\"phaseName\":\"Synthesis\""));
    }

    @Test
    @DisplayName("the old five- and four-argument constructors still work and carry no context")
    void compatibilityConstructors() {
        assertNull(new GroupConversationEventSink.HitlPauseEvent(0, "p", "r", "PHASE", Instant.now()).approvalContext());
        assertNull(new GroupConversationEventSink.HitlPauseEvent(0, "p", "r", "PHASE").approvalContext());
    }
}
