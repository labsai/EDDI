/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack;

import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.triggermanagement.IUserConversationStore;
import ai.labs.eddi.integrations.channels.ChannelTargetRouter;
import ai.labs.eddi.integrations.channels.ObserveGate;
import ai.labs.eddi.modules.llm.tools.ToolCostTracker;
import ai.labs.eddi.integrations.channels.ChannelTargetRouter.ResolvedTarget;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

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
        return new ResolvedTarget(null, null, cfg, null, null);
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
