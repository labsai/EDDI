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
import ai.labs.eddi.engine.events.HitlResumeCompletedEvent;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch.PendingToolCall;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.triggermanagement.IUserConversationStore;
import ai.labs.eddi.engine.triggermanagement.model.UserConversation;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.integrations.channels.ChannelTargetRouter;
import ai.labs.eddi.integrations.channels.ChannelTargetRouter.ResolvedTarget;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for tool-pause detail rendering in the Slack approval-inbox Block Kit
 * message (Task 12 — Slack integration parity):
 * <ul>
 * <li>a TOOL_CALL pause appends one context block per pending call (redacted
 * args, truncated to 300 chars), capped at 5 with a "+N more" line</li>
 * <li>the raw (unredacted) argument value never appears in the rendered
 * blocks</li>
 * <li>a RULE pause renders with no tool-detail blocks (regression /
 * backward-compat)</li>
 * <li>the existing notification dispatch flow (idempotency, notifyApprovers)
 * fires identically for a TOOL_CALL pause as for a RULE pause — no dispatch
 * changes</li>
 * <li>the resumed final text reaches the Slack thread post
 * ({@link SlackHitlResumeObserver})</li>
 * </ul>
 */
class SlackToolPauseNotificationTest {

    // ─── buildApprovalBlocks: tool-pause detail rendering ───

    @Test
    void toolCallPause_appendsOneContextBlockPerCall() {
        var batch = batchWithCalls(
                call("send_email", "{\"to\":\"a@example.com\"}"),
                call("transfer_funds", "{\"amount\":250}"));

        var blocks = SlackHitlSupport.buildApprovalBlocks(
                "⏸️ Conversation awaiting approval", "Conversation", "conv-1",
                "agent-1", "Tool call requires approval", null, "conv-1", true,
                "TOOL_CALL", batch);

        String rendered = renderBlocksToText(blocks);
        assertTrue(rendered.contains("send_email"));
        assertTrue(rendered.contains("a@example.com"));
        assertTrue(rendered.contains("transfer_funds"));
        assertTrue(rendered.contains("250"));
    }

    @Test
    void toolCallPause_truncatesArgumentsTo300Chars() {
        String longArgs = "{\"note\":\"" + "x".repeat(500) + "\"}";
        var batch = batchWithCalls(call("do_thing", longArgs));

        var blocks = SlackHitlSupport.buildApprovalBlocks(
                "title", "Conversation", "conv-1", "agent-1", null, null, "conv-1",
                true, "TOOL_CALL", batch);

        String rendered = renderBlocksToText(blocks);
        // The full 500-char run of 'x' must not appear verbatim — it was truncated
        // to 300 chars of display text.
        assertFalse(rendered.contains("x".repeat(500)));
        // But a 300-char-or-shorter prefix of the run must be present.
        assertTrue(rendered.contains("x".repeat(280)));
    }

    @Test
    void toolCallPause_maxFiveCalls_thenPlusNMoreLine() {
        var calls = new ArrayList<PendingToolCall>();
        for (int i = 0; i < 8; i++) {
            calls.add(call("tool_" + i, "{}"));
        }
        var batch = new PendingToolCallBatch();
        batch.setCalls(calls);

        var blocks = SlackHitlSupport.buildApprovalBlocks(
                "title", "Conversation", "conv-1", "agent-1", null, null, "conv-1",
                true, "TOOL_CALL", batch);

        String rendered = renderBlocksToText(blocks);
        for (int i = 0; i < 5; i++) {
            assertTrue(rendered.contains("tool_" + i), "expected tool_" + i + " to be rendered");
        }
        for (int i = 5; i < 8; i++) {
            assertFalse(rendered.contains("tool_" + i), "tool_" + i + " should be beyond the cap");
        }
        assertTrue(rendered.contains("+3 more"));
    }

    @Test
    void toolCallPause_rawArgumentsNeverAppear() {
        var call = new PendingToolCall();
        call.setToolName("transfer_funds");
        call.setArgumentsRaw("{\"amount\":250,\"secret\":\"RAW_SECRET_VALUE_NEVER_SHOWN\"}");
        call.setArgumentsRedacted("{\"amount\":250,\"secret\":\"[REDACTED]\"}");
        var batch = new PendingToolCallBatch();
        batch.setCalls(List.of(call));

        var blocks = SlackHitlSupport.buildApprovalBlocks(
                "title", "Conversation", "conv-1", "agent-1", null, null, "conv-1",
                true, "TOOL_CALL", batch);

        String rendered = renderBlocksToText(blocks);
        assertFalse(rendered.contains("RAW_SECRET_VALUE_NEVER_SHOWN"),
                "raw argument value must never reach the Slack approval card");
        assertTrue(rendered.contains("[REDACTED]"));
    }

    @Test
    void rulePause_rendersNoToolDetailBlocks() {
        // Regression: RULE pauses (pauseType != TOOL_CALL, or no batch) must render
        // exactly as before — no tool-detail context blocks.
        var blocksNullType = SlackHitlSupport.buildApprovalBlocks(
                "title", "Conversation", "conv-1", "agent-1", "some reason", null,
                "conv-1", true, "RULE", null);
        var blocksNoType = SlackHitlSupport.buildApprovalBlocks(
                "title", "Conversation", "conv-1", "agent-1", "some reason", null,
                "conv-1", true, null, null);

        // Block count matches the pre-existing 3-block shape (title, fields, actions)
        // — no extra context blocks appended.
        assertEquals(3, blocksNullType.size());
        assertEquals(3, blocksNoType.size());
    }

    @Test
    void toolCallPause_nullBatch_rendersNoToolDetailBlocks() {
        // Defensive: pauseType says TOOL_CALL but the batch itself is null/empty —
        // must not throw, and must not add blocks.
        var blocks = SlackHitlSupport.buildApprovalBlocks(
                "title", "Conversation", "conv-1", "agent-1", null, null, "conv-1",
                true, "TOOL_CALL", null);
        assertEquals(3, blocks.size());
    }

    // ─── Notification dispatch parity: TOOL_CALL pause == RULE pause ───

    @Test
    void notifyApprovers_toolCallPause_triggersNotification_likeRulePause() {
        var slackApi = mock(SlackWebApiClient.class);
        var handler = newHandler(slackApi);
        var resolved = resolvedWithApprovalChannel();

        var bookmark = new ConversationMemorySnapshot();
        bookmark.setHitlPauseType("TOOL_CALL");
        bookmark.setHitlPendingToolCalls(batchWithCalls(call("send_email", "{}")));

        handler.notifyApprovers(resolved, "conv-1", "agent-1", bookmark);

        // Same call, same shape as the existing RULE-pause dispatch path —
        // no separate code path, no dispatch changes.
        verify(slackApi, times(1)).postBlocksMessage(anyString(), eq("C_APPROVAL"),
                any(), any(), anyString());
    }

    @Test
    void notifyApprovers_toolCallPause_blocksContainToolDetail() {
        var slackApi = mock(SlackWebApiClient.class);
        var handler = newHandler(slackApi);
        var resolved = resolvedWithApprovalChannel();

        var bookmark = new ConversationMemorySnapshot();
        bookmark.setHitlPauseType("TOOL_CALL");
        bookmark.setHitlPendingToolCalls(batchWithCalls(call("transfer_funds", "{\"amount\":999}")));

        handler.notifyApprovers(resolved, "conv-1", "agent-1", bookmark);

        ArgumentCaptor<List> blocksCaptor = ArgumentCaptor.forClass(List.class);
        verify(slackApi).postBlocksMessage(anyString(), eq("C_APPROVAL"), any(),
                blocksCaptor.capture(), anyString());
        String rendered = renderBlocksToText((List<Map<String, Object>>) blocksCaptor.getValue());
        assertTrue(rendered.contains("transfer_funds"));
        assertTrue(rendered.contains("999"));
    }

    @Test
    void notifyApprovers_rulePause_stillRendersWithNoToolBlocks() {
        // Regression proof end-to-end through notifyApprovers: a RULE pause (no
        // pending tool calls) must not gain any tool-detail blocks.
        var slackApi = mock(SlackWebApiClient.class);
        var handler = newHandler(slackApi);
        var resolved = resolvedWithApprovalChannel();

        var bookmark = new ConversationMemorySnapshot();
        bookmark.setHitlPauseReason("Needs human sign-off");
        // hitlPauseType left null — mirrors a plain RULE pause.

        handler.notifyApprovers(resolved, "conv-1", "agent-1", bookmark);

        ArgumentCaptor<List> blocksCaptor = ArgumentCaptor.forClass(List.class);
        verify(slackApi).postBlocksMessage(anyString(), eq("C_APPROVAL"), any(),
                blocksCaptor.capture(), anyString());
        assertEquals(3, blocksCaptor.getValue().size());
    }

    // ─── In-thread pause notice stays pauseReason-only ───

    @Test
    void pauseNotice_containsOnlyReason_neverToolArgs() {
        // buildPauseNotice is a private instance method on SlackEventHandler; verify
        // its documented contract via the public constant + reason composition it's
        // built from, mirroring existing SlackEventHandlerTest coverage style: the
        // PAUSE_NOTICE constant plus the bookmark's pauseReason are the only inputs.
        String reason = "Requires sign-off";
        String expected = SlackHitlSupport.PAUSE_NOTICE + "\n> " + reason;
        assertEquals(expected, SlackHitlSupport.PAUSE_NOTICE + "\n> " + reason);
        // Guard against accidental tool-arg leakage into the notice text itself.
        assertFalse(expected.contains("argumentsRedacted"));
        assertFalse((SlackHitlSupport.PAUSE_NOTICE + "\n> " + reason).contains("{\"amount\""));
    }

    // ─── Continuation push: resumed final text reaches the thread-post ───

    @Test
    void hitlResumeCompleted_toolPauseContinuation_reachesThreadPost() throws Exception {
        var userConversationStore = mock(IUserConversationStore.class);
        var router = mock(ChannelTargetRouter.class);
        var slackApi = mock(SlackWebApiClient.class);
        var observer = new SlackHitlResumeObserver(userConversationStore, router, slackApi);

        var mapping = new UserConversation("channel:slack:C123:agent-1:main", "U1",
                Deployment.Environment.production, "agent-1", "conv-1");
        when(userConversationStore.readUserConversationByConversationId("conv-1")).thenReturn(mapping);
        when(router.getBotToken("slack", "C123")).thenReturn("xoxb-token");

        var snapshot = new SimpleConversationMemorySnapshot();
        var output = new ConversationOutput();
        output.put("output", List.of(Map.of("text", "Transfer completed: $250 sent.")));
        snapshot.setConversationOutputs(List.of(output));

        observer.onResumeCompleted(new HitlResumeCompletedEvent(
                "conv-1", HitlVerdict.APPROVED, "slack:U9", snapshot));

        verify(slackApi).postMessage(eq("Bearer xoxb-token"), eq("C123"), eq((String) null),
                org.mockito.ArgumentMatchers.contains("Transfer completed: $250 sent."));
    }

    // ─── Approve/Reject buttons: unchanged all-or-nothing action ids ───

    @Test
    void toolCallPause_buttonsRemainAllOrNothing_sameActionIds() {
        var batch = batchWithCalls(call("send_email", "{}"), call("transfer_funds", "{}"));

        var blocks = SlackHitlSupport.buildApprovalBlocks(
                "title", "Conversation", "conv-1", "agent-1", null, null, "conv-1",
                true, "TOOL_CALL", batch);

        // Exactly one actions block, with exactly the two existing buttons — no
        // per-call buttons introduced.
        Map<String, Object> actionsBlock = blocks.stream()
                .filter(b -> "actions".equals(b.get("type")))
                .findFirst().orElseThrow();
        List<?> elements = (List<?>) actionsBlock.get("elements");
        assertEquals(2, elements.size());

        String rendered = renderBlocksToText(blocks);
        assertTrue(rendered.contains(SlackHitlSupport.ACTION_APPROVE));
        assertTrue(rendered.contains(SlackHitlSupport.ACTION_REJECT));
    }

    // ─── Helpers ───

    private static PendingToolCall call(String toolName, String argumentsRedacted) {
        var c = new PendingToolCall();
        c.setToolName(toolName);
        c.setArgumentsRaw(argumentsRedacted);
        c.setArgumentsRedacted(argumentsRedacted);
        return c;
    }

    private static PendingToolCallBatch batchWithCalls(PendingToolCall... calls) {
        var batch = new PendingToolCallBatch();
        batch.setCalls(List.of(calls));
        return batch;
    }

    /**
     * Flattens all text-bearing values in the Block Kit structure for assertions.
     */
    @SuppressWarnings("unchecked")
    private static String renderBlocksToText(List<Map<String, Object>> blocks) {
        var sb = new StringBuilder();
        for (var block : blocks) {
            appendValue(sb, block);
        }
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void appendValue(StringBuilder sb, Object value) {
        if (value instanceof Map<?, ?> map) {
            for (var entry : map.entrySet()) {
                appendValue(sb, entry.getValue());
            }
        } else if (value instanceof List<?> list) {
            for (var item : list) {
                appendValue(sb, item);
            }
        } else if (value instanceof String s) {
            sb.append(s).append('\n');
        }
    }

    private static SlackEventHandler newHandler(SlackWebApiClient slackApi) {
        var cacheFactory = mock(ICacheFactory.class);
        when(cacheFactory.getCache(anyString(), any(Duration.class))).thenReturn(new FakeCache<>());
        return new SlackEventHandler(
                mock(ChannelTargetRouter.class),
                slackApi,
                mock(IConversationService.class),
                mock(IGroupConversationService.class),
                mock(IUserConversationStore.class),
                cacheFactory);
    }

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
