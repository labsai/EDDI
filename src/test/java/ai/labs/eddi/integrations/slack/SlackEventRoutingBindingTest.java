/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack;

import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.ChannelTarget;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.ConversationResult;
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
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * An inbound Slack event may only drive a route that belongs to the app whose
 * signing secret verified it. The webhook binds the signature to the owner of
 * the event's channel, but a thread lock and a group follow-up are looked up by
 * a timestamp the sender chooses, so they need the same binding here.
 */
class SlackEventRoutingBindingTest {

    private static final AtomicInteger EVENT_IDS = new AtomicInteger();

    private ChannelTargetRouter router;
    private IConversationService conversationService;
    private SlackWebApiClient slackApi;
    private Map<String, FakeCache<Object, Object>> caches;
    private SlackEventHandler handler;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() throws Exception {
        router = mock(ChannelTargetRouter.class);
        conversationService = mock(IConversationService.class);
        slackApi = mock(SlackWebApiClient.class);
        caches = new ConcurrentHashMap<>();
        var cacheFactory = mock(ICacheFactory.class);
        doAnswer(inv -> caches.computeIfAbsent(inv.getArgument(0), k -> new FakeCache<>()))
                .when(cacheFactory).getCache(anyString(), any(Duration.class));
        handler = new SlackEventHandler(router, mock(ObserveGate.class), mock(ToolCostTracker.class), slackApi,
                conversationService, mock(IGroupConversationService.class), mock(IUserConversationStore.class),
                cacheFactory,
                new SlackConfig(SlackConfig.DEFAULT_REQUEST_TIMEOUT_SECONDS,
                        SlackConfig.DEFAULT_GROUP_COMPLETION_TIMEOUT_SECONDS,
                        SlackConfig.DEFAULT_API_MAX_RETRIES, SlackConfig.DEFAULT_API_RETRY_BASE_MS),
                new InMemorySlackApprovalRecordStore(), mock(IUserMemoryStore.class));
        when(conversationService.startConversation(any(), anyString(), anyString(), any()))
                .thenReturn(new ConversationResult("conv-new", URI.create("eddi://conv-new")));
    }

    // ─── DM thread lock ───

    @Test
    void dmThreadReply_lockedToAnotherAppsTarget_isDropped() throws Exception {
        // The thread was locked to app A's agent. A DM channel is owned by no
        // integration, so the webhook accepted app B's signature for it; the lock
        // must not let B's secret continue A's thread.
        when(router.resolveThreadTarget("slack", "D1", "100.1"))
                .thenReturn(new ResolvedTarget(agentTarget("agent-A"), null, null, null, null));

        handler.handleEventAsync(nextEventId(), dmReply("D1", "100.1"), null, signedBy("int-b", "secret-b"));

        verify(router, timeout(2000)).threadCredentialsForDm(eq("slack"), any(), eq("secret-b"));
        verify(conversationService, after(700).never()).startConversation(any(), any(), any(), any());
    }

    @Test
    void dmThreadReply_lockedToTheSendersTarget_runsWithThatAppsCredentials() throws Exception {
        when(router.resolveThreadTarget("slack", "D1", "100.1"))
                .thenReturn(new ResolvedTarget(agentTarget("agent-A"), null, null, null, null));
        var withCredentials = new ResolvedTarget(agentTarget("agent-A"), null, integration("int-a", "secret-a"),
                null, null);
        when(router.threadCredentialsForDm(eq("slack"), any(), eq("secret-a"))).thenReturn(withCredentials);

        handler.handleEventAsync(nextEventId(), dmReply("D1", "100.1"), null, signedBy("int-a", "secret-a"));

        verify(conversationService, timeout(2000)).startConversation(any(), eq("agent-A"), anyString(), any());
    }

    // ─── DM default ───

    @Test
    void dmFromALegacyConnector_goesToThatConnector_notTheFirstIntegration() throws Exception {
        // A legacy per-agent connector has no name, so the webhook reports only its
        // secret. The DM must go to a legacy connector holding that secret — never to
        // resolveDefaultForDm's "first new-style integration".
        var legacy = new ResolvedTarget(agentTarget("agent-L"), "hello", null, "xoxb-l", "secret-l");
        when(router.resolveLegacyDefaultForDm("tell me more", "secret-l")).thenReturn(legacy);

        handler.handleEventAsync(nextEventId(), dmMessage("D2"), null, signedBy(null, "secret-l"));

        verify(conversationService, timeout(2000)).startConversation(any(), eq("agent-L"), anyString(), any());
        verify(router, never()).resolveDefaultForDm(any(), any(), any());
        verify(router, never()).resolveDefaultForDm(any(), any());
    }

    // ─── Any route ───

    @Test
    void routeOfAnotherApp_isDropped() throws Exception {
        // Whatever produced it, a route whose credentials belong to a different app
        // than the one that signed the event is not run.
        when(router.resolveTarget("slack", "C_A", "tell me more"))
                .thenReturn(new ResolvedTarget(agentTarget("agent-X"), "tell me more", integration("int-x", "secret-x"),
                        null, null));

        handler.handleEventAsync(nextEventId(), mention("C_A"), null, signedBy(null, "secret-a"));

        verify(router, timeout(2000)).resolveTarget("slack", "C_A", "tell me more");
        verify(conversationService, after(700).never()).startConversation(any(), any(), any(), any());
    }

    @Test
    void routeOfTheSigningApp_runs() throws Exception {
        when(router.resolveTarget("slack", "C_A", "tell me more"))
                .thenReturn(new ResolvedTarget(agentTarget("agent-A"), "tell me more", integration("int-a", "secret-a"),
                        null, null));

        handler.handleEventAsync(nextEventId(), mention("C_A"), null, signedBy(null, "secret-a"));

        verify(conversationService, timeout(2000)).startConversation(any(), eq("agent-A"), anyString(), any());
    }

    // ─── Group follow-up ───

    @Test
    void groupFollowUp_fromAnotherChannel_isDropped() throws Exception {
        // A follow-up is found by the bare timestamp of an agent's message, which the
        // sender picks. Posting it in a different channel (one the sender's app owns)
        // must not run the discussion's agent there with the discussion's context.
        registerFollowUp("200.1", "C_A", "secret-a");

        handler.handleEventAsync(nextEventId(), channelReply("C_B", "200.1"), null, signedBy(null, "secret-a"));

        verify(router, timeout(2000)).resolveThreadTarget("slack", "C_B", "200.1");
        verify(conversationService, after(700).never()).startConversation(any(), any(), any(), any());
    }

    @Test
    void groupFollowUp_signedByAnotherApp_isDropped() throws Exception {
        // Same channel (a DM the discussion ran in is owned by nobody, so any app's
        // signature passes the webhook), but not the app that started the discussion.
        registerFollowUp("200.1", "D_A", "secret-a");

        handler.handleEventAsync(nextEventId(), channelReply("D_A", "200.1"), null, signedBy("int-b", "secret-b"));

        verify(router, timeout(2000)).resolveThreadTarget("slack", "D_A", "200.1");
        verify(conversationService, after(700).never()).startConversation(any(), any(), any(), any());
    }

    @Test
    void groupFollowUp_inTheDiscussionsChannelFromItsApp_runs() throws Exception {
        registerFollowUp("200.1", "C_A", "secret-a");

        handler.handleEventAsync(nextEventId(), channelReply("C_A", "200.1"), null, signedBy(null, "secret-a"));

        verify(conversationService, timeout(2000)).startConversation(any(), eq("agent-A"), anyString(), any());
    }

    // ─── Helpers ───

    private void registerFollowUp(String agentMessageTs, String channelId, String discussionSecret) {
        var listener = mock(SlackGroupDiscussionListener.class);
        when(listener.getAgentIdForMessageTs(agentMessageTs)).thenReturn("agent-A");
        when(listener.getAgentContext("agent-A")).thenReturn(new SlackGroupDiscussionListener.AgentContext(
                "agent-A", "Alice", "contribution", "", "question", "gc-1"));
        var route = new ResolvedTarget(null, null, integration("int-a", discussionSecret), null, null);
        caches.get("slack-group-listeners").put(agentMessageTs,
                new SlackEventHandler.GroupFollowUp(listener, route, channelId));
    }

    private static SlackEventHandler.EventOrigin signedBy(String integrationName, String secret) {
        return new SlackEventHandler.EventOrigin("T1", integrationName, secret);
    }

    private static ChannelIntegrationConfiguration integration(String name, String signingSecret) {
        var cfg = new ChannelIntegrationConfiguration();
        cfg.setName(name);
        cfg.setChannelType("slack");
        cfg.setPlatformConfig(new HashMap<>(Map.of("signingSecret", signingSecret, "botToken", "xoxb-" + name)));
        return cfg;
    }

    private static ChannelTarget agentTarget(String agentId) {
        var target = new ChannelTarget();
        target.setName(agentId);
        target.setType(ChannelTarget.TargetType.AGENT);
        target.setTargetId(agentId);
        return target;
    }

    private static Map<String, Object> dmReply(String channel, String threadTs) {
        var event = channelReply(channel, threadTs);
        event.put("channel_type", "im");
        return event;
    }

    private static Map<String, Object> dmMessage(String channel) {
        var event = new HashMap<String, Object>();
        event.put("type", "message");
        event.put("channel_type", "im");
        event.put("channel", channel);
        event.put("ts", "300.1");
        event.put("user", "U1");
        event.put("text", "tell me more");
        return event;
    }

    private static Map<String, Object> mention(String channel) {
        var event = new HashMap<String, Object>();
        event.put("type", "app_mention");
        event.put("channel", channel);
        event.put("ts", "400.1");
        event.put("user", "U1");
        event.put("text", "<@UBOT> tell me more");
        return event;
    }

    private static Map<String, Object> channelReply(String channel, String threadTs) {
        var event = new HashMap<String, Object>();
        event.put("type", "message");
        event.put("channel", channel);
        event.put("thread_ts", threadTs);
        event.put("ts", threadTs + "9");
        event.put("user", "U1");
        event.put("text", "tell me more");
        return event;
    }

    private static String nextEventId() {
        return "Ev" + EVENT_IDS.incrementAndGet();
    }

    private static final class FakeCache<K, V> extends ConcurrentHashMap<K, V> implements ICache<K, V> {
        private static final long serialVersionUID = 1L;

        @Override
        public String getCacheName() {
            return "fake";
        }

        @Override
        public V put(K key, V value, long lifespan, TimeUnit unit) {
            return put(key, value);
        }

        @Override
        public V putIfAbsent(K key, V value, long lifespan, TimeUnit unit) {
            return putIfAbsent(key, value);
        }

        @Override
        public void putAll(Map<? extends K, ? extends V> map, long lifespan, TimeUnit unit) {
            putAll(map);
        }

        @Override
        public V replace(K key, V value, long lifespan, TimeUnit unit) {
            return replace(key, value);
        }

        @Override
        public boolean replace(K key, V oldValue, V value, long lifespan, TimeUnit unit) {
            return replace(key, oldValue, value);
        }

        @Override
        public V put(K key, V value, long lifespan, TimeUnit lifespanUnit, long maxIdleTime, TimeUnit maxIdleTimeUnit) {
            return put(key, value);
        }

        @Override
        public V putIfAbsent(K key, V value, long lifespan, TimeUnit lifespanUnit, long maxIdleTime,
                             TimeUnit maxIdleTimeUnit) {
            return putIfAbsent(key, value);
        }
    }
}
