/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack;

import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionException;
import ai.labs.eddi.engine.internal.GroupApprovalRequest;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.integrations.channels.ChannelTargetRouter;
import ai.labs.eddi.integrations.slack.hitl.ISlackApprovalRecordStore;
import ai.labs.eddi.integrations.slack.hitl.ISlackApprovalRecordStore.SlackApprovalRecord;
import ai.labs.eddi.integrations.slack.hitl.InMemorySlackApprovalRecordStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
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
    private static final Instant PAUSED_AT = Instant.parse("2026-09-01T10:00:00Z");
    private static final String GROUP_SUBJECT = SlackHitlSupport.GROUP_VALUE_PREFIX + "gc-7";

    private ChannelTargetRouter router;
    private IConversationService conversationService;
    private IGroupConversationService groupConversationService;
    private SlackWebApiClient slackApi;
    private InMemorySlackApprovalRecordStore approvalRecords;
    /** Card id of the latest card each {@code integration|subject} posted. */
    private final Map<String, String> cardIds = new HashMap<>();
    private SlackInteractivityHandler handler;

    @BeforeEach
    void setUp() {
        router = mock(ChannelTargetRouter.class);
        conversationService = mock(IConversationService.class);
        groupConversationService = mock(IGroupConversationService.class);
        slackApi = mock(SlackWebApiClient.class);
        approvalRecords = new InMemorySlackApprovalRecordStore();
        handler = new SlackInteractivityHandler(router, conversationService,
                groupConversationService, slackApi, new ObjectMapper(), approvalRecords);
        // Default fixture: both subjects are paused, and INT_NAME posted a card for
        // exactly that pause — the state a genuine button click arrives in.
        notified(INT_NAME, "conv-1", PAUSED_AT);
        notified(INT_NAME, GROUP_SUBJECT, PAUSED_AT);
        conversationPausedAt("conv-1", PAUSED_AT);
        groupPausedAt("gc-7", PAUSED_AT);
    }

    /** Record a card as the posting path does; returns the card's id. */
    private String notified(String integrationName, String subject, Instant pausedAt) {
        String cardId = ISlackApprovalRecordStore.newCardId();
        approvalRecords.tryRecord(integrationName, subject, ISlackApprovalRecordStore.pauseEpochOf(pausedAt), cardId,
                "C_APPROVAL");
        cardIds.put(integrationName + "|" + subject, cardId);
        return cardId;
    }

    private void conversationPausedAt(String conversationId, Instant pausedAt) {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setConversationState(ConversationState.AWAITING_HUMAN);
        snapshot.setHitlPausedAt(pausedAt);
        try {
            when(conversationService.getConversationMemorySnapshot(conversationId)).thenReturn(snapshot);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private void groupPausedAt(String groupConversationId, Instant pausedAt) {
        var gc = new GroupConversation();
        gc.setState(GroupConversationState.AWAITING_APPROVAL);
        gc.setPausedAt(pausedAt);
        try {
            when(groupConversationService.readGroupConversation(groupConversationId)).thenReturn(gc);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
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
        return cfg;
    }

    /**
     * Value of the latest card INT_NAME posted for {@code subject}:
     * {@code <name>|<subject>|<cardId>}. A subject INT_NAME never posted a card for
     * gets a card id no record holds.
     */
    private String value(String subject) {
        return value(subject, cardIds.getOrDefault(INT_NAME + "|" + subject, ISlackApprovalRecordStore.newCardId()));
    }

    private String value(String subject, String cardId) {
        return SlackHitlSupport.buildActionValue(INT_NAME, subject, cardId);
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

    // ─── The decision names the pause its card was checked against ───

    @Test
    void conversationDecision_carriesThePauseIdTheCardWasCheckedAgainst() throws Exception {
        // The handler reads the pause, matches the card to it, then resumes. The
        // resume must be bound to THAT pause, so a resume and re-pause landing in
        // between is refused by the engine under its CAS instead of approving the
        // newer request with this card.
        bindIntegration(integrationWith(INT_NAME, "U_APPROVER", "s"));

        handler.handlePayload(approvePayload("U_APPROVER", value("conv-1")));

        ArgumentCaptor<HitlDecision> captor = ArgumentCaptor.forClass(HitlDecision.class);
        verify(conversationService).resumeConversation(eq("conv-1"), captor.capture(), isNull());
        assertEquals(HitlDecision.pauseIdOf(PAUSED_AT), captor.getValue().getPauseId());
    }

    @Test
    void groupDecision_carriesThePauseIdTheCardWasCheckedAgainst() throws Exception {
        bindIntegration(integrationWith(INT_NAME, "U_APPROVER", "s"));

        handler.handlePayload(approvePayload("U_APPROVER",
                value(SlackHitlSupport.GROUP_VALUE_PREFIX + "gc-7")));

        ArgumentCaptor<GroupApprovalRequest> captor = ArgumentCaptor.forClass(GroupApprovalRequest.class);
        verify(groupConversationService).resumeDiscussion(eq("gc-7"), captor.capture(), isNull());
        assertEquals(HitlDecision.pauseIdOf(PAUSED_AT), captor.getValue().getDecision().getPauseId());
    }

    @Test
    void pauseChangedBeforeTheResume_isMarkedResolved() throws Exception {
        // The engine refuses the decision because the pause changed after the
        // handler's check: the card is stale, so it reads as resolved, not as an error.
        bindIntegration(integrationWith(INT_NAME, "U_APPROVER", "s"));
        doThrow(new IConversationService.PauseMismatchException("pause changed"))
                .when(conversationService).resumeConversation(eq("conv-1"), any(), isNull());

        handler.handlePayload(approvePayload("U_APPROVER", value("conv-1")));

        verify(slackApi).updateMessage(anyString(), eq("C_APPROVAL"), anyString(),
                contains("already been resolved"), any());
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
    void ambiguousIntegrationName_isRefused() throws Exception {
        // Finding C: two integrations share the display name INT_NAME, so
        // getIntegrationByName refuses (empty). A decision naming that name can then
        // be neither authenticated (no owning secret) nor resumed — an attacker who
        // copies the victim integration's name cannot approve the victim's pause.
        when(router.getIntegrationByName("slack", INT_NAME)).thenReturn(Optional.empty());

        assertNull(handler.resolveSigningSecretForDecision(approvePayload("U_APPROVER", value("conv-1"))),
                "an ambiguous integration name must not resolve to a signing secret");
        handler.handlePayload(approvePayload("U_APPROVER", value("conv-1")));
        verify(conversationService, never()).resumeConversation(any(), any(), any());
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

    // ─── Subject binding: the decision must name a subject THIS integration
    // posted a card for, for the pause it is in now ───

    @Test
    void subjectNeverNotified_isRefused() throws Exception {
        bindIntegration(integrationWith(INT_NAME, "U_APPROVER", "s"));
        conversationPausedAt("conv-foreign", PAUSED_AT);

        // A signed, authorized click whose value names a paused conversation this
        // integration never posted a card for — e.g. an edited button value.
        handler.handlePayload(approvePayload("U_APPROVER", value("conv-foreign")));

        verify(conversationService, never()).resumeConversation(any(), any(), any());
        verify(slackApi, never()).updateMessage(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void subjectNotifiedByAnotherIntegration_isRefused() throws Exception {
        bindIntegration(integrationWith(INT_NAME, "U_APPROVER", "s"));
        notified("other-int", "conv-other", PAUSED_AT);
        conversationPausedAt("conv-other", PAUSED_AT);

        handler.handlePayload(approvePayload("U_APPROVER", value("conv-other")));

        verify(conversationService, never()).resumeConversation(any(), any(), any());
    }

    @Test
    void cardFromEarlierPause_doesNotResolveTheCurrentOne() throws Exception {
        bindIntegration(integrationWith(INT_NAME, "U_APPROVER", "s"));
        // conv-1 was resumed and paused again: the old card must not approve the
        // new pause.
        conversationPausedAt("conv-1", PAUSED_AT.plusSeconds(60));

        handler.handlePayload(approvePayload("U_APPROVER", value("conv-1")));

        verify(conversationService, never()).resumeConversation(any(), any(), any());
        verify(slackApi).updateMessage(anyString(), eq("C_APPROVAL"), anyString(),
                contains("already been resolved"), any());
    }

    @Test
    void subjectNoLongerPaused_isMarkedResolvedWithoutResuming() throws Exception {
        bindIntegration(integrationWith(INT_NAME, "U_APPROVER", "s"));
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setConversationState(ConversationState.READY);
        when(conversationService.getConversationMemorySnapshot("conv-1")).thenReturn(snapshot);

        handler.handlePayload(approvePayload("U_APPROVER", value("conv-1")));

        verify(conversationService, never()).resumeConversation(any(), any(), any());
        verify(slackApi).updateMessage(anyString(), eq("C_APPROVAL"), anyString(),
                contains("already been resolved"), any());
    }

    @Test
    void unknownPauseRecord_matchesOnlyAPauseThatBeganBeforeIt() throws Exception {
        bindIntegration(integrationWith(INT_NAME, "U_APPROVER", "s"));
        Instant now = Instant.now();
        // Card posted before the bookmark was readable: no pause epoch, written now.
        approvalRecords.put(new SlackApprovalRecord(INT_NAME, "conv-2", ISlackApprovalRecordStore.UNKNOWN_PAUSE,
                "card-2", "C_APPROVAL", now, now.plus(Duration.ofDays(1))));

        // A pause that began after the card was written cannot be the one it was for.
        conversationPausedAt("conv-2", now.plusSeconds(30));
        handler.handlePayload(approvePayload("U_APPROVER", value("conv-2", "card-2")));
        verify(conversationService, never()).resumeConversation(eq("conv-2"), any(), any());

        // The pause that was already running when the card was posted is.
        conversationPausedAt("conv-2", now.minusMillis(200));
        handler.handlePayload(approvePayload("U_APPROVER", value("conv-2", "card-2")));
        verify(conversationService).resumeConversation(eq("conv-2"), any(), isNull());
    }

    @Test
    void expiredRecord_isRefused() throws Exception {
        bindIntegration(integrationWith(INT_NAME, "U_APPROVER", "s"));
        Instant longAgo = PAUSED_AT.minus(Duration.ofDays(60));
        approvalRecords.put(new SlackApprovalRecord(INT_NAME, "conv-3", ISlackApprovalRecordStore.pauseEpochOf(longAgo),
                "card-3", "C_APPROVAL", longAgo, longAgo.plus(Duration.ofDays(30))));
        conversationPausedAt("conv-3", longAgo);

        handler.handlePayload(approvePayload("U_APPROVER", value("conv-3", "card-3")));

        verify(conversationService, never()).resumeConversation(any(), any(), any());
    }

    @Test
    void recordStoreFailure_failsClosed() throws Exception {
        var failing = mock(ISlackApprovalRecordStore.class);
        when(failing.findBySubject(any(), any())).thenThrow(new IllegalStateException("db down"));
        var failingHandler = new SlackInteractivityHandler(router, conversationService,
                groupConversationService, slackApi, new ObjectMapper(), failing);
        bindIntegration(integrationWith(INT_NAME, "U_APPROVER", "s"));

        failingHandler.handlePayload(approvePayload("U_APPROVER", value("conv-1")));

        verify(conversationService, never()).resumeConversation(any(), any(), any());
        // The card is left intact so the approver can retry.
        verify(slackApi, never()).updateMessage(anyString(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void groupNeverNotified_isRefused() throws Exception {
        bindIntegration(integrationWith(INT_NAME, "U_APPROVER", "s"));
        groupPausedAt("gc-foreign", PAUSED_AT);

        handler.handlePayload(approvePayload("U_APPROVER",
                value(SlackHitlSupport.GROUP_VALUE_PREFIX + "gc-foreign")));

        verify(groupConversationService, never()).resumeDiscussion(any(), any(), any());
    }

    @Test
    void groupCardFromEarlierPause_isRefused() throws Exception {
        bindIntegration(integrationWith(INT_NAME, "U_APPROVER", "s"));
        groupPausedAt("gc-7", PAUSED_AT.plusSeconds(5));

        handler.handlePayload(approvePayload("U_APPROVER", value(GROUP_SUBJECT)));

        verify(groupConversationService, never()).resumeDiscussion(any(), any(), any());
        verify(slackApi).updateMessage(anyString(), eq("C_APPROVAL"), anyString(),
                contains("already been resolved"), any());
    }

    @Test
    void matchesPause_exactEpochOrUnknownWrittenAfterPause() {
        var exact = new SlackApprovalRecord(INT_NAME, "c", ISlackApprovalRecordStore.pauseEpochOf(PAUSED_AT), "card",
                null, PAUSED_AT, PAUSED_AT.plusSeconds(10));
        assertTrue(exact.matchesPause(PAUSED_AT));
        assertFalse(exact.matchesPause(PAUSED_AT.plusMillis(1)));
        assertFalse(exact.matchesPause(null));

        var unknown = new SlackApprovalRecord(INT_NAME, "c", ISlackApprovalRecordStore.UNKNOWN_PAUSE, "card",
                null, PAUSED_AT, PAUSED_AT.plusSeconds(10));
        assertTrue(unknown.matchesPause(PAUSED_AT));
        assertTrue(unknown.matchesPause(PAUSED_AT.minusSeconds(1)));
        assertFalse(unknown.matchesPause(PAUSED_AT.plusSeconds(1)));
        assertEquals(List.of(), approvalRecords.findBySubject(INT_NAME, "nothing"));
    }

    @Test
    void matchesCard_exactIdOnly_andNeverWithoutOne() {
        var record = new SlackApprovalRecord(INT_NAME, "c", "1", "card-a", null, PAUSED_AT, PAUSED_AT.plusSeconds(10));
        assertTrue(record.matchesCard("card-a"));
        assertFalse(record.matchesCard("card-b"));
        assertFalse(record.matchesCard("card-a2"));
        assertFalse(record.matchesCard(null));
        assertFalse(record.matchesCard(""));
        var noId = new SlackApprovalRecord(INT_NAME, "c", "1", null, null, PAUSED_AT, PAUSED_AT.plusSeconds(10));
        assertFalse(noId.matchesCard(null));
        assertFalse(noId.matchesCard("card-a"));
    }

    // --- Card binding: a click is bound to the card it was made on ---

    @Test
    void staleCardOfSameConversation_cannotResolveTheCurrentPause_butTheCurrentCardCan() throws Exception {
        bindIntegration(integrationWith(INT_NAME, "U_APPROVER", "s"));
        // conv-1's first card ("delete file A") is still in the channel. That pause
        // was resolved, the conversation paused again ("delete everything"), and a
        // NEW card was posted for the new pause.
        String staleValue = value("conv-1");
        Instant secondPause = PAUSED_AT.plusSeconds(60);
        conversationPausedAt("conv-1", secondPause);
        notified(INT_NAME, "conv-1", secondPause);

        // Clicking the OLD card must not approve an action the approver never saw.
        handler.handlePayload(approvePayload("U_APPROVER", staleValue));
        verify(conversationService, never()).resumeConversation(any(), any(), any());
        verify(slackApi).updateMessage(anyString(), eq("C_APPROVAL"), anyString(),
                contains("already been resolved"), any());

        // The card posted for the current pause does resolve it.
        handler.handlePayload(approvePayload("U_APPROVER", value("conv-1")));
        verify(conversationService).resumeConversation(eq("conv-1"), any(), isNull());
    }

    @Test
    void staleCardOfSameGroup_cannotResolveTheCurrentPause_butTheCurrentCardCan() throws Exception {
        bindIntegration(integrationWith(INT_NAME, "U_APPROVER", "s"));
        String staleValue = value(GROUP_SUBJECT);
        Instant secondPause = PAUSED_AT.plusSeconds(60);
        groupPausedAt("gc-7", secondPause);
        notified(INT_NAME, GROUP_SUBJECT, secondPause);

        handler.handlePayload(approvePayload("U_APPROVER", staleValue));
        verify(groupConversationService, never()).resumeDiscussion(any(), any(), any());

        handler.handlePayload(approvePayload("U_APPROVER", value(GROUP_SUBJECT)));
        verify(groupConversationService).resumeDiscussion(eq("gc-7"), any(), isNull());
    }

    @Test
    void unboundLegacyValue_isRefused() throws Exception {
        bindIntegration(integrationWith(INT_NAME, "U_APPROVER", "s"));
        // "<integration>|<subject>" with no card id: a card posted before card
        // binding. It cannot be tied to one card, so it must not resolve the pause
        // even though a live record for the current pause exists.
        handler.handlePayload(approvePayload("U_APPROVER", INT_NAME + "|conv-1"));
        handler.handlePayload(approvePayload("U_APPROVER", INT_NAME + "|" + GROUP_SUBJECT));

        verify(conversationService, never()).resumeConversation(any(), any(), any());
        verify(groupConversationService, never()).resumeDiscussion(any(), any(), any());
    }

    @Test
    void unknownCardId_isRefused() throws Exception {
        bindIntegration(integrationWith(INT_NAME, "U_APPROVER", "s"));
        // A live record exists for conv-1's current pause, but the click names a
        // card id no record holds (an edited or hand-built button).
        handler.handlePayload(approvePayload("U_APPROVER", value("conv-1", ISlackApprovalRecordStore.newCardId())));

        verify(conversationService, never()).resumeConversation(any(), any(), any());
        verify(slackApi, never()).updateMessage(anyString(), anyString(), anyString(), anyString(), any());
    }

    // ─── Ignored payloads ───

    @Test
    void nonBlockActions_ignored() throws Exception {
        handler.handlePayload("{\"type\":\"view_submission\"}");
        verifyNoInteractions(conversationService, groupConversationService, slackApi);
        verify(router, never()).getIntegrationByName(any(), any());
    }
}
