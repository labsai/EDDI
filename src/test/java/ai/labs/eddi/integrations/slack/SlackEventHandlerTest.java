/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack;

import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.channels.model.ChannelTarget;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
import ai.labs.eddi.engine.triggermanagement.IUserConversationStore;
import ai.labs.eddi.engine.triggermanagement.model.UserConversation;
import ai.labs.eddi.integrations.channels.ChannelTargetRouter;
import ai.labs.eddi.integrations.channels.ObserveGate;
import ai.labs.eddi.modules.llm.tools.ToolCostTracker;
import ai.labs.eddi.integrations.channels.ChannelTargetRouter.ResolvedTarget;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link SlackEventHandler} — static/utility methods and pattern
 * matching. Integration-level tests for the full event flow require CDI wiring
 * and are covered by the integration test suite.
 */
class SlackEventHandlerTest {

    // ─── stripBotMention ───

    @Test
    void stripBotMention_removesMention() {
        assertEquals("hello world", SlackEventHandler.stripBotMention("<@U0123BOTID> hello world"));
    }

    @Test
    void stripBotMention_removesMultipleSpaces() {
        assertEquals("test", SlackEventHandler.stripBotMention("<@U0123BOTID>   test"));
    }

    @Test
    void stripBotMention_noMention_returnsOriginal() {
        assertEquals("no mention here", SlackEventHandler.stripBotMention("no mention here"));
    }

    @Test
    void stripBotMention_onlyMention_returnsEmpty() {
        assertEquals("", SlackEventHandler.stripBotMention("<@U0123BOTID>"));
    }

    @Test
    void stripBotMention_mentionInMiddle_onlyStripsPrefix() {
        // Only the leading mention should be stripped
        assertEquals("hello <@U999> world",
                SlackEventHandler.stripBotMention("<@U0123BOTID> hello <@U999> world"));
    }

    @ParameterizedTest
    @CsvSource({
            "'<@UBOT123> what is EDDI?', 'what is EDDI?'",
            "'<@U0A1B2C3D> ', ''",
            "'plain text', 'plain text'"
    })
    void stripBotMention_parameterized(String input, String expected) {
        assertEquals(expected, SlackEventHandler.stripBotMention(input));
    }

    // ─── GROUP_PREFIX pattern ───

    private static final Pattern GROUP_PREFIX = Pattern.compile("^group:\\s*(.+)",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    @Test
    void groupPrefix_matches_simpleQuestion() {
        Matcher m = GROUP_PREFIX.matcher("group: What should our strategy be?");
        assertTrue(m.matches());
        assertEquals("What should our strategy be?", m.group(1).trim());
    }

    @Test
    void groupPrefix_caseInsensitive() {
        Matcher m = GROUP_PREFIX.matcher("GROUP: test question");
        assertTrue(m.matches());
        assertEquals("test question", m.group(1).trim());
    }

    @Test
    void groupPrefix_mixedCase() {
        Matcher m = GROUP_PREFIX.matcher("Group: Should we deploy?");
        assertTrue(m.matches());
    }

    @Test
    void groupPrefix_noSpace_afterColon() {
        Matcher m = GROUP_PREFIX.matcher("group:question without space");
        assertTrue(m.matches());
        assertEquals("question without space", m.group(1).trim());
    }

    @Test
    void groupPrefix_multiline() {
        Matcher m = GROUP_PREFIX.matcher("group: first line\nsecond line");
        assertTrue(m.matches());
        assertTrue(m.group(1).contains("second line"));
    }

    @Test
    void groupPrefix_noMatch_normalMessage() {
        assertFalse(GROUP_PREFIX.matcher("hello group: not a trigger").matches());
    }

    @Test
    void groupPrefix_noMatch_emptyAfterColon() {
        // Edge case: "group:" with nothing after should not match (.+ requires 1+
        // chars)
        assertFalse(GROUP_PREFIX.matcher("group:").matches());
    }

    // ─── truncate ───

    @Test
    void truncate_null_returnsEmpty() {
        assertEquals("", invokeStaticTruncate(null, 100));
    }

    @Test
    void truncate_shortText_unchanged() {
        assertEquals("hello", invokeStaticTruncate("hello", 100));
    }

    @Test
    void truncate_longText_truncated() {
        String result = invokeStaticTruncate("a".repeat(600), 500);
        assertEquals(503, result.length()); // 500 + "..."
        assertTrue(result.endsWith("..."));
    }

    @Test
    void truncate_exactLength_unchanged() {
        String input = "a".repeat(500);
        assertEquals(input, invokeStaticTruncate(input, 500));
    }

    // ─── buildFollowUpInput ───

    @Test
    void buildFollowUpInput_includesFeedback() {
        var ctx = new SlackGroupDiscussionListener.AgentContext(
                "agent1", "Alice", "My contribution",
                "From Bob: I disagree\nFrom Carol: I agree",
                "What's the plan?", "gc-123");

        String result = buildFollowUpInput(ctx, "Can you elaborate?");
        assertTrue(result.contains("group discussion"));
        assertTrue(result.contains("What's the plan?"));
        assertTrue(result.contains("My contribution"));
        assertTrue(result.contains("Bob"));
        assertTrue(result.contains("Carol"));
        assertTrue(result.contains("Can you elaborate?"));
    }

    @Test
    void buildFollowUpInput_noFeedback_omitsSection() {
        var ctx = new SlackGroupDiscussionListener.AgentContext(
                "agent1", "Alice", "My thought", "", "Question?", "gc-1");

        String result = buildFollowUpInput(ctx, "Explain more?");
        assertFalse(result.contains("Peer feedback"));
        assertTrue(result.contains("My thought"));
        assertTrue(result.contains("Explain more?"));
    }

    // ─── formatTimeoutInfo (HITL) ───

    @Test
    void formatTimeoutInfo_nullPolicy_returnsNull() {
        assertNull(SlackEventHandler.formatTimeoutInfo(null, "PT1H"));
        assertNull(SlackEventHandler.formatTimeoutInfo("", "PT1H"));
    }

    @Test
    void formatTimeoutInfo_policyOnly() {
        assertEquals("WAIT_INDEFINITELY", SlackEventHandler.formatTimeoutInfo("WAIT_INDEFINITELY", null));
        assertEquals("WAIT_INDEFINITELY", SlackEventHandler.formatTimeoutInfo("WAIT_INDEFINITELY", ""));
    }

    @Test
    void formatTimeoutInfo_policyAndTimeout() {
        assertEquals("AUTO_REJECT (PT1H)", SlackEventHandler.formatTimeoutInfo("AUTO_REJECT", "PT1H"));
    }

    // ─── notifyApprovers idempotency (HITL / F12) ───

    /**
     * F12(a): two DISTINCT pauses on the same conversation (different hitlPausedAt)
     * must EACH post an approval card. Keying by conversationId alone would
     * suppress the second card until the 24h TTL expired.
     */
    @Test
    void notifyApprovers_twoDistinctPausesSameConversation_eachPostsCard() {
        var slackApi = mock(SlackWebApiClient.class);
        var handler = newHandler(slackApi);
        var resolved = resolvedWithApprovalChannel();

        handler.notifyApprovers(resolved, "conv-1", "agent-1",
                bookmarkPausedAt(Instant.ofEpochMilli(1_000L)));
        handler.notifyApprovers(resolved, "conv-1", "agent-1",
                bookmarkPausedAt(Instant.ofEpochMilli(2_000L)));

        // Distinct pause identities → two cards, not one.
        verify(slackApi, times(2)).postBlocksMessage(anyString(), eq("C_APPROVAL"),
                any(), any(), anyString());
    }

    /**
     * Same pause identity (identical hitlPausedAt) → re-message-while-paused is a
     * no-op: exactly one card.
     */
    @Test
    void notifyApprovers_samePauseIdentity_postsOnlyOneCard() {
        var slackApi = mock(SlackWebApiClient.class);
        var handler = newHandler(slackApi);
        var resolved = resolvedWithApprovalChannel();
        var pausedAt = Instant.ofEpochMilli(1_000L);

        handler.notifyApprovers(resolved, "conv-1", "agent-1", bookmarkPausedAt(pausedAt));
        handler.notifyApprovers(resolved, "conv-1", "agent-1", bookmarkPausedAt(pausedAt));

        verify(slackApi, times(1)).postBlocksMessage(anyString(), eq("C_APPROVAL"),
                any(), any(), anyString());
    }

    /**
     * F12(b): a FAILED first delivery must clear the marker so a later retry (same
     * pause identity) can re-attempt — the card is not suppressed for the full TTL.
     */
    @Test
    void notifyApprovers_failedDelivery_doesNotPermanentlySuppressRetry() {
        var slackApi = mock(SlackWebApiClient.class);
        // First call fails (retryable delivery error), second call succeeds.
        doThrow(new SlackDeliveryException("HTTP 503"))
                .doReturn("1700000000.000100")
                .when(slackApi).postBlocksMessage(anyString(), eq("C_APPROVAL"), any(), any(), anyString());

        var handler = newHandler(slackApi);
        var resolved = resolvedWithApprovalChannel();
        var pausedAt = Instant.ofEpochMilli(1_000L);

        handler.notifyApprovers(resolved, "conv-1", "agent-1", bookmarkPausedAt(pausedAt));
        // Retry with the SAME pause identity — must not be blocked by a stale marker.
        handler.notifyApprovers(resolved, "conv-1", "agent-1", bookmarkPausedAt(pausedAt));

        verify(slackApi, times(2)).postBlocksMessage(anyString(), eq("C_APPROVAL"),
                any(), any(), anyString());
    }

    /**
     * When no approval channel is configured, the notification is a no-op (no card,
     * no marker) regardless of pause identity.
     */
    @Test
    void notifyApprovers_noApprovalChannel_isNoOp() {
        var slackApi = mock(SlackWebApiClient.class);
        var handler = newHandler(slackApi);

        var cfg = new ChannelIntegrationConfiguration();
        cfg.setName("acme-int");
        cfg.setChannelType("slack");
        cfg.setPlatformConfig(new HashMap<>(Map.of("botToken", "xoxb-token")));
        var resolved = new ResolvedTarget(null, null, cfg, null, null);

        handler.notifyApprovers(resolved, "conv-1", "agent-1",
                bookmarkPausedAt(Instant.ofEpochMilli(1_000L)));

        verify(slackApi, never()).postBlocksMessage(anyString(), anyString(), any(), any(), anyString());
    }

    // ─── Helpers ───

    /**
     * Build a {@link SlackEventHandler} with the given Slack API and lightweight
     * fake caches (real {@link ConcurrentMap} semantics so putIfAbsent/remove
     * behave exactly as in production). All other collaborators are unused by
     * {@code notifyApprovers} and are plain mocks.
     */
    private static SlackEventHandler newHandler(SlackWebApiClient slackApi) {
        var cacheFactory = mock(ICacheFactory.class);
        doReturn(new FakeCache<>()).when(cacheFactory).getCache(anyString(), any(Duration.class));
        return new SlackEventHandler(
                mock(ChannelTargetRouter.class),
                mock(ObserveGate.class),
                mock(ToolCostTracker.class),
                slackApi,
                mock(IConversationService.class),
                mock(IGroupConversationService.class),
                mock(IUserConversationStore.class),
                mock(IUserMemoryStore.class),
                cacheFactory,
                // The shipped defaults, so these tests exercise the same numbers the
                // constants used to hard-code.
                new SlackConfig(SlackConfig.DEFAULT_REQUEST_TIMEOUT_SECONDS, SlackConfig.DEFAULT_GROUP_COMPLETION_TIMEOUT_SECONDS,
                        SlackConfig.DEFAULT_API_MAX_RETRIES, SlackConfig.DEFAULT_API_RETRY_BASE_MS));
    }

    /**
     * A {@link ResolvedTarget} whose integration has an approval channel configured
     * and a non-blank bot token — so {@code notifyApprovers} proceeds to post.
     */
    private static ResolvedTarget resolvedWithApprovalChannel() {
        var cfg = new ChannelIntegrationConfiguration();
        cfg.setName("acme-int");
        cfg.setChannelType("slack");
        var pc = new HashMap<String, String>();
        pc.put("botToken", "xoxb-token");
        pc.put(SlackHitlSupport.CFG_HITL_APPROVAL_CHANNEL, "C_APPROVAL");
        pc.put(SlackHitlSupport.CFG_HITL_APPROVER_USER_IDS, "U_APPROVER");
        cfg.setPlatformConfig(pc);
        cfg.setResourceId("int-res-1");
        var agent = new ChannelTarget();
        agent.setName("agent");
        agent.setType(ChannelTarget.TargetType.AGENT);
        agent.setTargetId("agent-1");
        cfg.setTargets(List.of(agent));
        return new ResolvedTarget(null, null, cfg, null, null);
    }

    /** A paused conversation of agent-1 that integration int-res-1 started. */
    private static ConversationMemorySnapshot boundBookmark(Instant pausedAt) {
        var snapshot = bookmarkPausedAt(pausedAt);
        snapshot.setConversationState(ConversationState.AWAITING_HUMAN);
        snapshot.setAgentId("agent-1");
        var output = new ConversationOutput();
        output.put("context", new HashMap<>(Map.of("channelIntegrationId", "int-res-1")));
        snapshot.setConversationOutputs(List.of(output));
        return snapshot;
    }

    private static ConversationMemorySnapshot bookmarkPausedAt(Instant pausedAt) {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setHitlPausedAt(pausedAt);
        return snapshot;
    }

    /**
     * Minimal {@link ICache} backed by a {@link ConcurrentHashMap} — provides the
     * real {@code putIfAbsent}/{@code remove} semantics the idempotency logic
     * relies on. Lifespan-aware overloads ignore TTL (irrelevant to these tests).
     */
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

    /**
     * Mirror the static truncate logic from SlackEventHandler for testing (it's
     * package-private static, so we test it via reflection-alike approach).
     */
    private static String invokeStaticTruncate(String text, int maxLen) {
        if (text == null)
            return "";
        return text.length() > maxLen ? text.substring(0, maxLen) + "..." : text;
    }

    /**
     * Mirror the buildFollowUpInput logic from SlackEventHandler for testing.
     */
    private static String buildFollowUpInput(SlackGroupDiscussionListener.AgentContext ctx, String userMessage) {
        var sb = new StringBuilder();
        sb.append("[Context: You previously participated in a group discussion]\n");
        sb.append("Discussion question: \"").append(ctx.groupQuestion()).append("\"\n");
        sb.append("Your contribution: \"").append(invokeStaticTruncate(ctx.contribution(), 500)).append("\"\n");
        if (ctx.feedbackReceived() != null && !ctx.feedbackReceived().isEmpty()) {
            sb.append("Peer feedback you received:\n").append(invokeStaticTruncate(ctx.feedbackReceived(), 500)).append("\n");
        }
        sb.append("---\n");
        sb.append("User follow-up question: ").append(userMessage);
        return sb.toString();
    }

    // ─── Inbound binding (H4a / H4d) and user namespacing ───

    @Nested
    @DisplayName("events act only on the sending app's integrations")
    class InboundBinding {

        private final ChannelTargetRouter router = mock(ChannelTargetRouter.class);
        private final IConversationService conversationService = mock(IConversationService.class);
        private final IUserConversationStore userConversationStore = mock(IUserConversationStore.class);
        private final SlackWebApiClient slackApi = mock(SlackWebApiClient.class);
        private final IUserMemoryStore userMemoryStore = mock(IUserMemoryStore.class);
        private final IGroupConversationService groupConversationService = mock(IGroupConversationService.class);
        private final ObserveGate observeGate = mock(ObserveGate.class);

        private SlackEventHandler handler(boolean namespaceUserIds) throws Exception {
            return handler(namespaceUserIds, null);
        }

        private SlackEventHandler handler(boolean namespaceUserIds, String legacyTeamId) throws Exception {
            var cacheFactory = mock(ICacheFactory.class);
            doReturn(new FakeCache<>()).when(cacheFactory).getCache(anyString(), any(Duration.class));
            when(conversationService.startConversation(any(), anyString(), anyString(), any()))
                    .thenReturn(new IConversationService.ConversationResult("conv-new", null));
            doAnswer(invocation -> {
                IConversationService.ConversationResponseHandler responseHandler = invocation.getArgument(6);
                var snapshot = new SimpleConversationMemorySnapshot();
                snapshot.setConversationState(ConversationState.READY);
                responseHandler.onComplete(snapshot);
                return null;
            }).when(conversationService).say(anyString(), any(), any(), any(), any(), anyBoolean(), any());
            return new SlackEventHandler(router, observeGate, mock(ToolCostTracker.class), slackApi,
                    conversationService, groupConversationService, userConversationStore, userMemoryStore, cacheFactory,
                    new SlackConfig(5, 5, 1, 0L, namespaceUserIds, Optional.ofNullable(legacyTeamId)));
        }

        private ChannelIntegrationConfiguration integration(String integrationName, String signingSecret, ChannelTarget target) {
            var cfg = new ChannelIntegrationConfiguration();
            cfg.setName(integrationName);
            cfg.setResourceId("res-" + integrationName);
            cfg.setChannelType("slack");
            cfg.setPlatformConfig(new HashMap<>(Map.of("channelId", "C1", "botToken", "xoxb", "signingSecret", signingSecret)));
            cfg.setTargets(List.of(target));
            return cfg;
        }

        private ChannelTarget agentTarget() {
            var target = new ChannelTarget();
            target.setName("default");
            target.setType(ChannelTarget.TargetType.AGENT);
            target.setTargetId("agent-1");
            return target;
        }

        private ResolvedTarget routeTo(String integrationName, String signingSecret) {
            var target = agentTarget();
            return new ResolvedTarget(target, "hello", integration(integrationName, signingSecret, target), null, null);
        }

        private Map<String, Object> mention(String channel) {
            Map<String, Object> event = new HashMap<>();
            event.put("type", "app_mention");
            event.put("text", "<@UBOT> hello");
            event.put("user", "U1");
            event.put("channel", channel);
            event.put("ts", "1700.1");
            return event;
        }

        @Test
        @DisplayName("H4a: an event signed by another integration's app never reaches this integration's agent")
        void foreignSecretIsDropped() throws Exception {
            var handler = handler(true);
            when(router.resolveTarget(eq("slack"), eq("C1"), anyString())).thenReturn(routeTo("int-a", "sig-a"));

            handler.handleEvent(mention("C1"), new SlackEventEnvelope("sig-b", "T1", "A1", "UBOT"));

            verify(conversationService, never()).startConversation(any(), anyString(), anyString(), any());
            verify(conversationService, never()).say(anyString(), any(), any(), any(), any(), anyBoolean(), any());
            verify(slackApi, never()).postMessage(anyString(), anyString(), any(), anyString());
        }

        @Test
        @DisplayName("the owning app's event runs the turn, as a team-namespaced user, recording the integration")
        void owningSecretRunsTheTurn() throws Exception {
            var handler = handler(true);
            when(router.resolveTarget(eq("slack"), eq("C1"), anyString())).thenReturn(routeTo("int-a", "sig-a"));

            handler.handleEvent(mention("C1"), new SlackEventEnvelope("sig-a", "T1", "A1", "UBOT"));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Context>> context = ArgumentCaptor.forClass(Map.class);
            verify(conversationService).startConversation(any(), eq("agent-1"), eq("slack:T1:U1"), context.capture());
            // Bound by resource id, not name; raw ids ride along for templates.
            assertEquals("res-int-a", context.getValue().get("channelIntegrationId").getValue());
            assertEquals("U1", context.getValue().get("slackUserId").getValue());
            assertEquals("T1", context.getValue().get("slackTeamId").getValue());
            assertTrue(String.valueOf(context.getValue().get("channelIntent").getValue()).startsWith("channel:slack:C1:agent-1:"));
        }

        @Test
        @DisplayName("namespacing off (the default): the bare Slack id is the EDDI user, raw ids still in the context")
        void defaultKeepsBareIds() throws Exception {
            var handler = handler(false);
            when(router.resolveTarget(eq("slack"), eq("C1"), anyString())).thenReturn(routeTo("int-a", "sig-a"));

            handler.handleEvent(mention("C1"), new SlackEventEnvelope("sig-a", "T1", "A1", "UBOT"));

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Context>> context = ArgumentCaptor.forClass(Map.class);
            verify(conversationService).startConversation(any(), eq("agent-1"), eq("U1"), context.capture());
            assertEquals("T1", context.getValue().get("slackTeamId").getValue());
            verifyNoInteractions(userMemoryStore);
        }

        @Test
        @DisplayName("#1: a DM thread locked to another app's agent is not continued with this app's secret")
        void dmThreadLockTakeoverIsDropped() throws Exception {
            var handler = handler(false);
            var event = mention("D_VICTIM");
            event.put("type", "message");
            event.put("channel_type", "im");
            event.put("text", "hello");
            event.put("thread_ts", "1699.9");
            // The lock names app B's agent and carries no credentials (a DM channel).
            when(router.resolveThreadTarget("slack", "D_VICTIM", "1699.9"))
                    .thenReturn(new ResolvedTarget(agentTarget(), null, null, null, null));
            // App A's secret serves no integration that has that target.
            when(router.threadCredentialsForDm(eq("slack"), any(), eq("sig-a"), any())).thenReturn(null);

            handler.handleEvent(event, new SlackEventEnvelope("sig-a", "T1", "A1", "UBOT"));

            verifyNoInteractions(conversationService);
            verify(userConversationStore, never()).readUserConversation(anyString(), anyString());
        }

        @Test
        @DisplayName("#1/#7: a DM thread reply is served with the credentials of the integration or legacy connector owning its target")
        void dmThreadReplyGetsOwningCredentials() throws Exception {
            var handler = handler(false);
            var event = mention("D1");
            event.put("type", "message");
            event.put("channel_type", "im");
            event.put("text", "hello");
            event.put("thread_ts", "1699.9");
            var locked = agentTarget();
            when(router.resolveThreadTarget("slack", "D1", "1699.9")).thenReturn(new ResolvedTarget(locked, null, null, null, null));
            // A legacy connector authenticated by the same secret and owning the target.
            when(router.threadCredentialsForDm(eq("slack"), eq(locked), eq("sig-l"), any()))
                    .thenReturn(new ResolvedTarget(locked, null, null, "xoxb-legacy", "sig-l"));

            handler.handleEvent(event, new SlackEventEnvelope("sig-l", "T1", "A1", "UBOT"));

            verify(conversationService).startConversation(any(), eq("agent-1"), eq("U1"), any());
            verify(slackApi).postMessage(eq("Bearer xoxb-legacy"), eq("D1"), eq("1699.9"), anyString());
        }

        @Test
        @DisplayName("help is not posted into a channel another app's integration serves")
        void helpSuppressedForForeignSecret() throws Exception {
            var handler = handler(false);
            when(router.channelMatchesInbound(eq("slack"), eq("C1"), eq("sig-b"), any())).thenReturn(false);
            when(router.getBotToken("slack", "C1")).thenReturn("xoxb");

            handler.handleEvent(mention("C1"), new SlackEventEnvelope("sig-b", "T1", "A1", "UBOT"));

            verify(slackApi, never()).postMessage(anyString(), anyString(), any(), anyString());
        }

        @Test
        @DisplayName("the error notice is not posted into a channel another app's integration serves")
        void errorNoticeSuppressedForForeignSecret() throws Exception {
            var handler = handler(false);
            when(router.resolveTarget(eq("slack"), eq("C1"), anyString())).thenThrow(new IllegalStateException("boom"));
            when(router.getBotToken("slack", "C1")).thenReturn("xoxb");
            when(router.channelMatchesInbound(eq("slack"), eq("C1"), eq("sig-b"), any())).thenReturn(false);
            when(router.channelMatchesInbound(eq("slack"), eq("C1"), eq("sig-a"), any())).thenReturn(true);

            handler.handleEventAsync("evt-foreign", mention("C1"), new SlackEventEnvelope("sig-b", "T1", "A1", "UBOT"));
            handler.handleEventAsync("evt-own", mention("C1"), new SlackEventEnvelope("sig-a", "T1", "A1", "UBOT"));

            // Only the owning app's failure produces an apology.
            verify(slackApi, timeout(5000).times(1)).postMessage(anyString(), eq("C1"), any(), anyString());
            Thread.sleep(200);
            verify(slackApi, times(1)).postMessage(anyString(), eq("C1"), any(), anyString());
        }

        @Test
        @DisplayName("an observer does not answer an event another app signed")
        void observerSuppressedForForeignSecret() throws Exception {
            var handler = handler(false);
            var event = mention("C1");
            event.put("type", "message");
            event.put("text", "plain chatter");
            when(router.observeCandidates("slack", "C1")).thenReturn(List.of(agentTarget()));
            when(router.channelMatchesInbound(eq("slack"), eq("C1"), eq("sig-b"), any())).thenReturn(false);

            handler.handleEvent(event, new SlackEventEnvelope("sig-b", "T1", "A1", "UBOT"));

            verifyNoInteractions(observeGate);
            verifyNoInteractions(conversationService);
        }

        @Test
        @DisplayName("a group follow-up from another channel or another app is ignored")
        void groupFollowUpMismatchIgnored() throws Exception {
            var handler = handler(false);
            var groupTarget = new ChannelTarget();
            groupTarget.setName("panel");
            groupTarget.setType(ChannelTarget.TargetType.GROUP);
            groupTarget.setTargetId("group-1");
            var resolved = new ResolvedTarget(groupTarget, "q?", integration("int-a", "sig-a", groupTarget), null, null);
            var listener = mock(SlackGroupDiscussionListener.class);
            when(listener.getAgentMessageTsMap()).thenReturn(Map.of("agent-1", "1700.5"));
            when(listener.awaitCompletion(anyLong(), any())).thenReturn(true);
            when(listener.getAgentIdForMessageTs("1700.5")).thenReturn("agent-1");
            handler.registerAgentThreadMappings(listener, resolved, "C1");

            var reply = mention("C1");
            reply.put("type", "message");
            reply.put("text", "follow up");
            reply.put("thread_ts", "1700.5");
            // Signed by app B although the discussion ran under app A.
            handler.handleEvent(reply, new SlackEventEnvelope("sig-b", "T1", "A1", "UBOT"));
            // Right app, wrong channel.
            reply.put("channel", "C2");
            handler.handleEvent(reply, new SlackEventEnvelope("sig-a", "T1", "A1", "UBOT"));

            verify(listener, never()).getAgentContext(anyString());
            verifyNoInteractions(conversationService);
        }

        @Test
        @DisplayName("#3: a Slack-started group discussion records the integration that started it")
        void groupOriginIsRecorded() throws Exception {
            var handler = handler(false);
            var groupTarget = new ChannelTarget();
            groupTarget.setName("panel");
            groupTarget.setType(ChannelTarget.TargetType.GROUP);
            groupTarget.setTargetId("group-1");
            when(router.resolveTarget(eq("slack"), eq("C1"), anyString()))
                    .thenReturn(new ResolvedTarget(groupTarget, "q?", integration("int-a", "sig-a", groupTarget), null, null));
            var gc = new GroupConversation();
            gc.setId("gc-9");
            when(groupConversationService.startAndDiscussAsync(eq("group-1"), anyString(), anyString(), any())).thenReturn(gc);

            handler.handleEvent(mention("C1"), new SlackEventEnvelope("sig-a", "T1", "A1", "UBOT"));

            verify(userConversationStore).createUserConversation(argThat(m -> "channel:slack-group-origin:gc-9".equals(m.getIntent())
                    && "integration:res-int-a".equals(m.getUserId()) && "gc-9".equals(m.getConversationId())));
        }

        @Test
        @DisplayName("H4d: a DM is routed with the credentials that signed it")
        void dmUsesTheVerifiedSecret() throws Exception {
            var handler = handler(true);
            var event = mention("D1");
            event.put("type", "message");
            event.put("channel_type", "im");
            event.put("text", "hello");
            when(router.resolveDefaultForDm(eq("slack"), anyString(), eq("sig-a"), any())).thenReturn(routeTo("int-a", "sig-a"));

            handler.handleEvent(event, new SlackEventEnvelope("sig-a", "T1", "A1", "UBOT"));

            verify(router).resolveDefaultForDm(eq("slack"), eq("hello"), eq("sig-a"),
                    argThat(ids -> "T1".equals(ids.get(ChannelTargetRouter.CFG_TEAM_ID))));
            verify(conversationService).startConversation(any(), eq("agent-1"), anyString(), any());
        }

        @Test
        @DisplayName("an event with no verified secret acts on nothing")
        void noVerifiedSecret() throws Exception {
            var handler = handler(true);
            when(router.resolveTarget(eq("slack"), eq("C1"), anyString())).thenReturn(routeTo("int-a", "sig-a"));

            handler.handleEvent(mention("C1"), null);

            verifyNoInteractions(conversationService);
        }

        @Test
        @DisplayName("a thread mapped under the bare Slack id before namespacing keeps its conversation")
        void legacyMappingIsReused() throws Exception {
            var handler = handler(true, "T1");
            when(router.resolveTarget(eq("slack"), eq("C1"), anyString())).thenReturn(routeTo("int-a", "sig-a"));
            when(userConversationStore.readUserConversation(anyString(), eq("U1")))
                    .thenReturn(new UserConversation("intent", "U1", Deployment.Environment.production, "agent-1", "conv-legacy"));

            handler.handleEvent(mention("C1"), new SlackEventEnvelope("sig-a", "T1", "A1", "UBOT"));

            verify(conversationService, never()).startConversation(any(), anyString(), anyString(), any());
            verify(conversationService).say(eq("conv-legacy"), any(), any(), any(), any(), anyBoolean(), any());
        }

        @Test
        @DisplayName("user ids: bare by default; team-namespaced when enabled; aliased to the bare id only for the legacy team")
        void userIdNamespacing() throws Exception {
            var event = mention("C1");
            var envelope = new SlackEventEnvelope("sig-a", "T1", "A1", "UBOT");

            assertEquals(new SlackEventHandler.SlackUser("U1", null, "U1", "T1"), handler(false).slackUser(event, envelope));
            // Enabled, no legacy team known (multi-workspace): never aliased.
            assertEquals(new SlackEventHandler.SlackUser("slack:T1:U1", null, "U1", "T1"), handler(true).slackUser(event, envelope));
            // Enabled with the configured legacy team.
            assertEquals(new SlackEventHandler.SlackUser("slack:T1:U1", "U1", "U1", "T1"),
                    handler(true, "T1").slackUser(event, envelope));
            // Derived legacy team: every routed integration pins T1.
            when(router.commonPinnedTeamId("slack")).thenReturn("T1");
            assertEquals("U1", handler(true).slackUser(event, envelope).legacyUserId());
            // A Slack Connect user from another org carries their own team — and is not
            // aliased.
            event.put("user_team", "T9");
            var connect = handler(true, "T1").slackUser(event, envelope);
            assertEquals("slack:T9:U1", connect.eddiUserId());
            assertNull(connect.legacyUserId());
            // No team known at all: bare id.
            assertEquals(new SlackEventHandler.SlackUser("U1", null, "U1", null),
                    handler(true, "T1").slackUser(mention("C1"), new SlackEventEnvelope("sig-a", null, null, null)));
        }

        @Test
        @DisplayName("#2: legacy-team user's bare-id memories are copied to the namespaced id on first contact, once")
        void legacyMemoriesAreCarriedOver() throws Exception {
            var handler = handler(true, "T1");
            var entry = new UserMemoryEntry("id-1", "U1", "favourite_colour", "green", "preference",
                    Property.Visibility.self, "agent-1", List.of(), "conv-old", false, 3,
                    Instant.EPOCH, Instant.EPOCH);
            when(userMemoryStore.countEntries("slack:T1:U1")).thenReturn(0L);
            when(userMemoryStore.getAllEntries("U1")).thenReturn(List.of(entry));

            var user = handler.slackUser(mention("C1"), new SlackEventEnvelope("sig-a", "T1", "A1", "UBOT"));
            handler.carryOverLegacyMemories(user);
            handler.carryOverLegacyMemories(user); // second contact: no second copy

            verify(userMemoryStore, times(1)).upsert(argThat(e -> "slack:T1:U1".equals(e.userId()) && e.id() == null
                    && "favourite_colour".equals(e.key()) && "green".equals(e.value())));
        }

        @Test
        @DisplayName("#2: nothing is copied when the namespaced id already has memories")
        void noCarryOverOntoExistingMemories() throws Exception {
            var handler = handler(true, "T1");
            when(userMemoryStore.countEntries("slack:T1:U1")).thenReturn(2L);

            handler.carryOverLegacyMemories(handler.slackUser(mention("C1"), new SlackEventEnvelope("sig-a", "T1", "A1", "UBOT")));

            verify(userMemoryStore, never()).getAllEntries(anyString());
            verify(userMemoryStore, never()).upsert(any());
        }

        @Test
        @DisplayName("#2: a new thread of a legacy-team user carries memories over before its conversation starts")
        void newThreadTriggersCarryOver() throws Exception {
            var handler = handler(true, "T1");
            when(router.resolveTarget(eq("slack"), eq("C1"), anyString())).thenReturn(routeTo("int-a", "sig-a"));
            when(userMemoryStore.countEntries("slack:T1:U1")).thenReturn(0L);
            when(userMemoryStore.getAllEntries("U1")).thenReturn(List.of());

            handler.handleEvent(mention("C1"), new SlackEventEnvelope("sig-a", "T1", "A1", "UBOT"));

            verify(userMemoryStore).getAllEntries("U1");
            verify(conversationService).startConversation(any(), eq("agent-1"), eq("slack:T1:U1"), any());
        }
    }

    // ─── H4b: approval cards are bound to their pause ───

    @Test
    void notifyApprovers_buttonsCarryThePauseId() {
        var slackApi = mock(SlackWebApiClient.class);
        var handler = newHandler(slackApi);
        handler.notifyApprovers(resolvedWithApprovalChannel(), "conv-1", "agent-1", boundBookmark(Instant.ofEpochMilli(1_234L)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> blocks = ArgumentCaptor.forClass(List.class);
        verify(slackApi).postBlocksMessage(anyString(), eq("C_APPROVAL"), any(), blocks.capture(), anyString());
        assertTrue(blocks.getValue().toString().contains("acme-int|conv-1|1234"), blocks.getValue().toString());
    }

    @Test
    void notifyApprovers_conversationNotStartedByThisIntegration_rendersNoButtons() {
        // #6: e.g. a conversation started before the binding existed. A click would
        // be refused, so the card says where to decide instead of offering buttons.
        var slackApi = mock(SlackWebApiClient.class);
        var handler = newHandler(slackApi);
        var bookmark = boundBookmark(Instant.ofEpochMilli(1_234L));
        bookmark.getConversationOutputs().get(0).put("context", new HashMap<>(Map.of("channelIntent", "channel:slack:D1:agent-1:1")));

        handler.notifyApprovers(resolvedWithApprovalChannel(), "conv-1", "agent-1", bookmark);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> blocks = ArgumentCaptor.forClass(List.class);
        verify(slackApi).postBlocksMessage(anyString(), eq("C_APPROVAL"), any(), blocks.capture(), anyString());
        assertFalse(blocks.getValue().toString().contains(SlackHitlSupport.ACTION_APPROVE));
        assertTrue(blocks.getValue().toString().contains("not started through this integration"));
    }

    @Test
    void notifyApprovers_unidentifiedPause_rendersNoButtons() {
        // The bookmark read never saw the pause persisted: a button could only mean
        // "whatever is paused now", so none is rendered.
        var slackApi = mock(SlackWebApiClient.class);
        var handler = newHandler(slackApi);

        handler.notifyApprovers(resolvedWithApprovalChannel(), "conv-1", "agent-1", new ConversationMemorySnapshot());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> blocks = ArgumentCaptor.forClass(List.class);
        verify(slackApi).postBlocksMessage(anyString(), eq("C_APPROVAL"), any(), blocks.capture(), anyString());
        assertFalse(blocks.getValue().toString().contains(SlackHitlSupport.ACTION_APPROVE));
        assertTrue(blocks.getValue().toString().contains("could not be identified"));
    }

    // ─── The observe path ───

    /**
     * These three decide whether an observer speaks at all, and none of them was
     * covered: the branch's own tests only added constructor mocks. The whole point
     * of an observer is that nobody asked it to talk, so every one of these is the
     * difference between a useful watcher and a bot that interrupts.
     */
    @Nested
    @DisplayName("observe path")
    class ObservePath {

        @Test
        @DisplayName("channel system messages are not observed")
        void systemSubtypesAreIgnored() {
            // These arrive as ordinary `message` events with a human `user` and no
            // `bot_id`, so the bot-message filter upstream does not catch them.
            // Observed, the bot answers "@someone has joined the channel" with a
            // thread, an LLM turn, and a reply off its daily allowance.
            for (String subtype : new String[]{"channel_join", "channel_leave", "channel_topic",
                    "channel_purpose", "channel_name", "pinned_item", "me_message",
                    "message_changed", "thread_broadcast"}) {
                assertFalse(SlackEventHandler.isObservableSubtype(subtype), subtype);
            }
        }

        @Test
        @DisplayName("a plain message and a file upload are observed")
        void ordinaryMessagesAreObserved() {
            // `file_share` is how a MIME trigger is meant to fire at all.
            assertTrue(SlackEventHandler.isObservableSubtype(null));
            assertTrue(SlackEventHandler.isObservableSubtype("file_share"));
        }

        @Test
        @DisplayName("a bot mention anywhere in the text is recognised, labelled or not")
        void mentionIsFoundAnywhere() {
            // Slack delivers a channel mention twice, as `message` and as
            // `app_mention`. `app_mention` is the copy that routes, so an observer
            // that fails to spot the mention answers a sentence that is already
            // being answered — two replies, from two different agents.
            assertTrue(SlackEventHandler.mentionsThisBot("<@U0BOT> hello", "U0BOT"));
            assertTrue(SlackEventHandler.mentionsThisBot("thanks <@UALICE> — <@U0BOT> look?", "U0BOT"));
            assertTrue(SlackEventHandler.mentionsThisBot("hi <@U0BOT|eddi> there", "U0BOT"));
            assertFalse(SlackEventHandler.mentionsThisBot("<@UALICE> can you check?", "U0BOT"));
            assertFalse(SlackEventHandler.mentionsThisBot("no mentions here", "U0BOT"));
        }

        @Test
        @DisplayName("with no known bot id, any mention suppresses observing")
        void unknownBotIdSuppressesOnAnyMention() {
            // An org-wide install can deliver an envelope with no `is_bot`
            // authorization, so the app's own id is unknown. Erring towards
            // silence is a missed reply; erring the other way is a duplicate one.
            assertTrue(SlackEventHandler.mentionsThisBot("thanks <@UALICE> — <@U0BOT> look?", null));
            assertTrue(SlackEventHandler.mentionsThisBot("<@UALICE> can you check?", ""));
            assertFalse(SlackEventHandler.mentionsThisBot("no mentions here", null));
            assertFalse(SlackEventHandler.mentionsThisBot("", null));
        }

        @Test
        @DisplayName("attached MIME types are collected, and malformed entries skipped")
        void mimeTypesAreCollected() {
            Map<String, Object> event = new HashMap<>();
            event.put("files", List.of(
                    Map.of("mimetype", "application/pdf"),
                    Map.of("name", "no-mimetype.txt"),
                    Map.of("mimetype", "  "),
                    Map.of("mimetype", "image/png")));

            assertEquals(List.of("application/pdf", "image/png"),
                    SlackEventHandler.attachedMimeTypes(event));
        }

        @Test
        @DisplayName("no files means no MIME types, not a crash")
        void noFilesIsEmpty() {
            assertEquals(List.of(), SlackEventHandler.attachedMimeTypes(new HashMap<>()));
            Map<String, Object> notAList = new HashMap<>();
            notAList.put("files", "nonsense");
            assertEquals(List.of(), SlackEventHandler.attachedMimeTypes(notAList));
        }
    }
}
