/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack;

import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.ChannelTarget;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.triggermanagement.IUserConversationStore;
import ai.labs.eddi.integrations.channels.ChannelTargetRouter;
import ai.labs.eddi.integrations.channels.ChannelTargetRouter.ResolvedTarget;
import ai.labs.eddi.integrations.channels.ObserveGate;
import ai.labs.eddi.integrations.slack.hitl.InMemorySlackApprovalRecordStore;
import ai.labs.eddi.modules.llm.tools.ToolCostTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A DM's channel id belongs to no integration, so the router has no bot token
 * for it. The normal answer path takes the token from the route it resolved;
 * the error notice, the help text and the timeout line had no route and passed
 * {@code null}, so in a DM they were never sent. They now use the token of the
 * app that signed the event.
 */
@DisplayName("Slack: DMs on the error and help paths")
class SlackDirectMessageTokenTest {

    private static final AtomicInteger IDS = new AtomicInteger();
    private static final SlackEventHandler.EventOrigin SIGNED_BY_APP_A = new SlackEventHandler.EventOrigin("T1", "app-a",
            "secret-a");

    private ChannelTargetRouter router;
    private IConversationService conversationService;
    private SlackWebApiClient slackApi;
    private SlackEventHandler handler;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() {
        router = mock(ChannelTargetRouter.class);
        conversationService = mock(IConversationService.class);
        slackApi = mock(SlackWebApiClient.class);
        var cacheFactory = mock(ICacheFactory.class);
        doReturn(mock(ICache.class)).when(cacheFactory).getCache(anyString(), any(Duration.class));
        handler = new SlackEventHandler(router, mock(ObserveGate.class), mock(ToolCostTracker.class), slackApi,
                conversationService, mock(IGroupConversationService.class), mock(IUserConversationStore.class),
                cacheFactory,
                new SlackConfig(SlackConfig.DEFAULT_REQUEST_TIMEOUT_SECONDS,
                        SlackConfig.DEFAULT_GROUP_COMPLETION_TIMEOUT_SECONDS, 1, SlackConfig.DEFAULT_API_RETRY_BASE_MS),
                new InMemorySlackApprovalRecordStore(), mock(IUserMemoryStore.class));
        when(router.getBotTokenForSigner("slack", "app-a", "secret-a")).thenReturn("xoxb-signer");
    }

    @Test
    @DisplayName("an error while handling a DM is reported with the signing app's token")
    void errorNoticeInADm() throws Exception {
        var target = new ChannelTarget();
        target.setName("default");
        target.setType(ChannelTarget.TargetType.AGENT);
        target.setTargetId("agent-A");
        var integration = new ChannelIntegrationConfiguration();
        integration.setName("app-a");
        integration.setPlatformConfig(new HashMap<>(Map.of("signingSecret", "secret-a", "botToken", "xoxb-signer")));
        when(router.resolveDefaultForDm("slack", "hello", "app-a"))
                .thenReturn(new ResolvedTarget(target, "hello", integration, null, null));
        when(conversationService.startConversation(any(), anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("boom"));

        handler.handleEventAsync("Ev" + IDS.incrementAndGet(), dm("hello"), "UBOT", SIGNED_BY_APP_A);

        verify(slackApi, timeout(3000)).postMessage(eq("Bearer xoxb-signer"), eq("D0DM"), any(), contains("error"));
    }

    @Test
    @DisplayName("the help text in a DM is sent with the signing app's token")
    void helpInADm() throws Exception {
        // "help" resolves to no target, so the handler answers with the help text.
        when(router.resolveDefaultForDm("slack", "help", "app-a")).thenReturn(null);

        handler.handleEventAsync("Ev" + IDS.incrementAndGet(), dm("help"), "UBOT", SIGNED_BY_APP_A);

        verify(slackApi, timeout(3000)).postMessage(eq("Bearer xoxb-signer"), eq("D0DM"), any(), contains("Hi!"));
    }

    @Test
    @DisplayName("in a channel an integration owns, that integration's own token is used and the signer is not consulted")
    void ownedChannelKeepsItsToken() throws Exception {
        when(router.getBotToken("slack", "C0CHAN")).thenReturn("xoxb-channel");
        when(router.resolveTarget("slack", "C0CHAN", "help")).thenReturn(null);
        var event = dm("help");
        event.put("channel", "C0CHAN");
        event.put("channel_type", "channel");
        event.put("type", "app_mention");

        handler.handleEventAsync("Ev" + IDS.incrementAndGet(), event, "UBOT",
                new SlackEventHandler.EventOrigin("T1", null, "secret-a"));

        verify(slackApi, timeout(3000)).postMessage(eq("Bearer xoxb-channel"), eq("C0CHAN"), any(), anyString());
        verify(router, never()).getBotTokenForSigner(any(), any(), any());
    }

    private static Map<String, Object> dm(String text) {
        var event = new HashMap<String, Object>();
        event.put("type", "message");
        event.put("channel", "D0DM");
        event.put("channel_type", "im");
        event.put("ts", "100.1");
        event.put("user", "U0ALICE");
        event.put("text", text);
        return event;
    }
}
