/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack;

import ai.labs.eddi.engine.events.HitlResumeCompletedEvent;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.triggermanagement.IUserConversationStore;
import ai.labs.eddi.engine.triggermanagement.model.UserConversation;
import ai.labs.eddi.integrations.channels.ChannelTargetRouter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link SlackHitlResumeObserver}: intent parsing, decision
 * summary formatting, delivery to the right channel/thread, and ignoring
 * non-Slack conversations.
 */
class SlackHitlResumeObserverTest {

    private IUserConversationStore userConversationStore;
    private ChannelTargetRouter router;
    private SlackWebApiClient slackApi;
    private SlackHitlResumeObserver observer;

    @BeforeEach
    void setUp() {
        userConversationStore = mock(IUserConversationStore.class);
        router = mock(ChannelTargetRouter.class);
        slackApi = mock(SlackWebApiClient.class);
        observer = new SlackHitlResumeObserver(userConversationStore, router, slackApi);
    }

    // ─── Intent parsing ───

    @Test
    void parseIntent_threadedConversation() {
        var route = SlackHitlResumeObserver.parseIntent("channel:slack:C123:agent-1:1700.0001");
        assertNotNull(route);
        assertEquals("C123", route.channelId());
        assertEquals("1700.0001", route.threadTs());
    }

    @Test
    void parseIntent_mainThreadKey_meansNoThread() {
        var route = SlackHitlResumeObserver.parseIntent("channel:slack:C123:agent-1:main");
        assertNotNull(route);
        assertEquals("C123", route.channelId());
        assertNull(route.threadTs());
    }

    @Test
    void parseIntent_nonSlack_returnsNull() {
        assertNull(SlackHitlResumeObserver.parseIntent("channel:teams:C123:agent-1:main"));
        assertNull(SlackHitlResumeObserver.parseIntent("some:other:intent"));
        assertNull(SlackHitlResumeObserver.parseIntent("channel:slack:::main"));
    }

    // ─── Decision summary ───

    @Test
    void decisionSummary_approvedHuman_mentionsUser() {
        String s = SlackHitlResumeObserver.decisionSummary(HitlVerdict.APPROVED, "slack:U9");
        assertTrue(s.contains("Approved"));
        assertTrue(s.contains("<@U9>"));
    }

    @Test
    void decisionSummary_rejected() {
        String s = SlackHitlResumeObserver.decisionSummary(HitlVerdict.REJECTED, "slack:U9");
        assertTrue(s.contains("rejected"));
    }

    @Test
    void decisionSummary_automatedTimeout_labelled() {
        String s = SlackHitlResumeObserver.decisionSummary(HitlVerdict.REJECTED, "system:timeout");
        assertTrue(s.contains("system:timeout"));
    }

    // ─── Delivery ───

    @Test
    void onResumeCompleted_slackConversation_postsToChannelAndThread() throws Exception {
        var mapping = new UserConversation("channel:slack:C123:agent-1:1700.0001", "U1",
                Deployment.Environment.production, "agent-1", "conv-1");
        when(userConversationStore.readUserConversationByConversationId("conv-1")).thenReturn(mapping);
        when(router.getBotToken("slack", "C123")).thenReturn("xoxb-token");

        observer.onResumeCompleted(new HitlResumeCompletedEvent(
                "conv-1", HitlVerdict.APPROVED, "slack:U9", snapshotWithText("Order placed.")));

        verify(slackApi).postMessage(eq("Bearer xoxb-token"), eq("C123"), eq("1700.0001"),
                contains("Order placed."));
    }

    @Test
    void onResumeCompleted_nonSlackConversation_ignored() throws Exception {
        var mapping = new UserConversation("intent:mcp:something", "U1",
                Deployment.Environment.production, "agent-1", "conv-2");
        when(userConversationStore.readUserConversationByConversationId("conv-2")).thenReturn(mapping);

        observer.onResumeCompleted(new HitlResumeCompletedEvent(
                "conv-2", HitlVerdict.APPROVED, "slack:U9", snapshotWithText("x")));

        verifyNoInteractions(slackApi);
        verify(router, never()).getBotToken(any(), any());
    }

    @Test
    void onResumeCompleted_noMapping_ignored() throws Exception {
        when(userConversationStore.readUserConversationByConversationId("conv-3")).thenReturn(null);

        observer.onResumeCompleted(new HitlResumeCompletedEvent(
                "conv-3", HitlVerdict.APPROVED, "slack:U9", snapshotWithText("x")));

        verifyNoInteractions(slackApi);
    }

    @Test
    void onResumeCompleted_storeError_swallowed() throws Exception {
        when(userConversationStore.readUserConversationByConversationId(any()))
                .thenThrow(new ai.labs.eddi.datastore.IResourceStore.ResourceStoreException("boom"));

        // Must not throw — observer failures are best-effort.
        assertDoesNotThrow(() -> observer.onResumeCompleted(new HitlResumeCompletedEvent(
                "conv-4", HitlVerdict.APPROVED, "slack:U9", snapshotWithText("x"))));
        verifyNoInteractions(slackApi);
    }

    private static SimpleConversationMemorySnapshot snapshotWithText(String text) {
        var snapshot = new SimpleConversationMemorySnapshot();
        var output = new ConversationOutput();
        output.put("output", List.of(Map.of("text", text)));
        snapshot.setConversationOutputs(List.of(output));
        return snapshot;
    }
}
