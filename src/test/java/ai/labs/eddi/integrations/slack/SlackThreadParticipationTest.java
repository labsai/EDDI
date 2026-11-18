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
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.triggermanagement.IUserConversationStore;
import ai.labs.eddi.engine.triggermanagement.model.UserConversation;
import ai.labs.eddi.integrations.channels.ChannelTargetRouter;
import ai.labs.eddi.integrations.channels.ChannelTargetRouter.ResolvedTarget;
import ai.labs.eddi.integrations.channels.ObserveGate;
import ai.labs.eddi.integrations.slack.hitl.InMemorySlackApprovalRecordStore;
import ai.labs.eddi.modules.llm.tools.ToolCostTracker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which Slack messages the bot answers.
 * <p>
 * The event shapes are those {@code slackprobe.py} posted to a live instance:
 * with {@code message.channels} subscribed (the setup guide asks for it), Slack
 * delivers every message of a channel, so a reply in a thread of humans reached
 * the model, and a message that mentioned the bot in mid-sentence got two
 * answers — one per event Slack sends for it.
 */
@DisplayName("Slack: which messages the bot answers")
class SlackThreadParticipationTest {

    private static final String CHANNEL = "C0REVIEW";
    private static final String BOT = "UBOT";
    private static final String SECRET = "secret-a";
    private static final String AGENT = "agent-A";
    private static final AtomicInteger IDS = new AtomicInteger();

    private ChannelTargetRouter router;
    private IConversationService conversationService;
    private IUserConversationStore userConversationStore;
    private SlackWebApiClient slackApi;
    private SimpleMeterRegistry meters;
    private SlackEventHandler handler;
    private final Map<String, FakeCache<Object, Object>> caches = new ConcurrentHashMap<>();
    private ChannelIntegrationConfiguration integration;

    @BeforeEach
    @SuppressWarnings({"unchecked", "rawtypes"})
    void setUp() throws Exception {
        router = mock(ChannelTargetRouter.class);
        conversationService = mock(IConversationService.class);
        userConversationStore = mock(IUserConversationStore.class);
        slackApi = mock(SlackWebApiClient.class);
        meters = new SimpleMeterRegistry();
        var cacheFactory = mock(ICacheFactory.class);
        doAnswer(inv -> caches.computeIfAbsent(inv.getArgument(0), k -> new FakeCache<>()))
                .when(cacheFactory).getCache(anyString(), any(Duration.class));
        handler = new SlackEventHandler(router, mock(ObserveGate.class), mock(ToolCostTracker.class), slackApi,
                conversationService, mock(IGroupConversationService.class), userConversationStore, cacheFactory,
                new SlackConfig(5, SlackConfig.DEFAULT_GROUP_COMPLETION_TIMEOUT_SECONDS, 1,
                        SlackConfig.DEFAULT_API_RETRY_BASE_MS),
                new InMemorySlackApprovalRecordStore(), mock(IUserMemoryStore.class), meters);
        when(conversationService.startConversation(any(), anyString(), anyString(), any()))
                .thenReturn(new ConversationResult("conv-new", URI.create("eddi://conv-new")));
        doAnswer(inv -> {
            ((IConversationService.ConversationResponseHandler) inv.getArgument(6)).onComplete(answer("hello"));
            return null;
        }).when(conversationService).say(anyString(), any(), any(), any(), any(), anyBoolean(), any());
        when(slackApi.postMessage(anyString(), anyString(), any(), anyString())).thenReturn("1.1");

        integration = new ChannelIntegrationConfiguration();
        integration.setName("int-a");
        integration.setChannelType("slack");
        integration.setPlatformConfig(new HashMap<>(Map.of("signingSecret", SECRET, "botToken", "xoxb-a")));
        integration.setTargets(List.of(target()));
        when(router.integrationFor("slack", CHANNEL)).thenReturn(integration);
    }

    // ─── An unmentioned thread reply ───

    @Test
    @DisplayName("a reply in a thread of humans — the bot never took part — runs no turn and calls no model")
    void humanOnlyThread_isIgnored() throws Exception {
        handler.handleEventAsync(id(), threadReply("100.1", "100.5", "any update on the release?"), BOT, origin());

        verify(router, timeout(2000)).resolveThreadTarget("slack", CHANNEL, "100.1");
        verify(router, after(500).never()).resolveTarget(any(), any(), any());
        verify(conversationService, never()).startConversation(any(), any(), any(), any());
        verify(conversationService, never()).say(any(), any(), any(), any(), any(), anyBoolean(), any());
        assertEquals(1.0, meters.counter("eddi.slack.thread.replies.ignored").count());
    }

    @Test
    @DisplayName("a second reply in the same human thread is ignored as well")
    void secondHumanReply_isIgnored() throws Exception {
        handler.handleEventAsync(id(), threadReply("100.1", "100.5", "first"), BOT, origin());
        handler.handleEventAsync(id(), threadReply("100.1", "100.6", "second"), BOT, origin());

        verify(router, after(700).never()).resolveTarget(any(), any(), any());
        verify(conversationService, never()).startConversation(any(), any(), any(), any());
        assertEquals(2.0, meters.counter("eddi.slack.thread.replies.ignored").count());
    }

    @Test
    @DisplayName("a thread the bot was mentioned in (it holds the thread lock) is answered")
    void threadWithLock_isAnswered() throws Exception {
        when(router.resolveThreadTarget("slack", CHANNEL, "100.1")).thenReturn(locked());

        handler.handleEventAsync(id(), threadReply("100.1", "100.5", "and the second part?"), BOT, origin());

        verify(conversationService, timeout(2000)).startConversation(any(), eq(AGENT), anyString(), any());
        verify(slackApi, timeout(2000)).postMessage(anyString(), eq(CHANNEL), eq("100.1"), anyString());
        assertEquals(0.0, meters.counter("eddi.slack.thread.replies.ignored").count());
    }

    @Test
    @DisplayName("after a restart the lock is gone, but the user's own conversation mapping in the thread still counts")
    void threadWithOnlyAMapping_isAnsweredAndRelocked() throws Exception {
        // The lock is in memory (24 h); the mapping is in the database.
        when(router.resolveThreadTarget("slack", CHANNEL, "100.1")).thenReturn(null, locked());
        String intent = "channel:slack:" + CHANNEL + ":" + AGENT + ":100.1";
        when(userConversationStore.readUserConversation(intent, "slack:T1:U0ALICE"))
                .thenReturn(new UserConversation(intent, "slack:T1:U0ALICE", Deployment.Environment.production, AGENT,
                        "conv-1"));

        handler.handleEventAsync(id(), threadReply("100.1", "100.5", "continuing"), BOT, origin());

        verify(slackApi, timeout(2000)).postMessage(anyString(), eq(CHANNEL), eq("100.1"), anyString());
        // The write-back is the lock written BETWEEN the participation check's read of
        // the lock and routing's read of it — routing's own lock comes after both.
        InOrder order = inOrder(router);
        order.verify(router).resolveThreadTarget("slack", CHANNEL, "100.1");
        order.verify(router).lockThreadTarget(eq("slack"), eq(CHANNEL), eq("100.1"), any(ChannelTarget.class));
        order.verify(router).resolveThreadTarget("slack", CHANNEL, "100.1");
        order.verify(router).lockThreadTarget(eq("slack"), eq(CHANNEL), eq("100.1"), any(ChannelTarget.class));
    }

    @Test
    @DisplayName("a thread with a lock reads the lock once per reply: routing reuses the participation check's read")
    void threadWithLock_readsTheLockOnce() throws Exception {
        when(router.resolveThreadTarget("slack", CHANNEL, "100.1")).thenReturn(locked());

        handler.handleEventAsync(id(), threadReply("100.1", "100.5", "next"), BOT, origin());

        verify(slackApi, timeout(2000)).postMessage(anyString(), eq(CHANNEL), eq("100.1"), anyString());
        verify(router, times(1)).resolveThreadTarget("slack", CHANNEL, "100.1");
    }

    // ─── Cost of an ignored reply ───

    @Test
    @DisplayName("the second and later ignored replies of a user in a thread read no mapping from the database")
    void repeatedIgnoredReplies_readTheDatabaseOnce() throws Exception {
        handler.handleEventAsync(id(), threadReply("100.1", "100.5", "first"), BOT, origin());
        verify(userConversationStore, timeout(2000).times(2)).readUserConversation(anyString(), anyString());
        waitFor(() -> caches.get(SlackEventHandler.NON_PARTICIPATION_CACHE).size() == 1);

        handler.handleEventAsync(id(), threadReply("100.1", "100.6", "second"), BOT, origin());
        handler.handleEventAsync(id(), threadReply("100.1", "100.7", "third"), BOT, origin());

        // One read per channel target plus the follow-up mapping, for the first reply
        // only.
        verify(userConversationStore, after(700).times(2)).readUserConversation(anyString(), anyString());
        verify(router, times(3)).resolveThreadTarget("slack", CHANNEL, "100.1");
        assertEquals(3.0, meters.counter("eddi.slack.thread.replies.ignored").count());
        assertEquals("slack-thread-nonparticipants", SlackEventHandler.NON_PARTICIPATION_CACHE);
    }

    @Test
    @DisplayName("a store error is not remembered as 'no part in the thread'")
    void storeErrorIsNotCachedAsNonParticipation() throws Exception {
        when(userConversationStore.readUserConversation(anyString(), anyString()))
                .thenThrow(new IllegalStateException("db down"))
                .thenReturn(null);

        handler.handleEventAsync(id(), threadReply("100.1", "100.5", "first"), BOT, origin());
        verify(userConversationStore, timeout(2000).times(2)).readUserConversation(anyString(), anyString());
        handler.handleEventAsync(id(), threadReply("100.1", "100.6", "second"), BOT, origin());

        verify(userConversationStore, timeout(2000).times(4)).readUserConversation(anyString(), anyString());
    }

    @Test
    @DisplayName("a user's non-participation is forgotten when a mapping for them in the thread is created")
    void newMappingForgetsNonParticipation() throws Exception {
        handler.handleEventAsync(id(), threadReply("100.1", "100.5", "first"), BOT, origin());
        verify(userConversationStore, timeout(2000).times(2)).readUserConversation(anyString(), anyString());
        waitFor(() -> caches.get(SlackEventHandler.NON_PARTICIPATION_CACHE).size() == 1);
        var negatives = caches.get(SlackEventHandler.NON_PARTICIPATION_CACHE);

        // The bot is then mentioned in the thread: routing creates the user's mapping.
        when(router.resolveTarget("slack", CHANNEL, "now you")).thenReturn(locked());
        handler.handleEventAsync(id(), mentionEvent("100.1", "100.8", "<@UBOT> now you"), BOT, origin());

        verify(userConversationStore, timeout(2000)).createUserConversation(any());
        waitFor(negatives::isEmpty);
    }

    // ─── Group follow-ups across nodes and restarts (S4) ───

    @Test
    @DisplayName("without the node-local listener, the user's own follow-up mapping keeps a group thread alive")
    void followUpMappingWithoutListener_isAnsweredByThatAgent() throws Exception {
        String intent = "channel:followup:" + CHANNEL + ":100.1";
        when(userConversationStore.readUserConversation(intent, "slack:T1:U0ALICE"))
                .thenReturn(new UserConversation(intent, "slack:T1:U0ALICE", Deployment.Environment.production, "agent-member",
                        "conv-fu"));
        when(conversationService.getConversationState("conv-fu")).thenReturn(ConversationState.READY);

        handler.handleEventAsync(id(), threadReply("100.1", "100.5", "and the risks?"), BOT, origin());

        verify(conversationService, timeout(2000)).say(eq("conv-fu"), any(), any(), any(), any(), anyBoolean(), any());
        verify(slackApi, timeout(2000)).postMessage(eq("Bearer xoxb-a"), eq(CHANNEL), eq("100.1"), anyString());
        verify(router, never()).resolveTarget(any(), any(), any());
        assertEquals(0.0, meters.counter("eddi.slack.thread.replies.ignored").count());
    }

    @Test
    @DisplayName("a stored follow-up is not continued for an event another app signed")
    void followUpMappingFromAnotherApp_isRefused() throws Exception {
        String intent = "channel:followup:" + CHANNEL + ":100.1";
        when(userConversationStore.readUserConversation(intent, "slack:T1:U0ALICE"))
                .thenReturn(new UserConversation(intent, "slack:T1:U0ALICE", Deployment.Environment.production, "agent-member",
                        "conv-fu"));

        handler.handleEventAsync(id(), threadReply("100.1", "100.5", "and the risks?"), BOT,
                new SlackEventHandler.EventOrigin("T1", null, "secret-other"));

        verify(conversationService, after(700).never()).say(any(), any(), any(), any(), any(), anyBoolean(), any());
    }

    // ─── The bot's own messages ───

    @Test
    @DisplayName("an event whose sender is this bot's own user — without a bot_id — runs no turn")
    void selfMessage_isIgnored() throws Exception {
        when(router.resolveThreadTarget("slack", CHANNEL, "100.1")).thenReturn(locked());
        var event = threadReply("100.1", "100.5", "my own answer");
        event.put("user", BOT);

        handler.handleEventAsync(id(), event, BOT, origin());

        verify(conversationService, after(700).never()).say(any(), any(), any(), any(), any(), anyBoolean(), any());
        verify(router, never()).resolveThreadTarget(any(), any(), any());
    }

    // ─── One message, one turn ───

    @Test
    @DisplayName("the message key is scoped by the pinned workspace")
    void messageKeyIsScopedByWorkspace() {
        assertEquals("msg:T1:C1:100.5",
                SlackEventHandler.messageDedupKey(new SlackEventHandler.EventOrigin("T1", null, "s"), "C1", "100.5"));
        assertEquals("msg:-:C1:100.5",
                SlackEventHandler.messageDedupKey(new SlackEventHandler.EventOrigin(null, null, "s"), "C1", "100.5"));
    }

    @Test
    @DisplayName("a thread under a group discussion's agent message counts as one the bot is in")
    void groupDiscussionThread_isAnswered() throws Exception {
        var listener = mock(SlackGroupDiscussionListener.class);
        when(listener.getAgentIdForMessageTs("100.1")).thenReturn(AGENT);
        when(listener.getAgentContext(AGENT)).thenReturn(new SlackGroupDiscussionListener.AgentContext(AGENT, "Alice",
                "contribution", "", "question", "gc-1"));
        caches.computeIfAbsent("slack-group-listeners", k -> new FakeCache<>()).put("100.1",
                new SlackEventHandler.GroupFollowUp(listener, new ResolvedTarget(null, null, integration, null, null),
                        CHANNEL));

        handler.handleEventAsync(id(), threadReply("100.1", "100.5", "why that choice?"), BOT, origin());

        verify(conversationService, timeout(2000)).startConversation(any(), eq(AGENT), anyString(), any());
    }

    @Test
    @DisplayName("a direct message is answered, thread or not")
    void directMessage_isAnswered() throws Exception {
        when(router.resolveDefaultForDm("slack", "hi there", "int-a"))
                .thenReturn(new ResolvedTarget(target(), "hi there", integration, null, null));
        var event = threadReply("100.1", "100.5", "hi there");
        event.put("channel", "D0DM");
        event.put("channel_type", "im");
        event.remove("thread_ts");

        // A DM channel is owned by nobody: the webhook names the integration that
        // signed it.
        handler.handleEventAsync(id(), event, BOT, new SlackEventHandler.EventOrigin("T1", "int-a", SECRET));

        verify(conversationService, timeout(2000)).startConversation(any(), eq(AGENT), anyString(), any());
        assertEquals(0.0, meters.counter("eddi.slack.thread.replies.ignored").count());
    }

    @Test
    @DisplayName("an unmentioned top-level channel message is never answered")
    void topLevelMessage_isIgnored() throws Exception {
        var event = threadReply("100.1", "100.1", "just chatting among humans");
        event.remove("thread_ts");

        handler.handleEventAsync(id(), event, BOT, origin());

        verify(router, after(700).never()).resolveTarget(any(), any(), any());
        verify(conversationService, never()).startConversation(any(), any(), any(), any());
    }

    // ─── One mention predicate ───

    @Test
    @DisplayName("a mid-sentence mention of the bot in a thread: the message event steps aside, app_mention answers once")
    void midSentenceMention_messageThenAppMention_oneTurn() throws Exception {
        when(router.resolveThreadTarget("slack", CHANNEL, "100.1")).thenReturn(locked());
        when(router.resolveTarget("slack", CHANNEL, "thanks <@UBOT> can you check")).thenReturn(locked());
        String text = "thanks <@UBOT> can you check";

        handler.handleEventAsync(id(), threadReply("100.1", "100.5", text), BOT, origin());
        handler.handleEventAsync(id(), mentionEvent("100.1", "100.5", text), BOT, origin());

        verify(conversationService, timeout(2000)).startConversation(any(), eq(AGENT), anyString(), any());
        verify(conversationService, after(700).times(1)).startConversation(any(), any(), any(), any());
        verify(slackApi, times(1)).postMessage(anyString(), eq(CHANNEL), eq("100.1"), anyString());
    }

    @Test
    @DisplayName("the same pair delivered in the other order is one turn as well")
    void midSentenceMention_appMentionThenMessage_oneTurn() throws Exception {
        when(router.resolveThreadTarget("slack", CHANNEL, "100.1")).thenReturn(locked());
        when(router.resolveTarget("slack", CHANNEL, "thanks <@UBOT> can you check")).thenReturn(locked());
        String text = "thanks <@UBOT> can you check";

        handler.handleEventAsync(id(), mentionEvent("100.1", "100.5", text), BOT, origin());
        handler.handleEventAsync(id(), threadReply("100.1", "100.5", text), BOT, origin());

        verify(conversationService, timeout(2000)).startConversation(any(), eq(AGENT), anyString(), any());
        verify(conversationService, after(700).times(1)).startConversation(any(), any(), any(), any());
    }

    @Test
    @DisplayName("without the bot's id in the envelope the same ts still yields one turn (the net under the predicate)")
    void sameTimestamp_withoutBotId_oneTurn() throws Exception {
        when(router.resolveThreadTarget("slack", CHANNEL, "100.1")).thenReturn(locked());
        when(router.resolveTarget(any(), any(), any())).thenReturn(locked());

        handler.handleEventAsync(id(), mentionEvent("100.1", "100.5", "hello <@UBOT>"), null, origin());
        handler.handleEventAsync(id(), mentionEvent("100.1", "100.5", "hello <@UBOT>"), null, origin());

        verify(conversationService, timeout(2000)).startConversation(any(), eq(AGENT), anyString(), any());
        verify(conversationService, after(700).times(1)).startConversation(any(), any(), any(), any());
    }

    @Test
    @DisplayName("a reply that starts with another user's mention is not a mention of the bot: processed when the bot is in the thread")
    void otherUsersMention_inABotThread_isProcessed() throws Exception {
        when(router.resolveThreadTarget("slack", CHANNEL, "100.1")).thenReturn(locked());

        handler.handleEventAsync(id(), threadReply("100.1", "100.5", "<@U0CAROL> look at this"), BOT, origin());

        verify(conversationService, timeout(2000)).startConversation(any(), eq(AGENT), anyString(), any());
    }

    @Test
    @DisplayName("...and ignored when the bot is not in the thread")
    void otherUsersMention_inAHumanThread_isIgnored() throws Exception {
        handler.handleEventAsync(id(), threadReply("100.1", "100.5", "<@U0CAROL> look at this"), BOT, origin());

        verify(router, after(700).never()).resolveTarget(any(), any(), any());
        verify(conversationService, never()).startConversation(any(), any(), any(), any());
    }

    @Test
    @DisplayName("an app_mention that opens with another user's mention keeps it in the text the agent gets")
    void stripBotMention_onlyStripsTheBotsOwn() {
        assertEquals("what is EDDI?", SlackEventHandler.stripBotMention("<@UBOT> what is EDDI?", BOT));
        assertEquals("what is EDDI?", SlackEventHandler.stripBotMention("<@UBOT|eddi>   what is EDDI?", BOT));
        assertEquals("<@U0CAROL> what is EDDI?", SlackEventHandler.stripBotMention("<@U0CAROL> what is EDDI?", BOT));
        assertEquals("ask <@UBOT> now", SlackEventHandler.stripBotMention("ask <@UBOT> now", BOT));
        // Without the bot's id, the old behaviour: any leading mention goes.
        assertEquals("what is EDDI?", SlackEventHandler.stripBotMention("<@U0CAROL> what is EDDI?", null));
    }

    // ─── Helpers ───

    private static void waitFor(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not reached within 5 s");
            }
            Thread.sleep(10);
        }
    }

    private ResolvedTarget locked() {
        return new ResolvedTarget(target(), null, integration, null, null);
    }

    private static ChannelTarget target() {
        var target = new ChannelTarget();
        target.setName("default");
        target.setType(ChannelTarget.TargetType.AGENT);
        target.setTargetId(AGENT);
        return target;
    }

    private static SlackEventHandler.EventOrigin origin() {
        return new SlackEventHandler.EventOrigin("T1", null, SECRET);
    }

    private static String id() {
        return "Ev" + IDS.incrementAndGet();
    }

    private static Map<String, Object> threadReply(String threadTs, String ts, String text) {
        var event = new HashMap<String, Object>();
        event.put("type", "message");
        event.put("channel", CHANNEL);
        event.put("channel_type", "channel");
        event.put("thread_ts", threadTs);
        event.put("ts", ts);
        event.put("user", "U0ALICE");
        event.put("text", text);
        return event;
    }

    private static Map<String, Object> mentionEvent(String threadTs, String ts, String text) {
        var event = threadReply(threadTs, ts, text);
        event.put("type", "app_mention");
        return event;
    }

    private static SimpleConversationMemorySnapshot answer(String text) {
        var snapshot = new SimpleConversationMemorySnapshot();
        snapshot.setConversationState(ConversationState.READY);
        var output = new ConversationOutput();
        output.put("output", List.of(text));
        snapshot.getConversationOutputs().add(output);
        return snapshot;
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
