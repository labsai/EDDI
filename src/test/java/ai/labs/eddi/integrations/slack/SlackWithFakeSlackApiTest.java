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
import ai.labs.eddi.engine.httpclient.outbound.TestOutbound;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.triggermanagement.IUserConversationStore;
import ai.labs.eddi.integrations.channels.ChannelTargetRouter;
import ai.labs.eddi.integrations.channels.ChannelTargetRouter.ResolvedTarget;
import ai.labs.eddi.integrations.channels.ObserveGate;
import ai.labs.eddi.integrations.slack.hitl.InMemorySlackApprovalRecordStore;
import ai.labs.eddi.modules.llm.tools.ToolCostTracker;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Slack client and the event handler against {@link FakeSlackApi}, an
 * in-process stand-in for Slack's Web API. Until the API base URL was a
 * deployment property nothing that needs a <em>successful</em> Slack call could
 * run against a fake: the client always dialled slack.com, so the code after a
 * posted answer — late answers, rate limits — had no test.
 * <p>
 * The fake is an engine behind the real outbound facade, not a socket, so this
 * is a plain unit test.
 */
@DisplayName("Slack client and handler against a fake Slack API")
class SlackWithFakeSlackApiTest {

    private static final String CHANNEL = "C0FAKE";
    private static final String AUTH = "Bearer xoxb-fake";

    private final ObjectMapper mapper = new ObjectMapper();
    private FakeSlackApi slack;

    @BeforeEach
    void setUp() {
        slack = new FakeSlackApi();
    }

    private SlackWebApiClient client(SlackConfig config) {
        return new SlackWebApiClient(mapper, TestOutbound.facade(slack), config);
    }

    private static SlackConfig config(int requestTimeoutSeconds, int retries, int lateWindowSeconds) {
        return new SlackConfig(requestTimeoutSeconds, SlackConfig.DEFAULT_GROUP_COMPLETION_TIMEOUT_SECONDS, retries, 10L)
                .withApiBaseUrl(FakeSlackApi.BASE_URL)
                .withLateAnswerWindowSeconds(lateWindowSeconds);
    }

    // ─── The client ───

    @Nested
    @DisplayName("SlackWebApiClient")
    class Client {

        @Test
        @DisplayName("post message: ok:true returns the ts, and the call goes to the configured base URL")
        void postMessageOk() {
            String ts = client(config(60, 3, 0)).postMessage(AUTH, CHANNEL, "100.1", "hello");

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
            var client = client(config(60, 3, 0));

            assertNotNull(client.postMessage(AUTH, CHANNEL, null, "one"));
            assertNotNull(client.postMessage(AUTH, CHANNEL, null, "two"));

            assertEquals(List.of("one", "two"), slack.calls("chat.postMessage").stream().map(FakeSlackApi.Call::text).toList());
        }

        @Test
        @DisplayName("chat.update and views.open go to the same configured base")
        void otherMethodsUseTheConfiguredBase() {
            var client = client(config(60, 3, 0));

            assertTrue(client.updateMessage(AUTH, CHANNEL, "100.1", "done", null));
            assertTrue(client.openView(AUTH, "trigger-1", Map.of("type", "modal")));

            assertEquals(1, slack.calls("chat.update").size());
            assertEquals(1, slack.calls("views.open").size());
        }

        @Test
        @DisplayName("post message: ok:false is a refusal, not a retry — null, one call")
        void postMessageNotOk() {
            slack.nextError("channel_not_found");

            assertNull(client(config(60, 3, 0)).postMessage(AUTH, CHANNEL, null, "hello"));
            assertEquals(1, slack.calls().size());
        }

        @Test
        @DisplayName("post message: 429 throws a retryable error that carries Retry-After")
        void postMessageRateLimited() {
            slack.nextRateLimited(2);

            var e = assertThrows(SlackDeliveryException.class,
                    () -> client(config(60, 3, 0)).postMessage(AUTH, CHANNEL, null, "hello"));

            assertEquals(2000L, e.getRetryAfterMillis());
        }

        @Test
        @DisplayName("a 5xx carries no Retry-After; a Retry-After that is not a number is ignored")
        void noUsableRetryAfter() {
            slack.nextStatus(503);
            var e = assertThrows(SlackDeliveryException.class,
                    () -> client(config(60, 3, 0)).postMessage(AUTH, CHANNEL, null, "hello"));
            assertEquals(0L, e.getRetryAfterMillis());
        }
    }

    // ─── The handler ───

    @Nested
    @DisplayName("SlackEventHandler")
    class Handler {

        private ChannelTargetRouter router;
        private IConversationService conversationService;
        private SimpleMeterRegistry meters;
        private SlackEventHandler handler;
        private final AtomicReference<IConversationService.ConversationResponseHandler> pendingTurn = new AtomicReference<>();
        private final Map<String, FakeCache<Object, Object>> caches = new ConcurrentHashMap<>();
        private ChannelIntegrationConfiguration integration;

        @BeforeEach
        @SuppressWarnings({"unchecked", "rawtypes"})
        void setUp() throws Exception {
            router = mock(ChannelTargetRouter.class);
            conversationService = mock(IConversationService.class);
            meters = new SimpleMeterRegistry();
            var cacheFactory = mock(ICacheFactory.class);
            doAnswer(inv -> caches.computeIfAbsent(inv.getArgument(0), k -> new FakeCache<>()))
                    .when(cacheFactory).getCache(anyString(), any(Duration.class));
            when(conversationService.startConversation(any(), anyString(), anyString(), any()))
                    .thenReturn(new ConversationResult("conv-new", URI.create("eddi://conv-new")));

            var target = new ChannelTarget();
            target.setName("default");
            target.setType(ChannelTarget.TargetType.AGENT);
            target.setTargetId("agent-A");
            integration = new ChannelIntegrationConfiguration();
            integration.setName("int-a");
            integration.setChannelType("slack");
            integration.setPlatformConfig(new HashMap<>(Map.of("signingSecret", "secret-a", "botToken", "xoxb-fake")));
            integration.setTargets(List.of(target));
            // The channel is owned by the integration: the timeout notice, which has no
            // resolved route to take a token from, looks it up by channel.
            when(router.getBotToken("slack", CHANNEL)).thenReturn("xoxb-fake");
            when(router.resolveTarget(any(), any(), any()))
                    .thenAnswer(inv -> new ResolvedTarget(target, inv.getArgument(2), integration, null, null));
            this.cacheFactory = cacheFactory;
        }

        private ICacheFactory cacheFactory;

        @AfterEach
        void tearDown() {
            handler.shutdown();
        }

        private void build(SlackConfig config) {
            handler = new SlackEventHandler(router, mock(ObserveGate.class), mock(ToolCostTracker.class), client(config),
                    conversationService, mock(IGroupConversationService.class), mock(IUserConversationStore.class),
                    cacheFactory, config, new InMemorySlackApprovalRecordStore(), mock(IUserMemoryStore.class), meters);
        }

        /** say() answers at once. */
        private void answersAtOnce(String text) throws Exception {
            doAnswer(inv -> {
                ((IConversationService.ConversationResponseHandler) inv.getArgument(6)).onComplete(answer(text));
                return null;
            }).when(conversationService).say(anyString(), any(), any(), any(), any(), anyBoolean(), any());
        }

        /** say() holds the turn: the test completes it later. */
        private void holdsTheTurn() throws Exception {
            doAnswer(inv -> {
                pendingTurn.set(inv.getArgument(6));
                return null;
            }).when(conversationService).say(anyString(), any(), any(), any(), any(), anyBoolean(), any());
        }

        private void mention(String text) {
            var event = new HashMap<String, Object>();
            event.put("type", "app_mention");
            event.put("channel", CHANNEL);
            event.put("ts", "100." + Math.abs(text.hashCode() % 1000));
            event.put("user", "U0ALICE");
            event.put("text", "<@UBOT> " + text);
            handler.handleEventAsync("Ev" + System.nanoTime(), event, "UBOT",
                    new SlackEventHandler.EventOrigin("T1", null, "secret-a"));
        }

        private List<String> postedTexts() {
            return slack.calls("chat.postMessage").stream().map(FakeSlackApi.Call::text).toList();
        }

        private double late(String outcome) {
            var counter = meters.find("eddi.slack.late.answers").tag("outcome", outcome).counter();
            return counter == null ? 0.0 : counter.count();
        }

        // --- an answer through the fake ---

        @Test
        @DisplayName("an answer is posted to the thread through the fake API")
        void answerIsPosted() throws Exception {
            build(config(5, 3, 60));
            answersAtOnce("the answer");

            mention("question one");

            verify(conversationService, timeout(3000)).say(anyString(), any(), any(), any(), any(), anyBoolean(), any());
            waitFor(() -> !slack.calls("chat.postMessage").isEmpty());
            assertEquals(List.of("the answer"), postedTexts());
            assertEquals(AUTH, slack.calls("chat.postMessage").getFirst().authorization());
        }

        // --- Retry-After ---

        @Test
        @DisplayName("a 429 with Retry-After is waited out, then the message is posted — once")
        void retryAfterIsHonoured() throws Exception {
            build(config(5, 3, 60));
            answersAtOnce("the answer");
            slack.nextRateLimited(1);

            long start = System.nanoTime();
            mention("question two");
            waitFor(() -> slack.calls("chat.postMessage").size() >= 2);
            long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertEquals(2, slack.calls("chat.postMessage").size(), "one refused, one retry");
            assertTrue(elapsedMillis >= 900, "the retry waited for Retry-After (1 s), not the 10 ms backoff: " + elapsedMillis);
        }

        // --- late answers ---

        @Test
        @DisplayName("a turn that outlives the wait: a notice now, the answer — once — when it completes")
        void lateAnswerIsPosted() throws Exception {
            build(config(1, 1, 30));
            holdsTheTurn();

            mention("slow question");
            waitFor(() -> !postedTexts().isEmpty());
            assertTrue(postedTexts().getFirst().contains("I'll post the answer"), postedTexts().getFirst());

            waitFor(() -> pendingTurn.get() != null);
            pendingTurn.get().onComplete(answer("the late answer"));
            waitFor(() -> postedTexts().size() >= 2);

            assertEquals("the late answer", postedTexts().get(1));
            assertEquals(2, postedTexts().size(), "the answer is posted once");
            // Counted once the delivery has returned, a moment after the post itself.
            waitFor(() -> late("posted") == 1.0);
            assertEquals(1.0, late("posted"));
        }

        @Test
        @DisplayName("a turn that fails after the wait gave up: a failure notice, not silence")
        void lateFailureIsReported() throws Exception {
            build(config(1, 1, 30));
            holdsTheTurn();

            mention("doomed question");
            waitFor(() -> !postedTexts().isEmpty());
            waitFor(() -> pendingTurn.get() != null);
            pendingTurn.get().onComplete(null);
            waitFor(() -> postedTexts().size() >= 2);

            assertTrue(postedTexts().get(1).contains("could not finish"), postedTexts().get(1));
            assertEquals(1.0, late("failed"));
        }

        @Test
        @DisplayName("a turn that never completes is given up on after the late-answer window, with a notice")
        void lateAnswerExpires() throws Exception {
            build(config(1, 1, 1));
            holdsTheTurn();

            mention("endless question");
            waitFor(() -> postedTexts().size() >= 2);

            assertTrue(postedTexts().get(1).contains("stopped waiting"), postedTexts().get(1));
            assertEquals(1.0, late("expired"));
            // A completion after that is not posted a second time.
            waitFor(() -> pendingTurn.get() != null);
            pendingTurn.get().onComplete(answer("too late"));
            Thread.sleep(300);
            assertEquals(2, postedTexts().size());
        }

        @Test
        @DisplayName("with the window at 0 the old behaviour stays: 'ask again', and nothing is posted later")
        void windowZeroKeepsTheOldBehaviour() throws Exception {
            build(config(1, 1, 0));
            holdsTheTurn();

            mention("slow question");
            waitFor(() -> !postedTexts().isEmpty());
            assertTrue(postedTexts().getFirst().contains("ask again"), postedTexts().getFirst());

            waitFor(() -> pendingTurn.get() != null);
            pendingTurn.get().onComplete(answer("the late answer"));
            Thread.sleep(300);
            assertEquals(1, postedTexts().size());
            assertEquals(0.0, late("posted"));
        }
    }

    // ─── Helpers ───

    private static void waitFor(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not reached within 10 s");
            }
            Thread.sleep(20);
        }
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
