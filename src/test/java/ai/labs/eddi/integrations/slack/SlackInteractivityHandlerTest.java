/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack;

import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionException;
import ai.labs.eddi.engine.internal.GroupApprovalRequest;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.integrations.channels.ChannelTargetRouter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SlackInteractivityHandler}: integration binding via the
 * button value (IDOR-safe), signing-secret resolution, authorization
 * (fail-closed), decidedBy derivation, idempotency (single + group
 * double-click), and group routing.
 */
class SlackInteractivityHandlerTest {

    private static final String INT_NAME = "acme-int";

    private ChannelTargetRouter router;
    private IConversationService conversationService;
    private IGroupConversationService groupConversationService;
    private SlackWebApiClient slackApi;
    private SlackInteractivityHandler handler;

    @BeforeEach
    void setUp() {
        router = mock(ChannelTargetRouter.class);
        conversationService = mock(IConversationService.class);
        groupConversationService = mock(IGroupConversationService.class);
        slackApi = mock(SlackWebApiClient.class);
        handler = new SlackInteractivityHandler(router, conversationService,
                groupConversationService, slackApi, new ObjectMapper());
    }

    private ChannelIntegrationConfiguration integrationWith(String name, String approverIds, String signingSecret) {
        var cfg = new ChannelIntegrationConfiguration();
        cfg.setName(name);
        cfg.setChannelType("slack");
        var pc = new java.util.HashMap<String, String>();
        pc.put("channelId", "C_MAIN");
        pc.put("botToken", "xoxb-token");
        pc.put("signingSecret", signingSecret);
        pc.put(SlackHitlSupport.CFG_HITL_APPROVAL_CHANNEL, "C_APPROVAL");
        if (approverIds != null) {
            pc.put(SlackHitlSupport.CFG_HITL_APPROVER_USER_IDS, approverIds);
        }
        cfg.setPlatformConfig(pc);
        return cfg;
    }

    /** Value carries the owning integration name: {@code <name>|<subject>}. */
    private String value(String subject) {
        return SlackHitlSupport.buildActionValue(INT_NAME, subject);
    }

    private String approvePayload(String slackUserId, String value) {
        return """
                {"type":"block_actions",
                 "user":{"id":"%s"},
                 "channel":{"id":"C_APPROVAL"},
                 "message":{"ts":"1700000000.000100"},
                 "actions":[{"action_id":"hitl_approve","value":"%s"}]}
                """.formatted(slackUserId, value);
    }

    private String rejectPayload(String slackUserId, String value) {
        return """
                {"type":"block_actions",
                 "user":{"id":"%s"},
                 "channel":{"id":"C_APPROVAL"},
                 "message":{"ts":"1700000000.000100"},
                 "actions":[{"action_id":"hitl_reject","value":"%s"}]}
                """.formatted(slackUserId, value);
    }

    private void bindIntegration(ChannelIntegrationConfiguration cfg) {
        when(router.getIntegrationByName("slack", INT_NAME)).thenReturn(Optional.of(cfg));
    }

    // ─── Signing-secret resolution (H1 binding) ───

    @Test
    void resolveSigningSecretForDecision_returnsOwningSecret() {
        bindIntegration(integrationWith(INT_NAME, "U_APPROVER", "owning-secret"));
        assertEquals("owning-secret",
                handler.resolveSigningSecretForDecision(approvePayload("U_APPROVER", value("conv-1"))));
    }

    @Test
    void resolveSigningSecretForDecision_legacyBareValue_isUnbindable() {
        // No integration name in the value → cannot bind → null (endpoint rejects).
        // With no by-approval-channel integration configured either, this is null.
        assertNull(handler.resolveSigningSecretForDecision(approvePayload("U_APPROVER", "conv-1")));
    }

    @Test
    void resolveSigningSecretForDecision_bareValue_neverFallsBackToApprovalChannel() {
        // Proves the removed fallback: even with a by-approval-channel integration
        // CONFIGURED (which the old code would have bound the decision to), a bare
        // value with no integration name resolves to null/unbindable and the
        // channel lookup is NEVER consulted — closing the cross-integration
        // ambiguity in a shared approval channel.
        var byChannel = integrationWith("channel-owner", "U_APPROVER", "channel-secret");
        when(router.getIntegrationByApprovalChannel(any(), any())).thenReturn(Optional.of(byChannel));

        assertNull(handler.resolveSigningSecretForDecision(approvePayload("U_APPROVER", "conv-1")),
                "a bare value must remain unbindable, not be routed by approval channel");

        verify(router, never()).getIntegrationByApprovalChannel(any(), any());
        verify(router, never()).getIntegrationByName(any(), any());
    }

    @Test
    void resolveSigningSecretForDecision_unknownIntegration_returnsNull() {
        when(router.getIntegrationByName("slack", INT_NAME)).thenReturn(Optional.empty());
        assertNull(handler.resolveSigningSecretForDecision(approvePayload("U_APPROVER", value("conv-1"))));
    }

    @Test
    void resolveSigningSecretForDecision_nonBlockActions_returnsNull() {
        assertNull(handler.resolveSigningSecretForDecision("{\"type\":\"view_submission\"}"));
    }

    // ─── Authorization ───

    @Test
    void unauthorizedUser_cannotDecide() throws Exception {
        bindIntegration(integrationWith(INT_NAME, "U_APPROVER", "s"));

        handler.handlePayload(approvePayload("U_INTRUDER", value("conv-1")));

        verify(conversationService, never()).resumeConversation(any(), any(), any());
        verify(slackApi).postMessage(anyString(), eq("C_APPROVAL"), isNull(), contains("not authorized"));
    }

    @Test
    void authorizedUser_resumesWithSlackDecidedBy() throws Exception {
        bindIntegration(integrationWith(INT_NAME, "U_APPROVER", "s"));

        handler.handlePayload(approvePayload("U_APPROVER", value("conv-1")));

        ArgumentCaptor<HitlDecision> captor = ArgumentCaptor.forClass(HitlDecision.class);
        verify(conversationService).resumeConversation(eq("conv-1"), captor.capture(), isNull());
        HitlDecision decision = captor.getValue();
        assertEquals(HitlDecision.HitlVerdict.APPROVED, decision.getVerdict());
        assertEquals("slack:U_APPROVER", decision.getDecidedBy());
        assertTrue(decision.getNote().contains("U_APPROVER"));
        verify(slackApi).updateMessage(anyString(), eq("C_APPROVAL"), eq("1700000000.000100"),
                contains("Approved"), any());
    }

    @Test
    void rejectVerdict_isPassedThrough() throws Exception {
        bindIntegration(integrationWith(INT_NAME, "U_APPROVER", "s"));

        handler.handlePayload(rejectPayload("U_APPROVER", value("conv-1")));

        ArgumentCaptor<HitlDecision> captor = ArgumentCaptor.forClass(HitlDecision.class);
        verify(conversationService).resumeConversation(eq("conv-1"), captor.capture(), isNull());
        assertEquals(HitlDecision.HitlVerdict.REJECTED, captor.getValue().getVerdict());
    }

    // ─── H1: cross-integration IDOR — decision binds to the value's integration
    // ───

    @Test
    void decisionBindsToValueIntegration_notApprovalChannel() throws Exception {
        // The button value names integration B; the by-approval-channel lookup would
        // return integration A. The handler MUST authorize against B (by name).
        var integrationB = integrationWith(INT_NAME, "U_APPROVER", "s");
        bindIntegration(integrationB);
        // A different integration owns the same approval channel — must be ignored.
        var integrationA = integrationWith("other-int", "U_OTHER_ONLY", "s2");
        when(router.getIntegrationByApprovalChannel(any(), any())).thenReturn(Optional.of(integrationA));

        handler.handlePayload(approvePayload("U_APPROVER", value("conv-1")));

        // Authorized by B's approver list → resume proceeds.
        verify(conversationService).resumeConversation(eq("conv-1"), any(), isNull());
        // The channel-lookup integration A was never consulted for authz.
        verify(router, never()).getIntegrationByApprovalChannel(any(), any());
    }

    @Test
    void unknownIntegrationName_ignored() throws Exception {
        when(router.getIntegrationByName("slack", INT_NAME)).thenReturn(Optional.empty());

        handler.handlePayload(approvePayload("U_APPROVER", value("conv-1")));

        verify(conversationService, never()).resumeConversation(any(), any(), any());
        verifyNoInteractions(slackApi);
    }

    // ─── Idempotency: single-conversation double-click ───

    @Test
    void doubleClick_alreadyResolved_noErrorSpam() throws Exception {
        bindIntegration(integrationWith(INT_NAME, "U_APPROVER", "s"));
        doThrow(new IllegalStateException("not AWAITING_HUMAN"))
                .when(conversationService).resumeConversation(eq("conv-1"), any(), isNull());

        handler.handlePayload(approvePayload("U_APPROVER", value("conv-1")));

        verify(slackApi).updateMessage(anyString(), eq("C_APPROVAL"), anyString(),
                contains("already been resolved"), any());
        verify(slackApi, never()).postMessage(anyString(), anyString(), any(), contains("error"));
    }

    // ─── Group routing + idempotency (H3) ───

    @Test
    void groupValue_routesToResumeDiscussion() throws Exception {
        bindIntegration(integrationWith(INT_NAME, "U_APPROVER", "s"));

        handler.handlePayload(approvePayload("U_APPROVER",
                value(SlackHitlSupport.GROUP_VALUE_PREFIX + "gc-7")));

        ArgumentCaptor<GroupApprovalRequest> captor = ArgumentCaptor.forClass(GroupApprovalRequest.class);
        verify(groupConversationService).resumeDiscussion(eq("gc-7"), captor.capture(), isNull());
        verify(conversationService, never()).resumeConversation(any(), any(), any());
        assertEquals("slack:U_APPROVER", captor.getValue().getDecision().getDecidedBy());
    }

    @Test
    void groupDoubleClick_notAwaitingApproval_noErrorSpam() throws Exception {
        bindIntegration(integrationWith(INT_NAME, "U_APPROVER", "s"));
        // resumeDiscussion signals a non-paused group with a CHECKED
        // GroupDiscussionException — it must be treated as already-resolved, not a
        // generic failure (no warn-spam, no live buttons left behind).
        doThrow(new GroupDiscussionException("Group conversation is not awaiting approval"))
                .when(groupConversationService).resumeDiscussion(eq("gc-7"), any(), isNull());

        handler.handlePayload(approvePayload("U_APPROVER",
                value(SlackHitlSupport.GROUP_VALUE_PREFIX + "gc-7")));

        verify(slackApi).updateMessage(anyString(), eq("C_APPROVAL"), anyString(),
                contains("already been resolved"), any());
        verify(slackApi, never()).postMessage(anyString(), anyString(), any(), contains("error"));
    }

    @Test
    void groupDoubleClick_casLost_noErrorSpam() throws Exception {
        bindIntegration(integrationWith(INT_NAME, "U_APPROVER", "s"));
        doThrow(new IResourceStore.ResourceModifiedException("CAS lost"))
                .when(groupConversationService).resumeDiscussion(eq("gc-7"), any(), isNull());

        handler.handlePayload(approvePayload("U_APPROVER",
                value(SlackHitlSupport.GROUP_VALUE_PREFIX + "gc-7")));

        verify(slackApi).updateMessage(anyString(), eq("C_APPROVAL"), anyString(),
                contains("already been resolved"), any());
    }

    // ─── Ignored payloads ───

    @Test
    void nonBlockActions_ignored() throws Exception {
        handler.handlePayload("{\"type\":\"view_submission\"}");
        verifyNoInteractions(conversationService, groupConversationService, slackApi);
        verify(router, never()).getIntegrationByName(any(), any());
    }
}
