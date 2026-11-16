/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack;

import ai.labs.eddi.configs.groups.model.HumanRequestDetail;
import ai.labs.eddi.engine.lifecycle.GroupConversationEventSink.HumanInputRequestedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@DisplayName("Slack — the tie-break notice")
class SlackTieBreakNoticeTest {

    private static final String TOKEN = "Bearer xoxb-test";

    @Test
    @DisplayName("a tie-break names the decider and the options, and stays notification-only")
    void tieBreakNotice() {
        var api = mock(SlackWebApiClient.class);
        var listener = new SlackGroupDiscussionListener(api, TOKEN, "C1", "1.0");

        listener.onHumanInputRequested(new HumanInputRequestedEvent("founder@acme.com", "Founder", 2, "Board vote",
                HumanRequestDetail.tieBreak(List.of("Double down on SEO", "Launch the paid pilot"), 4, "NO_DECISION")));

        var text = ArgumentCaptor.forClass(String.class);
        verify(api).postMessage(eq(TOKEN), eq("C1"), anyString(), text.capture());
        assertTrue(text.getValue().startsWith("🗳️ Tie in *Board vote* — *Founder* decides:"), text.getValue());
        assertTrue(text.getValue().contains("• Double down on SEO\n• Launch the paid pilot\n"), text.getValue());
        assertTrue(text.getValue().contains("Decide in EDDI"), text.getValue());
    }

    @Test
    @DisplayName("a human task says a task is ready")
    void taskNotice() {
        var api = mock(SlackWebApiClient.class);
        var listener = new SlackGroupDiscussionListener(api, TOKEN, "C1", "1.0");

        listener.onHumanInputRequested(new HumanInputRequestedEvent("h1", "Hannah", 1, "Execute",
                new HumanRequestDetail(HumanRequestDetail.KIND_TASK, null, "t-1", null, null)));

        var text = ArgumentCaptor.forClass(String.class);
        verify(api).postMessage(eq(TOKEN), eq("C1"), anyString(), text.capture());
        assertTrue(text.getValue().startsWith("📋 *Hannah* — a task assigned to you is ready in *Execute*"), text.getValue());
    }

    @Test
    @DisplayName("a plain speaking turn keeps its notice")
    void plainTurn() {
        var api = mock(SlackWebApiClient.class);
        var listener = new SlackGroupDiscussionListener(api, TOKEN, "C1", "1.0");

        listener.onHumanInputRequested(new HumanInputRequestedEvent("h1", "Hannah", 0, "Discuss"));

        var text = ArgumentCaptor.forClass(String.class);
        verify(api).postMessage(eq(TOKEN), eq("C1"), anyString(), text.capture());
        assertTrue(text.getValue().contains("you're up in *Discuss*"), text.getValue());
        assertFalse(text.getValue().contains("Tie"), text.getValue());
    }
}
