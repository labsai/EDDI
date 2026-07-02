/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack;

import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService;
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
 * Unit tests for {@link SlackInteractivityHandler}: authorization
 * (fail-closed), decidedBy derivation, idempotency (double-click), and group
 * routing.
 */
class SlackInteractivityHandlerTest {

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

    private ChannelIntegrationConfiguration integrationWith(String approverIds) {
        var cfg = new ChannelIntegrationConfiguration();
        cfg.setChannelType("slack");
        var pc = new java.util.HashMap<String, String>();
        pc.put("channelId", "C_MAIN");
        pc.put("botToken", "xoxb-token");
        pc.put(SlackHitlSupport.CFG_HITL_APPROVAL_CHANNEL, "C_APPROVAL");
        if (approverIds != null) {
            pc.put(SlackHitlSupport.CFG_HITL_APPROVER_USER_IDS, approverIds);
        }
        cfg.setPlatformConfig(pc);
        return cfg;
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

    @Test
    void unauthorizedUser_cannotDecide() throws Exception {
        when(router.getIntegrationByApprovalChannel("slack", "C_APPROVAL"))
                .thenReturn(Optional.of(integrationWith("U_APPROVER")));

        handler.handlePayload(approvePayload("U_INTRUDER", "conv-1"));

        verify(conversationService, never()).resumeConversation(any(), any(), any());
        // Best-effort "not authorized" reply is posted, not a resume
        verify(slackApi).postMessage(anyString(), eq("C_APPROVAL"), isNull(), contains("not authorized"));
    }

    @Test
    void authorizedUser_resumesWithSlackDecidedBy() throws Exception {
        when(router.getIntegrationByApprovalChannel("slack", "C_APPROVAL"))
                .thenReturn(Optional.of(integrationWith("U_APPROVER")));

        handler.handlePayload(approvePayload("U_APPROVER", "conv-1"));

        ArgumentCaptor<HitlDecision> captor = ArgumentCaptor.forClass(HitlDecision.class);
        verify(conversationService).resumeConversation(eq("conv-1"), captor.capture(), isNull());
        HitlDecision decision = captor.getValue();
        assertEquals(HitlDecision.HitlVerdict.APPROVED, decision.getVerdict());
        assertEquals("slack:U_APPROVER", decision.getDecidedBy());
        assertTrue(decision.getNote().contains("U_APPROVER"));
        // Message finalized (buttons removed)
        verify(slackApi).updateMessage(anyString(), eq("C_APPROVAL"), eq("1700000000.000100"),
                contains("Approved"), any());
    }

    @Test
    void rejectVerdict_isPassedThrough() throws Exception {
        when(router.getIntegrationByApprovalChannel("slack", "C_APPROVAL"))
                .thenReturn(Optional.of(integrationWith("U_APPROVER")));

        handler.handlePayload(rejectPayload("U_APPROVER", "conv-1"));

        ArgumentCaptor<HitlDecision> captor = ArgumentCaptor.forClass(HitlDecision.class);
        verify(conversationService).resumeConversation(eq("conv-1"), captor.capture(), isNull());
        assertEquals(HitlDecision.HitlVerdict.REJECTED, captor.getValue().getVerdict());
    }

    @Test
    void doubleClick_alreadyResolved_noErrorSpam() throws Exception {
        when(router.getIntegrationByApprovalChannel("slack", "C_APPROVAL"))
                .thenReturn(Optional.of(integrationWith("U_APPROVER")));
        // Second click: resume throws IllegalStateException (already decided)
        doThrow(new IllegalStateException("not AWAITING_HUMAN"))
                .when(conversationService).resumeConversation(eq("conv-1"), any(), isNull());

        handler.handlePayload(approvePayload("U_APPROVER", "conv-1"));

        // Message updated to "already resolved", but NO error message posted
        verify(slackApi).updateMessage(anyString(), eq("C_APPROVAL"), anyString(),
                contains("already been resolved"), any());
        verify(slackApi, never()).postMessage(anyString(), anyString(), any(), contains("error"));
    }

    @Test
    void groupValue_routesToResumeDiscussion() throws Exception {
        when(router.getIntegrationByApprovalChannel("slack", "C_APPROVAL"))
                .thenReturn(Optional.of(integrationWith("U_APPROVER")));

        handler.handlePayload(approvePayload("U_APPROVER",
                SlackHitlSupport.GROUP_VALUE_PREFIX + "gc-7"));

        ArgumentCaptor<GroupApprovalRequest> captor = ArgumentCaptor.forClass(GroupApprovalRequest.class);
        verify(groupConversationService).resumeDiscussion(eq("gc-7"), captor.capture(), isNull());
        verify(conversationService, never()).resumeConversation(any(), any(), any());
        assertEquals("slack:U_APPROVER", captor.getValue().getDecision().getDecidedBy());
    }

    @Test
    void unknownApprovalChannel_ignored() throws Exception {
        when(router.getIntegrationByApprovalChannel("slack", "C_APPROVAL"))
                .thenReturn(Optional.empty());

        handler.handlePayload(approvePayload("U_APPROVER", "conv-1"));

        verify(conversationService, never()).resumeConversation(any(), any(), any());
        verifyNoInteractions(slackApi);
    }

    @Test
    void nonBlockActions_ignored() throws Exception {
        handler.handlePayload("{\"type\":\"view_submission\"}");
        verifyNoInteractions(conversationService, groupConversationService, slackApi);
        verify(router, never()).getIntegrationByApprovalChannel(any(), any());
    }
}
