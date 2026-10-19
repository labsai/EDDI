/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack;

import ai.labs.eddi.engine.httpclient.outbound.ScriptedEngine;
import ai.labs.eddi.engine.httpclient.outbound.TestOutbound;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * What {@link SlackWebApiClient} sends, through the real outbound facade over a
 * scripted engine — no network.
 */
@DisplayName("SlackWebApiClient — on the wire")
class SlackWebApiClientRedirectTest {

    @Test
    @DisplayName("a redirect is never followed, so the bot token is sent to slack.com only")
    void redirectIsNotFollowed() {
        ScriptedEngine engine = new ScriptedEngine()
                .respond(302, Map.of("Location", List.of("https://203.0.113.50/collect")), new byte[0]);
        var client = new SlackWebApiClient(new ObjectMapper(), TestOutbound.facade(engine));

        assertNull(client.postMessage("Bearer xoxb-secret", "C0123", null, "hi"), "a 3xx is a non-retryable failure");
        assertEquals(1, engine.hops.size(), "exactly one request, to Slack");
        assertEquals("slack.com", engine.lastHop().uri().getHost());
    }

    @Test
    @DisplayName("an ok answer returns the message ts")
    void okAnswer() {
        ScriptedEngine engine = new ScriptedEngine().respond(200, "{\"ok\":true,\"ts\":\"1700000000.0001\"}");
        var client = new SlackWebApiClient(new ObjectMapper(), TestOutbound.facade(engine));

        assertEquals("1700000000.0001", client.postMessage("Bearer xoxb-secret", "C0123", null, "hi"));
        assertEquals("POST", engine.lastHop().method());
        assertEquals(List.of("Bearer xoxb-secret"), engine.lastHop().headers().get("authorization"));
    }
}
