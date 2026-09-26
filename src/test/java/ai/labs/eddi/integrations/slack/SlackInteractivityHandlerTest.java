/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack;

import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.ChannelTarget;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionException;
import ai.labs.eddi.engine.internal.GroupApprovalRequest;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.triggermanagement.IUserConversationStore;
import ai.labs.eddi.engine.triggermanagement.model.UserConversation;
import ai.labs.eddi.integrations.channels.ChannelTargetRouter;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
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
    private static final String INT_RES = "int-res-1";

    private ChannelTargetRouter router;
    private IConversationService conversationService;
    private IGroupConversationService groupConversationService;
    private SlackWebApiClient slackApi;
    private IUserConversationStore userConversationStore;
    private SlackInteractivityHandler handler;

    private static final Instant PAUSED_AT = Instant.ofEpochMilli(1_700_000_000_123L);
    private static final String PAUSE_ID = HitlDecision.pauseIdOf(PAUSED_AT);

    @BeforeEach
    void setUp() throws Exception {
        router = mock(ChannelTargetRouter.class);
        conversationService = mock(IConversationService.class);
        groupConversationService = mock(IGroupConversationService.class);
        slackApi = mock(SlackWebApiClient.class);
        userConversationStore = mock(IUserConversationStore.class);
        handler = new SlackInteractivityHandler(router, conversationService,
                groupConversationService, slackApi, userConversationStore, new ObjectMapper());
        // By default every targeted conversation / discussion belongs to INT_NAME and
        // is in the pause the cards were posted for; individual tests override.
        when(conversationService.getConversationMemorySnapshot(anyString()))
                .thenReturn(conversationSnapshot("agent-1", Map.of("channelIntegrationId", INT_RES), PAUSED_AT));
        when(groupConversationService.readGroupConversation(anyString()))
                .thenAnswer(inv -> groupConversation(inv.getArgument(0), "group-1", PAUSED_AT));
        // ...and every discussion was started through INT_NAME (the origin mapping).
        when(userConversationStore.readUserConversation(startsWith(SlackEventHandler.GROUP_ORIGIN_INTENT_PREFIX),
                eq(SlackEventHandler.GROUP_ORIGIN_USER_PREFIX + INT_RES)))
                .thenAnswer(inv -> {
                    String gcId = ((String) inv.getArgument(0)).substring(SlackEventHandler.GROUP_ORIGIN_INTENT_PREFIX.length());
                    return new UserConversation(inv.getArgument(0), inv.getArgument(1), Deployment.Environment.production,
                            "group-1", gcId);
                });
    }

    private ChannelIntegrationConfiguration integrationWith(String name, String approverIds, String signingSecret) {
        var cfg = new ChannelIntegrationConfiguration();
        cfg.setName(name);
        cfg.setChannelType("slack");
        var pc = new HashMap<String, String>();
        pc.put("channelId", "C_MAIN");
        pc.put("botToken", "xoxb-token");
        pc.put("signingSecret", signingSecret);
        pc.put(SlackHitlSupport.CFG_HITL_APPROVAL_CHANNEL, "C_APPROVAL");
        if (approverIds != null) {
            pc.put(SlackHitlSupport.CFG_HITL_APPROVER_USER_IDS, approverIds);
        }
        cfg.setPlatformConfig(pc);
        cfg.setResourceId(INT_RES);
        var agent = new ChannelTarget();
        agent.setName("agent");
        agent.setType(ChannelTarget.TargetType.AGENT);
        agent.setTargetId("agent-1");
        var group = new ChannelTarget();
        group.setName("group");
        group.setType(ChannelTarget.TargetType.GROUP);
        group.setTargetId("group-1");
        cfg.setTargets(List.of(agent, group));
        return cfg;
    }

    private static ConversationMemorySnapshot conversationSnapshot(String agentId, Map<String, Object> startContext,
                                                                   Instant pausedAt) {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setAgentId(agentId);
        snapshot.setHitlPausedAt(pausedAt);
        var output = new ConversationOutput();
        output.put("context", new HashMap<>(startContext));
        snapshot.setConversationOutputs(List.of(output));
        return snapshot;
    }

    private static GroupConversation groupConversation(String gcId, String groupId, Instant pausedAt) {
        var gc = new GroupConversation();
        gc.setId(gcId);
        gc.setGroupId(groupId);
        gc.setPausedAt(pausedAt);
        return gc;
    }

    /**
     * Value carries the owning integration name and the pause id:
     * {@code <name>|<subject>|<pauseId>}.
     */
    private String value(String subject) {
        return SlackHitlSupport.buildActionValue(INT_NAME, subject, PAUSE_ID);
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

    // ─── H4c: the targeted conversation must belong to the integration ───

    @Test
    void conversationStartedByAnotherIntegration_isRefused() throws Exception {
        // An editor can create an integration with their own secret and approver
        // list. Resuming whichever conversation id their card names would let them
        // decide every paused conversation in the deployment.
        bindIntegration(integrationWith(INT_NAME, "U_APPROVER", "s"));
        when(conversationService.getConversationMemorySnapshot("victim"))
                .thenReturn(conversationSnapshot("agent-1", Map.of("channelIntegrationId", "res-someone-else"), PAUSED_AT));

        handler.handlePayload(approvePayload("U_APPROVER", value("victim")));

        verify(conversationService, never()).resumeConversation(any(), any(), any());
        verify(slackApi).postMessage(anyString(), eq("C_APPROVAL"), isNull(), contains("not authorized"));
    }

    @Test
    void conversationOfAnAgentThatIsNotATarget_isRefused() throws Exception {
        bindIntegration(integrationWith(INT_NAME, "U_APPROVER", "s"));
        when(conversationService.getConversationMemorySnapshot("rest-conv"))
                .thenReturn(conversationSnapshot("agent-elsewhere", Map.of("channelIntegrationId", INT_RES), PAUSED_AT));

        handler.handlePayload(approvePayload("U_APPROVER", value("rest-conv")));

        verify(conversationService, never()).resumeConversation(any(), any(), any());
    }

    @Test
    void conversationSlackNeverStarted_isRefused() throws Exception {
        // No channel context at all — a REST/MCP conversation of the same agent.
        bindIntegration(integrationWith(INT_NAME, "U_APPROVER", "s"));
        when(conversationService.getConversationMemorySnapshot("rest-conv"))
                .thenReturn(conversationSnapshot("agent-1", Map.of(), PAUSED_AT));

        handler.handlePayload(approvePayload("U_APPROVER", value("rest-conv")));

        verify(conversationService, never()).resumeConversation(any(), any(), any());
    }

    @Test
    void preUpgradeConversation_withoutIntegrationId_isRefused() throws Exception {
        // #4: only the resource id binds. A channelIntent naming the integration's
        // channel is not enough — that channel id can be freed and claimed by a new
        // integration, which would then inherit the old paused conversations.
        bindIntegration(integrationWith(INT_NAME, "U_APPROVER", "s"));
        when(conversationService.getConversationMemorySnapshot("old-conv")).thenReturn(conversationSnapshot("agent-1",
                Map.of("channelIntent", "channel:slack:C_MAIN:agent-1:1700.1"), PAUSED_AT));

        handler.handlePayload(approvePayload("U_APPROVER", value("old-conv")));

        verify(conversationService, never()).resumeConversation(any(), any(), any());
    }

    @Test
    void sameNameDifferentIntegration_isRefused() throws Exception {
        // #4: integration renamed/deleted, a new one created under the old name.
        var impostor = integrationWith(INT_NAME, "U_APPROVER", "s");
        impostor.setResourceId("int-res-new");
        bindIntegration(impostor);

        handler.handlePayload(approvePayload("U_APPROVER", value("conv-1")));

        verify(conversationService, never()).resumeConversation(any(), any(), any());
    }

    @Test
    void groupStartedElsewhere_isRefused() throws Exception {
        // #3: the group is one of the integration's targets, but this discussion was
        // not started through it (REST, another integration, another user).
        bindIntegration(integrationWith(INT_NAME, "U_APPROVER", "s"));
        when(userConversationStore.readUserConversation(eq(SlackEventHandler.GROUP_ORIGIN_INTENT_PREFIX + "gc-rest"), anyString()))
                .thenReturn(null);

        handler.handlePayload(approvePayload("U_APPROVER", value(SlackHitlSupport.GROUP_VALUE_PREFIX + "gc-rest")));

        verify(groupConversationService, never()).resumeDiscussion(any(), any(), any());
        verify(slackApi).postMessage(anyString(), eq("C_APPROVAL"), isNull(), contains("not authorized"));
    }

    @Test
    void approverFromAnotherTeam_isRefusedWhenTheWorkspaceIsPinned() throws Exception {
        // #5: Slack ids are unique per team; a Slack Connect user whose id collides
        // with a listed approver must not pass.
        var cfg = integrationWith(INT_NAME, "U_APPROVER", "s");
        var pc = cfg.getPlatformConfig();
        pc.put(ChannelTargetRouter.CFG_TEAM_ID, "T_OWN");
        cfg.setPlatformConfig(pc);
        bindIntegration(cfg);

        handler.handlePayload(approvePayloadFromTeam("U_APPROVER", "T_OTHER", "T_OWN", value("conv-1")));
        verify(conversationService, never()).resumeConversation(any(), any(), any());

        handler.handlePayload(approvePayloadFromTeam("U_APPROVER", "T_OWN", "T_OWN", value("conv-1")));
        verify(conversationService).resumeConversation(eq("conv-1"), any(), isNull());
    }

    @Test
    void teamScopedApproverEntry_matchesOnlyThatTeam() throws Exception {
        bindIntegration(integrationWith(INT_NAME, "T_OWN:U_APPROVER", "s"));

        handler.handlePayload(approvePayloadFromTeam("U_APPROVER", "T_OTHER", null, value("conv-1")));
        verify(conversationService, never()).resumeConversation(any(), any(), any());

        handler.handlePayload(approvePayloadFromTeam("U_APPROVER", "T_OWN", null, value("conv-1")));
        verify(conversationService).resumeConversation(eq("conv-1"), any(), isNull());
    }

    private String approvePayloadFromTeam(String slackUserId, String userTeam, String team, String value) {
        return """
                {"type":"block_actions",%s
                 "user":{"id":"%s","team_id":"%s"},
                 "channel":{"id":"C_APPROVAL"},
                 "message":{"ts":"1700000000.000100"},
                 "actions":[{"action_id":"hitl_approve","value":"%s"}]}
                """.formatted(team != null ? "\"team\":{\"id\":\"" + team + "\"}," : "", slackUserId, userTeam, value);
    }

    @Test
    void groupWhoseGroupIsNotATarget_isRefused() throws Exception {
        bindIntegration(integrationWith(INT_NAME, "U_APPROVER", "s"));
        when(groupConversationService.readGroupConversation("gc-x")).thenReturn(groupConversation("gc-x", "group-other", PAUSED_AT));

        handler.handlePayload(approvePayload("U_APPROVER", value(SlackHitlSupport.GROUP_VALUE_PREFIX + "gc-x")));

        verify(groupConversationService, never()).resumeDiscussion(any(), any(), any());
    }

    // ─── H4b: a card is bound to its pause ───

    @Test
    void decisionCarriesThePauseIdToTheResume() throws Exception {
        bindIntegration(integrationWith(INT_NAME, "U_APPROVER", "s"));

        handler.handlePayload(approvePayload("U_APPROVER", value("conv-1")));

        ArgumentCaptor<HitlDecision> captor = ArgumentCaptor.forClass(HitlDecision.class);
        verify(conversationService).resumeConversation(eq("conv-1"), captor.capture(), isNull());
        // The engine re-checks it under the CAS — the authoritative check.
        assertEquals(PAUSE_ID, captor.getValue().getPauseId());
    }

    @Test
    void staleCard_forAnEarlierPause_doesNotApproveTheCurrentOne() throws Exception {
        bindIntegration(integrationWith(INT_NAME, "U_APPROVER", "s"));
        when(conversationService.getConversationMemorySnapshot("conv-1")).thenReturn(conversationSnapshot("agent-1",
                Map.of("channelIntegrationId", INT_RES), PAUSED_AT.plusSeconds(60)));

        handler.handlePayload(approvePayload("U_APPROVER", value("conv-1")));

        verify(conversationService, never()).resumeConversation(any(), any(), any());
        verify(slackApi).updateMessage(anyString(), eq("C_APPROVAL"), anyString(), contains("out of date"), any());
    }

    @Test
    void engineRefusal_ofAChangedPause_marksTheCardOutOfDate() throws Exception {
        bindIntegration(integrationWith(INT_NAME, "U_APPROVER", "s"));
        doThrow(new IConversationService.PauseMismatchException("changed"))
                .when(conversationService).resumeConversation(eq("conv-1"), any(), isNull());

        handler.handlePayload(approvePayload("U_APPROVER", value("conv-1")));

        verify(slackApi).updateMessage(anyString(), eq("C_APPROVAL"), anyString(), contains("out of date"), any());
    }

    @Test
    void cardWithoutPauseId_isRefused() throws Exception {
        bindIntegration(integrationWith(INT_NAME, "U_APPROVER", "s"));

        handler.handlePayload(approvePayload("U_APPROVER", SlackHitlSupport.buildActionValue(INT_NAME, "conv-1")));

        verify(conversationService, never()).resumeConversation(any(), any(), any());
        verify(slackApi).updateMessage(anyString(), eq("C_APPROVAL"), anyString(), contains("out of date"), any());
    }

    @Test
    void staleGroupCard_isRefused() throws Exception {
        bindIntegration(integrationWith(INT_NAME, "U_APPROVER", "s"));
        when(groupConversationService.readGroupConversation("gc-7")).thenReturn(groupConversation("gc-7", "group-1",
                PAUSED_AT.plusSeconds(5)));

        handler.handlePayload(approvePayload("U_APPROVER", value(SlackHitlSupport.GROUP_VALUE_PREFIX + "gc-7")));

        verify(groupConversationService, never()).resumeDiscussion(any(), any(), any());
    }

    // ─── Pinned workspace / app ───

    @Test
    void pinnedTeamId_mismatch_isIgnored() throws Exception {
        var cfg = integrationWith(INT_NAME, "U_APPROVER", "s");
        var pc = cfg.getPlatformConfig();
        pc.put(ChannelTargetRouter.CFG_TEAM_ID, "T_OWN");
        cfg.setPlatformConfig(pc);
        bindIntegration(cfg);
        String payload = """
                {"type":"block_actions","team":{"id":"T_FOREIGN"},
                 "user":{"id":"U_APPROVER"},"channel":{"id":"C_APPROVAL"},"message":{"ts":"1.2"},
                 "actions":[{"action_id":"hitl_approve","value":"%s"}]}
                """.formatted(value("conv-1"));

        handler.handlePayload(payload);

        verify(conversationService, never()).resumeConversation(any(), any(), any());
    }

    // ─── Ignored payloads ───

    @Test
    void nonBlockActions_ignored() throws Exception {
        handler.handlePayload("{\"type\":\"view_submission\"}");
        verifyNoInteractions(conversationService, groupConversationService, slackApi);
        verify(router, never()).getIntegrationByName(any(), any());
    }
}
