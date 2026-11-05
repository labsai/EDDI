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
import ai.labs.eddi.engine.hitl.context.RenderedApprovalContext;
import ai.labs.eddi.engine.hitl.context.RenderedApprovalContext.Item;
import ai.labs.eddi.engine.lifecycle.GroupConversationEventSink;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.triggermanagement.IUserConversationStore;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.integrations.channels.ChannelTargetRouter;
import ai.labs.eddi.integrations.channels.ChannelTargetRouter.ResolvedTarget;
import ai.labs.eddi.integrations.channels.ObserveGate;
import ai.labs.eddi.integrations.slack.hitl.InMemorySlackApprovalRecordStore;
import ai.labs.eddi.modules.llm.tools.ToolCostTracker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * End to end through the two callers: the persisted context reaches the card
 * only for an opted-in integration, plain text, with unfurling off; without
 * context nothing about the post changes.
 */
@DisplayName("Slack approval card with approvalContext")
@SuppressWarnings({"unchecked", "rawtypes"})
class SlackApprovalContextNotificationTest {

    private static SlackEventHandler newHandler(SlackWebApiClient slackApi) {
        var cacheFactory = mock(ICacheFactory.class);
        when(cacheFactory.getCache(anyString(), any(Duration.class))).thenReturn(mock(ICache.class));
        return new SlackEventHandler(mock(ChannelTargetRouter.class), mock(ObserveGate.class), mock(ToolCostTracker.class), slackApi,
                mock(IConversationService.class), mock(IGroupConversationService.class), mock(IUserConversationStore.class), cacheFactory,
                new SlackConfig(SlackConfig.DEFAULT_REQUEST_TIMEOUT_SECONDS, SlackConfig.DEFAULT_GROUP_COMPLETION_TIMEOUT_SECONDS,
                        SlackConfig.DEFAULT_API_MAX_RETRIES, SlackConfig.DEFAULT_API_RETRY_BASE_MS),
                new InMemorySlackApprovalRecordStore(), mock(IUserMemoryStore.class));
    }

    private static ResolvedTarget resolved(boolean optIn) {
        var cfg = new ChannelIntegrationConfiguration();
        cfg.setName("acme-int");
        cfg.setChannelType("slack");
        var pc = new HashMap<String, String>();
        pc.put("botToken", "xoxb-token");
        pc.put(SlackHitlSupport.CFG_HITL_APPROVAL_CHANNEL, "C_APPROVAL");
        pc.put(SlackHitlSupport.CFG_HITL_APPROVER_USER_IDS, "U_APPROVER");
        if (optIn) {
            pc.put(SlackHitlSupport.CFG_HITL_APPROVAL_CONTEXT, "true");
        }
        cfg.setPlatformConfig(pc);
        return new ResolvedTarget(null, null, cfg, null, null);
    }

    private static ConversationMemorySnapshot bookmark(boolean withContext) {
        var bookmark = new ConversationMemorySnapshot();
        if (withContext) {
            bookmark.setHitlApprovalContext(
                    RenderedApprovalContext.of(List.of(new Item("Draft", "Acme cuts onboarding to 3 days <!channel>", false, "agent", null, null)),
                            false));
        }
        return bookmark;
    }

    private static String flatten(Object value) {
        StringBuilder sb = new StringBuilder();
        if (value instanceof Map<?, ?> map) {
            map.values().forEach(v -> sb.append(flatten(v)));
        } else if (value instanceof List<?> list) {
            list.forEach(v -> sb.append(flatten(v)));
        } else if (value instanceof String s) {
            sb.append(s).append('\n');
        }
        return sb.toString();
    }

    @Test
    @DisplayName("opted in: the context is on the card, as plain text, posted with unfurling off")
    void optedInShowsContextWithoutUnfurl() {
        var slackApi = mock(SlackWebApiClient.class);

        newHandler(slackApi).notifyApprovers(resolved(true), "conv-1", "agent-1", bookmark(true));

        ArgumentCaptor<List> blocks = ArgumentCaptor.forClass(List.class);
        verify(slackApi).postBlocksMessage(anyString(), eq("C_APPROVAL"), any(), blocks.capture(), anyString(), eq(false));
        verify(slackApi, never()).postBlocksMessage(anyString(), anyString(), any(), any(), anyString());
        String text = flatten(blocks.getValue());
        assertTrue(text.contains("Acme cuts onboarding to 3 days <!channel>"), "carried verbatim in a plain_text section");
        assertEquals("actions", ((Map<String, Object>) blocks.getValue().get(blocks.getValue().size() - 1)).get("type"));
    }

    @Test
    @DisplayName("not opted in: only the hint, no conversation text, and the post is the unchanged five-argument call")
    void notOptedInShowsHintOnly() {
        var slackApi = mock(SlackWebApiClient.class);

        newHandler(slackApi).notifyApprovers(resolved(false), "conv-1", "agent-1", bookmark(true));

        ArgumentCaptor<List> blocks = ArgumentCaptor.forClass(List.class);
        verify(slackApi).postBlocksMessage(anyString(), eq("C_APPROVAL"), any(), blocks.capture(), anyString());
        String text = flatten(blocks.getValue());
        assertTrue(text.contains(SlackApprovalContextBlocks.HINT_ONLY));
        assertFalse(text.contains("Acme cuts onboarding"));
    }

    @Test
    @DisplayName("no context configured: the card and the post are exactly what they were")
    void noContextUnchanged() {
        var slackApi = mock(SlackWebApiClient.class);

        newHandler(slackApi).notifyApprovers(resolved(true), "conv-1", "agent-1", bookmark(false));

        ArgumentCaptor<List> blocks = ArgumentCaptor.forClass(List.class);
        verify(slackApi).postBlocksMessage(anyString(), eq("C_APPROVAL"), any(), blocks.capture(), anyString());
        verify(slackApi, never()).postBlocksMessage(anyString(), anyString(), any(), any(), anyString(), anyBoolean());
        assertFalse(flatten(blocks.getValue()).contains(SlackApprovalContextBlocks.HINT_ONLY));
        assertFalse(flatten(blocks.getValue()).contains("divider"));
    }

    @Test
    @DisplayName("group card: the event's context is rendered for an opted-in listener, hint otherwise")
    void groupListener() {
        var slackApi = mock(SlackWebApiClient.class);
        var context = RenderedApprovalContext.of(List.of(new Item("Draft", "the group draft", false, "group", null, null)), false);
        var event = new GroupConversationEventSink.HitlPauseEvent(1, "Synthesis", "Requires approval", "PHASE", Instant.now(), context);

        var optedIn = new SlackGroupDiscussionListener(slackApi, "Bearer t", "C1", "ts1", "C_APPROVAL", "U1", "acme-int",
                new InMemorySlackApprovalRecordStore());
        optedIn.setShowApprovalContext(true);
        optedIn.onGroupStart(new GroupConversationEventSink.GroupStartEvent("gc-1", "g-1", "q", "ROUND_TABLE", 1, List.of("a")));
        optedIn.onHitlPause(event);

        ArgumentCaptor<List> blocks = ArgumentCaptor.forClass(List.class);
        verify(slackApi).postBlocksMessage(eq("Bearer t"), eq("C_APPROVAL"), any(), blocks.capture(), anyString(), eq(false));
        assertTrue(flatten(blocks.getValue()).contains("the group draft"));

        var slackApi2 = mock(SlackWebApiClient.class);
        var notOptedIn = new SlackGroupDiscussionListener(slackApi2, "Bearer t", "C1", "ts1", "C_APPROVAL", "U1", "acme-int",
                new InMemorySlackApprovalRecordStore());
        notOptedIn.onGroupStart(new GroupConversationEventSink.GroupStartEvent("gc-2", "g-1", "q", "ROUND_TABLE", 1, List.of("a")));
        notOptedIn.onHitlPause(event);

        ArgumentCaptor<List> blocks2 = ArgumentCaptor.forClass(List.class);
        verify(slackApi2).postBlocksMessage(eq("Bearer t"), eq("C_APPROVAL"), any(), blocks2.capture(), anyString());
        assertFalse(flatten(blocks2.getValue()).contains("the group draft"));
        assertTrue(flatten(blocks2.getValue()).contains(SlackApprovalContextBlocks.HINT_ONLY));
    }
}
