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

    // ─── UX Mode Detection ───

    @Test
    void onGroupStart_peerReview_setsExpandedMode() {
        listener.onGroupStart(new GroupConversationEventSink.GroupStartEvent(
                "gc1", "g1", "Test question", "PEER_REVIEW", 3, List.of("a1", "a2")));

        assertTrue(listener.isExpandedMode());
        verify(slackApi).postMessage(eq(AUTH_TOKEN), eq(CHANNEL), eq(USER_THREAD), contains("PEER REVIEW"));
    }

    @Test
    void onGroupStart_roundTable_setsCompactMode() {
        listener.onGroupStart(new GroupConversationEventSink.GroupStartEvent(
                "gc1", "g1", "Test question", "ROUND_TABLE", 2, List.of("a1", "a2")));

        assertFalse(listener.isExpandedMode());
        verify(slackApi).postMessage(eq(AUTH_TOKEN), eq(CHANNEL), eq(USER_THREAD), contains("ROUND TABLE"));
    }

    @Test
    void onGroupStart_debate_setsExpandedMode() {
        listener.onGroupStart(new GroupConversationEventSink.GroupStartEvent(
                "gc1", "g1", "Question?", "DEBATE", 3, List.of("a1", "a2", "a3")));

        assertTrue(listener.isExpandedMode());
    }

    @Test
    void onGroupStart_delphi_setsCompactMode() {
        listener.onGroupStart(new GroupConversationEventSink.GroupStartEvent(
                "gc1", "g1", "Question?", "DELPHI", 3, List.of("a1")));

        assertFalse(listener.isExpandedMode());
    }

    // ─── Compact Mode (ROUND_TABLE) ───

    @Test
    void compactMode_contributions_postedInThread() {
        initCompactMode();

        listener.onSpeakerComplete(speakerEvent("agent1", "Alice", "My opinion...", null, null));
        listener.onSpeakerComplete(speakerEvent("agent2", "Bob", "I think...", null, null));

        // Both posted in the user's thread
        verify(slackApi, times(3)).postMessage(eq(AUTH_TOKEN), eq(CHANNEL), eq(USER_THREAD), any());
    }

    @Test
    void compactMode_peerFeedback_postedInThread_withArrow() {
        initCompactMode();

        listener.onSpeakerComplete(speakerEvent("agent1", "Alice", "My opinion...", null, null));
        listener.onSpeakerComplete(speakerEvent("agent2", "Bob", "I disagree because...", "agent1", "Alice"));

        // Feedback includes arrow notation
        verify(slackApi).postMessage(eq(AUTH_TOKEN), eq(CHANNEL), eq(USER_THREAD), contains("→"));
    }

    // ─── Expanded Mode (PEER_REVIEW) ───

    @Test
    void expandedMode_firstContribution_postedAsChannelMessage() {
        initExpandedMode();
        when(slackApi.postMessage(eq(AUTH_TOKEN), eq(CHANNEL), isNull(), contains("Alice")))
                .thenReturn("1234567890.001");

        listener.onSpeakerComplete(speakerEvent("agent1", "Alice", "My opinion...", null, null));

        // Posted at channel level (null threadTs)
        verify(slackApi).postMessage(eq(AUTH_TOKEN), eq(CHANNEL), isNull(), contains("Alice"));
        // ts is tracked
        assertEquals("1234567890.001", listener.getAgentMessageTsMap().get("agent1"));
    }

    @Test
    void expandedMode_peerFeedback_postedUnderTargetMessage() {
        initExpandedMode();
        when(slackApi.postMessage(eq(AUTH_TOKEN), eq(CHANNEL), isNull(), contains("Alice")))
                .thenReturn("1234567890.001");

        // Alice posts first
        listener.onSpeakerComplete(speakerEvent("agent1", "Alice", "My opinion...", null, null));
        // Bob reviews Alice
        listener.onSpeakerComplete(speakerEvent("agent2", "Bob", "I disagree...", "agent1", "Alice"));

        // Bob's feedback threads under Alice's message
        verify(slackApi).postMessage(eq(AUTH_TOKEN), eq(CHANNEL), eq("1234567890.001"),
                contains("Bob"));
    }

    @Test
    void expandedMode_revision_threadsUnderOwnMessage() {
        initExpandedMode();
        when(slackApi.postMessage(eq(AUTH_TOKEN), eq(CHANNEL), isNull(), contains("Alice")))
                .thenReturn("1234567890.001");

        // Alice posts first (channel-level)
        listener.onSpeakerComplete(speakerEvent("agent1", "Alice", "My opinion...", null, null));
        // Alice posts again (revision — threads under own message)
        listener.onSpeakerComplete(speakerEvent("agent1", "Alice", "Revised opinion...", null, null));

        // Revision threads under Alice's own message
        verify(slackApi).postMessage(eq(AUTH_TOKEN), eq(CHANNEL), eq("1234567890.001"),
                contains("revised"));
    }

    @Test
    void expandedMode_peerFeedback_fallbackToThread_whenNoTargetTs() {
        initExpandedMode();
        // No agent2 message posted yet, so no ts in map

        listener.onSpeakerComplete(speakerEvent("agent1", "Alice", "Feedback...", "agent2", "Bob"));

        // Falls back to user thread since agent2 has no ts
        verify(slackApi).postMessage(eq(AUTH_TOKEN), eq(CHANNEL), eq(USER_THREAD), contains("Alice"));
    }

    // ─── Synthesis ───

    @Test
    void expandedMode_synthesis_postedAtChannelLevel() {
        initExpandedMode();
        listener.onSynthesisStart(new GroupConversationEventSink.SynthesisStartEvent("moderator1"));

        listener.onSpeakerComplete(speakerEvent("moderator1", "Moderator", "The panel agrees...", null, null));

        // Synthesis is channel-level (null threadTs), not threaded
        verify(slackApi).postMessage(eq(AUTH_TOKEN), eq(CHANNEL), isNull(), contains("Synthesis"));
    }

    @Test
    void compactMode_synthesis_postedInThread() {
        initCompactMode();
        listener.onSynthesisStart(new GroupConversationEventSink.SynthesisStartEvent("moderator1"));

        listener.onSpeakerComplete(speakerEvent("moderator1", "Moderator", "Summary...", null, null));

        // In compact mode, synthesis stays in the thread
        verify(slackApi).postMessage(eq(AUTH_TOKEN), eq(CHANNEL), eq(USER_THREAD), contains("Synthesis"));
    }

    // ─── Context Tracking ───

    @Test
    void agentContext_trackedForFollowUp() {
        initExpandedMode();
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
        initExpandedMode();
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
        initExpandedMode();
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
        initCompactMode();
        int beforeCount = mockingDetails(slackApi).getInvocations().size();

        listener.onSpeakerComplete(speakerEvent("agent1", "Alice", "  ", null, null));
        listener.onSpeakerComplete(speakerEvent("agent1", "Alice", null, null, null));

        // No additional postMessage calls (only the onGroupStart one)
        verify(slackApi, times(1)).postMessage(any(), any(), any(), any());
    }

    @Test
    void onGroupError_postsErrorMessage() {
        initCompactMode();

        listener.onGroupError(new GroupConversationEventSink.GroupErrorEvent("timeout"));

        verify(slackApi).postMessage(eq(AUTH_TOKEN), eq(CHANNEL), eq(USER_THREAD), contains("error"));
    }

    // ─── Completion Latch ───

    @Test
    void awaitCompletion_returnsTrueAfterGroupComplete() {
        initCompactMode();
        listener.onGroupComplete(new GroupConversationEventSink.GroupCompleteEvent(
                ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState.COMPLETED, null));

        assertTrue(listener.awaitCompletion(1, TimeUnit.SECONDS));
    }

    @Test
    void awaitCompletion_returnsTrueAfterGroupError() {
        initCompactMode();
        listener.onGroupError(new GroupConversationEventSink.GroupErrorEvent("fail"));

        assertTrue(listener.awaitCompletion(1, TimeUnit.SECONDS));
    }

    @Test
    void awaitCompletion_returnsFalseOnTimeout() {
        initCompactMode();
        // Never call onGroupComplete/onGroupError

        assertFalse(listener.awaitCompletion(50, TimeUnit.MILLISECONDS));
    }

    // ─── Synthesis Fallback ───

    @Test
    void onGroupComplete_postsSynthesisFallback_whenNotPostedDuringSpeakerComplete() {
        initCompactMode();
        // No onSynthesisStart/onSpeakerComplete, synthesis comes only in
        // onGroupComplete
        listener.onGroupComplete(new GroupConversationEventSink.GroupCompleteEvent(
                ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState.COMPLETED,
                "Final synthesis answer"));

        verify(slackApi).postMessage(eq(AUTH_TOKEN), eq(CHANNEL), eq(USER_THREAD), contains("Synthesis"));
    }

    @Test
    void onGroupComplete_doesNotDuplicateSynthesis_whenAlreadyPosted() {
        initCompactMode();
        listener.onSynthesisStart(new GroupConversationEventSink.SynthesisStartEvent("mod1"));
        listener.onSpeakerComplete(speakerEvent("mod1", "Moderator", "Synthesis via speaker", null, null));

        // Now onGroupComplete also has a synthesis — should NOT post again
        listener.onGroupComplete(new GroupConversationEventSink.GroupCompleteEvent(
                ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState.COMPLETED,
                "Duplicate synthesis"));

        // Only one synthesis message posted (the one from onSpeakerComplete)
        verify(slackApi, times(1)).postMessage(eq(AUTH_TOKEN), eq(CHANNEL), eq(USER_THREAD), contains("Synthesis"));
    }

    // ─── Helpers ───

    private void initCompactMode() {
        listener.onGroupStart(new GroupConversationEventSink.GroupStartEvent(
                "gc1", "g1", "Test?", "ROUND_TABLE", 2, List.of("a1", "a2")));
    }

    private void initExpandedMode() {
        listener.onGroupStart(new GroupConversationEventSink.GroupStartEvent(
                "gc1", "g1", "Test?", "PEER_REVIEW", 3, List.of("a1", "a2")));
    }

    private GroupConversationEventSink.SpeakerCompleteEvent speakerEvent(
                                                                         String agentId, String displayName, String response,
                                                                         String targetAgentId, String targetDisplayName) {
        return new GroupConversationEventSink.SpeakerCompleteEvent(
                agentId, displayName, response, 0, "Opinion",
                targetAgentId, targetDisplayName);
    }
}
