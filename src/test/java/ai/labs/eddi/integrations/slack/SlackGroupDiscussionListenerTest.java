/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack;

import ai.labs.eddi.engine.lifecycle.GroupConversationEventSink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link SlackGroupDiscussionListener}.
 * <p>
 * All discussion styles now use EXPANDED mode (channel-level messages with
 * per-agent threads). There is no compact mode.
 */
class SlackGroupDiscussionListenerTest {

    private SlackWebApiClient slackApi;
    private SlackGroupDiscussionListener listener;

    private static final String AUTH_TOKEN = "Bearer xoxb-test";
    private static final String CHANNEL = "C0123";
    private static final String USER_THREAD = "1234567890.000";

    @BeforeEach
    void setUp() {
        slackApi = mock(SlackWebApiClient.class);
        listener = new SlackGroupDiscussionListener(slackApi, AUTH_TOKEN, CHANNEL, USER_THREAD);
    }

    // ─── UX Mode Detection — all styles use expanded ───

    @Test
    void onGroupStart_roundTable_setsExpandedMode() {
        listener.onGroupStart(groupStart("ROUND_TABLE", 2));
        assertTrue(listener.isExpandedMode());
        verify(slackApi).postMessage(eq(AUTH_TOKEN), eq(CHANNEL), eq(USER_THREAD),
                contains("round table"));
    }

    @Test
    void onGroupStart_peerReview_setsExpandedMode() {
        listener.onGroupStart(groupStart("PEER_REVIEW", 3));
        assertTrue(listener.isExpandedMode());
        verify(slackApi).postMessage(eq(AUTH_TOKEN), eq(CHANNEL), eq(USER_THREAD),
                contains("peer review"));
    }

    @Test
    void onGroupStart_debate_setsExpandedMode() {
        listener.onGroupStart(groupStart("DEBATE", 3));
        assertTrue(listener.isExpandedMode());
    }

    @Test
    void onGroupStart_devilAdvocate_setsExpandedMode() {
        listener.onGroupStart(groupStart("DEVIL_ADVOCATE", 2));
        assertTrue(listener.isExpandedMode());
        verify(slackApi).postMessage(eq(AUTH_TOKEN), eq(CHANNEL), eq(USER_THREAD),
                contains("devil advocate"));
    }

    @Test
    void onGroupStart_delphi_setsExpandedMode() {
        listener.onGroupStart(groupStart("DELPHI", 3));
        assertTrue(listener.isExpandedMode());
    }

    @Test
    void onGroupStart_messageContainsAgentCount() {
        listener.onGroupStart(groupStart("ROUND_TABLE", 2));
        verify(slackApi).postMessage(eq(AUTH_TOKEN), eq(CHANNEL), eq(USER_THREAD),
                contains("2 agents"));
    }

    @Test
    void onGroupStart_messageContainsQuestionInBlockquote() {
        listener.onGroupStart(new GroupConversationEventSink.GroupStartEvent(
                "gc1", "g1", "What is EDDI?", "ROUND_TABLE", 2, List.of("a1", "a2")));
        verify(slackApi).postMessage(eq(AUTH_TOKEN), eq(CHANNEL), eq(USER_THREAD),
                contains("> _What is EDDI?_"));
    }

    // ─── Primary Contributions (EXPANDED mode) ───

    @Test
    void expandedMode_firstContribution_postsHeaderAndThread() {
        initExpanded();
        when(slackApi.postMessage(eq(AUTH_TOKEN), eq(CHANNEL), isNull(), contains("Alice")))
                .thenReturn("ts-alice");

        listener.onSpeakerComplete(speakerEvent("agent1", "Alice", "My opinion...", null, null));

        // Header at channel level
        verify(slackApi).postMessage(eq(AUTH_TOKEN), eq(CHANNEL), isNull(), contains("Alice"));
        // Full response in thread
        verify(slackApi).postMessage(eq(AUTH_TOKEN), eq(CHANNEL), eq("ts-alice"), eq("My opinion..."));
        // ts is tracked
        assertEquals("ts-alice", listener.getAgentMessageTsMap().get("agent1"));
    }

    @Test
    void expandedMode_secondAgent_postsOwnHeader() {
        initExpanded();
        when(slackApi.postMessage(eq(AUTH_TOKEN), eq(CHANNEL), isNull(), contains("Alice")))
                .thenReturn("ts-alice");
        when(slackApi.postMessage(eq(AUTH_TOKEN), eq(CHANNEL), isNull(), contains("Bob")))
                .thenReturn("ts-bob");

        listener.onSpeakerComplete(speakerEvent("agent1", "Alice", "Opinion A", null, null));
        listener.onSpeakerComplete(speakerEvent("agent2", "Bob", "Opinion B", null, null));

        assertEquals("ts-alice", listener.getAgentMessageTsMap().get("agent1"));
        assertEquals("ts-bob", listener.getAgentMessageTsMap().get("agent2"));
    }

    // ─── Peer Feedback ───

    @Test
    void expandedMode_peerFeedback_postedUnderTargetMessage() {
        initExpanded();
        when(slackApi.postMessage(eq(AUTH_TOKEN), eq(CHANNEL), isNull(), contains("Alice")))
                .thenReturn("ts-alice");

        listener.onSpeakerComplete(speakerEvent("agent1", "Alice", "My opinion...", null, null));
        listener.onSpeakerComplete(speakerEvent("agent2", "Bob", "I disagree...", "agent1", "Alice"));

        // Bob's feedback threads under Alice's message
        verify(slackApi).postMessage(eq(AUTH_TOKEN), eq(CHANNEL), eq("ts-alice"),
                contains("Bob"));
    }

    @Test
    void expandedMode_peerFeedback_containsArrowNotation() {
        initExpanded();
        when(slackApi.postMessage(eq(AUTH_TOKEN), eq(CHANNEL), isNull(), contains("Alice")))
                .thenReturn("ts-alice");

        listener.onSpeakerComplete(speakerEvent("agent1", "Alice", "My opinion...", null, null));
        listener.onSpeakerComplete(speakerEvent("agent2", "Bob", "I disagree...", "agent1", "Alice"));

        verify(slackApi).postMessage(eq(AUTH_TOKEN), eq(CHANNEL), eq("ts-alice"),
                contains("→"));
    }

    @Test
    void expandedMode_peerFeedback_fallbackToThread_whenNoTargetTs() {
        initExpanded();
        // No agent2 message posted yet, so no ts in map
        listener.onSpeakerComplete(speakerEvent("agent1", "Alice", "Feedback...", "agent2", "Bob"));

        // Falls back to user thread since agent2 has no ts
        verify(slackApi).postMessage(eq(AUTH_TOKEN), eq(CHANNEL), eq(USER_THREAD), contains("Alice"));
    }

    // ─── Revisions ───

    @Test
    void expandedMode_revision_threadsUnderOwnMessage() {
        initExpanded();
        when(slackApi.postMessage(eq(AUTH_TOKEN), eq(CHANNEL), isNull(), contains("Alice")))
                .thenReturn("ts-alice");

        listener.onSpeakerComplete(speakerEvent("agent1", "Alice", "My opinion...", null, null));
        listener.onSpeakerComplete(speakerEvent("agent1", "Alice", "Revised opinion...", null, null));

        // Revision threads under Alice's own message
        verify(slackApi).postMessage(eq(AUTH_TOKEN), eq(CHANNEL), eq("ts-alice"),
                contains("revised"));
    }

    // ─── Synthesis (header + thread pattern) ───

    @Test
    void expandedMode_synthesis_postsHeaderAndThread() {
        initExpanded();
        when(slackApi.postMessage(eq(AUTH_TOKEN), eq(CHANNEL), isNull(), contains("Synthesis")))
                .thenReturn("ts-synth");

        listener.onSynthesisStart(new GroupConversationEventSink.SynthesisStartEvent("mod1"));
        listener.onSpeakerComplete(speakerEvent("mod1", "Moderator", "The panel agrees...", null, null));

        // Header at channel level
        verify(slackApi).postMessage(eq(AUTH_TOKEN), eq(CHANNEL), isNull(), contains("Panel Synthesis"));
        // Full content in thread
        verify(slackApi).postMessage(eq(AUTH_TOKEN), eq(CHANNEL), eq("ts-synth"),
                eq("The panel agrees..."));
    }

    @Test
    void synthesis_headerContainsModerator() {
        initExpanded();
        when(slackApi.postMessage(eq(AUTH_TOKEN), eq(CHANNEL), isNull(), any()))
                .thenReturn("ts-synth");

        listener.onSynthesisStart(new GroupConversationEventSink.SynthesisStartEvent("mod1"));
        listener.onSpeakerComplete(speakerEvent("mod1", "Moderator", "The panel agrees...", null, null));

        verify(slackApi).postMessage(eq(AUTH_TOKEN), eq(CHANNEL), isNull(),
                contains("Moderator"));
    }

    // ─── Context Tracking ───

    @Test
    void agentContext_trackedForFollowUp() {
        initExpanded();
        when(slackApi.postMessage(eq(AUTH_TOKEN), eq(CHANNEL), isNull(), any()))
                .thenReturn("ts1");

        listener.onSpeakerComplete(speakerEvent("agent1", "Alice", "My contribution...", null, null));

        var ctx = listener.getAgentContext("agent1");
        assertNotNull(ctx);
        assertEquals("agent1", ctx.agentId());
        assertEquals("Alice", ctx.displayName());
        assertEquals("My contribution...", ctx.contribution());
    }

    @Test
    void agentContext_feedbackAccumulated() {
        initExpanded();
        when(slackApi.postMessage(eq(AUTH_TOKEN), eq(CHANNEL), isNull(), any()))
                .thenReturn("ts1");

        listener.onSpeakerComplete(speakerEvent("agent1", "Alice", "My opinion...", null, null));
        listener.onSpeakerComplete(speakerEvent("agent2", "Bob", "I disagree...", "agent1", "Alice"));
        listener.onSpeakerComplete(speakerEvent("agent3", "Carol", "I agree with Alice...", "agent1", "Alice"));

        var ctx = listener.getAgentContext("agent1");
        assertNotNull(ctx);
        assertTrue(ctx.feedbackReceived().contains("Bob"));
        assertTrue(ctx.feedbackReceived().contains("Carol"));
    }

    @Test
    void getAgentIdForMessageTs_returnsCorrectAgent() {
        initExpanded();
        when(slackApi.postMessage(eq(AUTH_TOKEN), eq(CHANNEL), isNull(), contains("Alice")))
                .thenReturn("ts-alice");
        when(slackApi.postMessage(eq(AUTH_TOKEN), eq(CHANNEL), isNull(), contains("Bob")))
                .thenReturn("ts-bob");

        listener.onSpeakerComplete(speakerEvent("agent1", "Alice", "Opinion A", null, null));
        listener.onSpeakerComplete(speakerEvent("agent2", "Bob", "Opinion B", null, null));

        assertEquals("agent1", listener.getAgentIdForMessageTs("ts-alice"));
        assertEquals("agent2", listener.getAgentIdForMessageTs("ts-bob"));
        assertNull(listener.getAgentIdForMessageTs("ts-unknown"));
    }

    // ─── Edge Cases ───

    @Test
    void blankResponse_notPosted() {
        initExpanded();

        listener.onSpeakerComplete(speakerEvent("agent1", "Alice", "  ", null, null));
        listener.onSpeakerComplete(speakerEvent("agent1", "Alice", null, null, null));

        // No additional postMessage calls beyond the onGroupStart one
        verify(slackApi, times(1)).postMessage(any(), any(), any(), any());
    }

    @Test
    void onGroupError_postsErrorMessage() {
        initExpanded();

        listener.onGroupError(new GroupConversationEventSink.GroupErrorEvent("timeout"));

        // Error posted at channel level in expanded mode
        verify(slackApi).postMessage(eq(AUTH_TOKEN), eq(CHANNEL), isNull(), contains("error"));
    }

    // ─── Completion Latch ───

    @Test
    void awaitCompletion_returnsTrueAfterGroupComplete() {
        initExpanded();
        listener.onGroupComplete(new GroupConversationEventSink.GroupCompleteEvent(
                ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState.COMPLETED, null));

        assertTrue(listener.awaitCompletion(1, TimeUnit.SECONDS));
    }

    @Test
    void awaitCompletion_returnsTrueAfterGroupError() {
        initExpanded();
        listener.onGroupError(new GroupConversationEventSink.GroupErrorEvent("fail"));

        assertTrue(listener.awaitCompletion(1, TimeUnit.SECONDS));
    }

    @Test
    void awaitCompletion_returnsFalseOnTimeout() {
        initExpanded();
        assertFalse(listener.awaitCompletion(50, TimeUnit.MILLISECONDS));
    }

    // ─── Synthesis Fallback ───

    @Test
    void onGroupComplete_postsSynthesisFallback_whenNotPostedDuringSpeakerComplete() {
        initExpanded();
        when(slackApi.postMessage(eq(AUTH_TOKEN), eq(CHANNEL), isNull(), contains("Synthesis")))
                .thenReturn("ts-synth");

        listener.onGroupComplete(new GroupConversationEventSink.GroupCompleteEvent(
                ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState.COMPLETED,
                "Final synthesis answer"));

        verify(slackApi).postMessage(eq(AUTH_TOKEN), eq(CHANNEL), isNull(), contains("Synthesis"));
    }

    @Test
    void onGroupComplete_doesNotDuplicateSynthesis_whenAlreadyPosted() {
        initExpanded();
        when(slackApi.postMessage(eq(AUTH_TOKEN), eq(CHANNEL), isNull(), contains("Synthesis")))
                .thenReturn("ts-synth");

        listener.onSynthesisStart(new GroupConversationEventSink.SynthesisStartEvent("mod1"));
        listener.onSpeakerComplete(speakerEvent("mod1", "Moderator", "Synthesis via speaker", null, null));

        // Now onGroupComplete also has a synthesis — should NOT post again
        listener.onGroupComplete(new GroupConversationEventSink.GroupCompleteEvent(
                ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState.COMPLETED,
                "Duplicate synthesis"));

        // Only one synthesis header posted
        verify(slackApi, times(1)).postMessage(eq(AUTH_TOKEN), eq(CHANNEL), isNull(),
                contains("Synthesis"));
    }

    // ─── Helpers ───

    private void initExpanded() {
        listener.onGroupStart(groupStart("PEER_REVIEW", 3));
    }

    private GroupConversationEventSink.GroupStartEvent groupStart(String style, int memberCount) {
        List<String> ids = new java.util.ArrayList<>();
        for (int i = 0; i < memberCount; i++)
            ids.add("a" + i);
        return new GroupConversationEventSink.GroupStartEvent(
                "gc1", "g1", "Test?", style, memberCount, ids);
    }

    private GroupConversationEventSink.SpeakerCompleteEvent speakerEvent(
                                                                         String agentId, String displayName, String response,
                                                                         String targetAgentId, String targetDisplayName) {
        return new GroupConversationEventSink.SpeakerCompleteEvent(
                agentId, displayName, response, 0, "Opinion",
                targetAgentId, targetDisplayName);
    }

    // ─── HITL ───

    @Test
    void onHitlPause_postsThreadNotice() {
        listener.onGroupStart(groupStart("ROUND_TABLE", 2));
        listener.onHitlPause(new GroupConversationEventSink.HitlPauseEvent(0, "Phase 1", "needs sign-off", "phase"));
        verify(slackApi).postMessage(eq(AUTH_TOKEN), eq(CHANNEL), eq(USER_THREAD), contains("awaiting approval"));
    }

    @Test
    void onHitlPause_withApprovalChannelAndApprovers_postsButtons() {
        var withHitl = new SlackGroupDiscussionListener(slackApi, AUTH_TOKEN, CHANNEL, USER_THREAD,
                "C_APPROVAL", "U1,U2");
        withHitl.onGroupStart(groupStart("ROUND_TABLE", 2));

        withHitl.onHitlPause(new GroupConversationEventSink.HitlPauseEvent(0, "Phase 1", "sign-off", "phase"));

        // Interactive block message posted to the approval channel with group value
        verify(slackApi).postBlocksMessage(eq(AUTH_TOKEN), eq("C_APPROVAL"), isNull(), anyList(), anyString());
    }

    @Test
    void onHitlPause_withIntegrationName_buttonValueBindsIntegration() {
        // H2/H1: the group approval button value carries
        // "<integrationName>|group:<gcId>"
        // so the decision binds to that integration at the interactivity endpoint.
        var withHitl = new SlackGroupDiscussionListener(slackApi, AUTH_TOKEN, CHANNEL, USER_THREAD,
                "C_APPROVAL", "U1,U2", "acme-int");
        withHitl.onGroupStart(groupStart("ROUND_TABLE", 2));

        withHitl.onHitlPause(new GroupConversationEventSink.HitlPauseEvent(0, "Phase 1", "sign-off", "phase"));

        var blocksCaptor = org.mockito.ArgumentCaptor.forClass(java.util.List.class);
        verify(slackApi).postBlocksMessage(eq(AUTH_TOKEN), eq("C_APPROVAL"), isNull(), blocksCaptor.capture(), anyString());
        @SuppressWarnings("unchecked")
        var blocks = (java.util.List<java.util.Map<String, Object>>) blocksCaptor.getValue();
        var actions = blocks.stream().filter(b -> "actions".equals(b.get("type"))).findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        var elements = (java.util.List<java.util.Map<String, Object>>) actions.get("elements");
        String value = (String) elements.get(0).get("value");
        var parsed = SlackHitlSupport.parseActionValue(value);
        assertEquals("acme-int", parsed.integrationName());
        assertTrue(parsed.isGroup());
    }

    @Test
    void onHitlPause_noApprovalChannel_noBlockMessage() {
        listener.onGroupStart(groupStart("ROUND_TABLE", 2));
        listener.onHitlPause(new GroupConversationEventSink.HitlPauseEvent(0, "Phase 1", "sign-off", "phase"));
        verify(slackApi, never()).postBlocksMessage(any(), any(), any(), anyList(), anyString());
    }

    @Test
    void onHitlResume_postsVerdict() {
        listener.onGroupStart(groupStart("ROUND_TABLE", 2));
        listener.onHitlResume(new GroupConversationEventSink.HitlResumeEvent("APPROVED", "looks good", "slack:U1"));
        verify(slackApi).postMessage(eq(AUTH_TOKEN), eq(CHANNEL), eq(USER_THREAD), contains("approved"));
    }

    @Test
    void onMemberPauseSkipped_postsWarning() {
        listener.onGroupStart(groupStart("ROUND_TABLE", 2));
        listener.onMemberPauseSkipped(new GroupConversationEventSink.MemberPauseSkippedEvent(
                "a1", "Alice", 0, "Phase 1", "gated action"));
        verify(slackApi).postMessage(eq(AUTH_TOKEN), eq(CHANNEL), eq(USER_THREAD), contains("skipped"));
    }
}
