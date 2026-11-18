/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack;

import ai.labs.eddi.engine.httpclient.outbound.TestOutbound;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The Slack client against {@link FakeSlackApi}, an in-process stand-in for
 * Slack's Web API. Until the API base URL was a deployment property nothing
 * that needs a <em>successful</em> Slack call could run against a fake: the
 * client always dialled slack.com.
 * <p>
 * The fake is an engine behind the real outbound facade, not a socket, so this
 * is a plain unit test.
 */
@DisplayName("Slack client against a fake Slack API")
class SlackWithFakeSlackApiTest {

    private static final String CHANNEL = "C0FAKE";
    private static final String AUTH = "Bearer xoxb-fake";

    private FakeSlackApi slack;
    private SlackWebApiClient client;

    @BeforeEach
    void setUp() {
        slack = new FakeSlackApi();
        var config = new SlackConfig(60, SlackConfig.DEFAULT_GROUP_COMPLETION_TIMEOUT_SECONDS, 3, 10L, FakeSlackApi.BASE_URL);
        client = new SlackWebApiClient(new ObjectMapper(), TestOutbound.facade(slack), config);
    }

    @Test
    @DisplayName("post message: ok:true returns the ts, and the call goes to the configured base URL")
    void postMessageOk() {
        String ts = client.postMessage(AUTH, CHANNEL, "100.1", "hello");

        assertNotNull(ts);
        var call = slack.calls("chat.postMessage").getFirst();
        assertEquals(AUTH, call.authorization());
        assertEquals(CHANNEL, call.channel());
        assertEquals("100.1", call.threadTs());
        assertEquals("hello", call.text());
    }

    @Test
    @DisplayName("post message: each post gets its own ts")
    void postMessageTsIsFresh() {
        String first = client.postMessage(AUTH, CHANNEL, null, "one");
        String second = client.postMessage(AUTH, CHANNEL, null, "two");

        assertNotNull(first);
        assertNotNull(second);
        assertEquals(2, slack.calls("chat.postMessage").size());
        assertEquals(List.of("one", "two"), slack.calls("chat.postMessage").stream().map(FakeSlackApi.Call::text).toList());
    }

    @Test
    @DisplayName("post message: ok:false is a refusal, not a retry — null, one call")
    void postMessageNotOk() {
        slack.nextError("channel_not_found");

        assertNull(client.postMessage(AUTH, CHANNEL, null, "hello"));
        assertEquals(1, slack.calls().size());
    }

    @Test
    @DisplayName("post message: 429 is a retryable failure")
    void postMessageRateLimited() {
        slack.nextRateLimited(2);

        assertThrows(SlackDeliveryException.class, () -> client.postMessage(AUTH, CHANNEL, null, "hello"));
    }

    @Test
    @DisplayName("chat.update and views.open go to the same configured base")
    void otherMethodsUseTheConfiguredBase() {
        assertEquals(true, client.updateMessage(AUTH, CHANNEL, "100.1", "done", null));
        assertEquals(true, client.openView(AUTH, "trigger-1", Map.of("type", "modal")));

        assertEquals(1, slack.calls("chat.update").size());
        assertEquals(1, slack.calls("views.open").size());
    }
}
