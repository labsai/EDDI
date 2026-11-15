/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.hitl.HitlAccessGuard;
import ai.labs.eddi.engine.hitl.context.RenderedApprovalContext;
import ai.labs.eddi.engine.hitl.context.RenderedApprovalContext.Item;
import ai.labs.eddi.engine.hitl.tools.IHitlToolJournalStore;
import ai.labs.eddi.engine.memory.ConversationMemoryTail;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.descriptor.IConversationDescriptorStore;
import ai.labs.eddi.engine.memory.descriptor.model.ConversationDescriptor;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.security.ConversationAccessGuard;
import ai.labs.eddi.engine.security.OwnershipValidator;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * {@code approval-status} serves the context rendered at pause commit, through
 * the current redaction filter, only while the conversation is paused.
 */
@DisplayName("RestAgentEngine approval-status approvalContext")
class RestAgentEngineApprovalContextTest {

    private static final String CONVERSATION_ID = "conv-context";
    private static final String USER_ID = "test-user";

    @Mock
    private IConversationService conversationService;
    @Mock
    private IConversationDescriptorStore conversationDescriptorStore;
    @Mock
    private SecurityIdentity identity;
    @Mock
    private OwnershipValidator ownershipValidator;
    @Mock
    private Principal principal;

    private RestAgentEngine restAgentEngine;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        var descriptor = new ConversationDescriptor();
        descriptor.setUserId(USER_ID);
        doReturn(descriptor).when(conversationDescriptorStore).readDescriptor(anyString(), anyInt());
        doNothing().when(ownershipValidator).requireOwnerOrAdmin(any(), any(), any());
        doReturn(principal).when(identity).getPrincipal();
        doReturn(USER_ID).when(principal).getName();
        var hitlAccessGuard = new HitlAccessGuard(identity, ownershipValidator, conversationDescriptorStore, conversationService,
                mock(IGroupConversationService.class));
        restAgentEngine = new RestAgentEngine(conversationService, mock(IConversationMemoryStore.class), identity, ownershipValidator,
                new ConversationAccessGuard(identity, ownershipValidator, conversationDescriptorStore), mock(ResourceAccessGuard.class),
                hitlAccessGuard, mock(IHitlToolJournalStore.class), mock(AgentDisplayNameResolver.class), 30);
    }

    private ConversationMemorySnapshot snapshot(ConversationState state, RenderedApprovalContext context) throws Exception {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setConversationState(state);
        snapshot.setHitlPausedAt(Instant.parse("2026-11-02T10:00:00Z"));
        snapshot.setHitlApprovalContext(context);
        doReturn(snapshot).when(conversationService).getConversationMemorySnapshot(CONVERSATION_ID);
        lenient().doReturn(snapshot).when(conversationService).getConversationMemorySnapshotForApprover(CONVERSATION_ID);
        lenient().doAnswer(invocation -> ConversationMemoryTail.ofFullSnapshot(snapshot)).when(conversationService)
                .getConversationMemoryTail(CONVERSATION_ID);
        return snapshot;
    }

    private static RenderedApprovalContext context(String text) {
        return RenderedApprovalContext.of(List.of(new Item("Draft", text, false, "agent", null, null)), false);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> summaryOf(Response response) {
        return (Map<String, Object>) response.getEntity();
    }

    @Test
    @DisplayName("while paused the summary carries the items, truncation flag and digest")
    void servedWhilePaused() throws Exception {
        var ctx = context("Acme cuts onboarding to 3 days");
        snapshot(ConversationState.AWAITING_HUMAN, ctx);

        @SuppressWarnings("unchecked")
        var view = (Map<String, Object>) summaryOf(restAgentEngine.getApprovalStatus(CONVERSATION_ID, null)).get("approvalContext");

        assertNotNull(view);
        @SuppressWarnings("unchecked")
        var items = (List<Map<String, Object>>) view.get("items");
        assertEquals("Draft", items.get(0).get("label"));
        assertEquals("Acme cuts onboarding to 3 days", items.get(0).get("text"));
        assertEquals(ctx.getDigest(), view.get("digest"));
        assertEquals(false, view.get("truncated"));
    }

    @Test
    @DisplayName("the stored text is redacted again through the current filter when served")
    void reRedactedAtServeTime() throws Exception {
        String key = "sk-ant-" + "fixture".repeat(5);
        snapshot(ConversationState.AWAITING_HUMAN, context("posted with " + key + " and ${vault:openai}"));

        String body = summaryOf(restAgentEngine.getApprovalStatus(CONVERSATION_ID, null)).get("approvalContext").toString();

        assertFalse(body.contains(key), body);
        assertFalse(body.contains("openai"), body);
        assertTrue(body.contains("[secret]"), body);
    }

    @Test
    @DisplayName("not paused: null, even when a stale context is still stored")
    void nullWhenNotPaused() throws Exception {
        snapshot(ConversationState.READY, context("stale"));

        assertNull(summaryOf(restAgentEngine.getApprovalStatus(CONVERSATION_ID, null)).get("approvalContext"));
    }

    @Test
    @DisplayName("paused without a configured context: null")
    void nullWhenNoContext() throws Exception {
        snapshot(ConversationState.AWAITING_HUMAN, null);

        assertNull(summaryOf(restAgentEngine.getApprovalStatus(CONVERSATION_ID, null)).get("approvalContext"));
    }

    @Test
    @DisplayName("detail=full keeps the field, re-redacted")
    void fullViewKeepsItRedacted() throws Exception {
        String key = "sk-ant-" + "fixture".repeat(5);
        snapshot(ConversationState.AWAITING_HUMAN, context("posted with " + key));

        var body = (ConversationMemorySnapshot) restAgentEngine.getApprovalStatus(CONVERSATION_ID, "full").getEntity();

        assertNotNull(body.getHitlApprovalContext());
        assertFalse(body.getHitlApprovalContext().getItems().get(0).getText().contains(key));
    }
}
