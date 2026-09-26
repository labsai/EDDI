/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack;

import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.properties.IUserMemoryStore;
import ai.labs.eddi.configs.properties.model.Property.Visibility;
import ai.labs.eddi.configs.properties.model.UserMemoryEntry;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IConversationService.ConversationResult;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.triggermanagement.IUserConversationStore;
import ai.labs.eddi.engine.triggermanagement.model.UserConversation;
import ai.labs.eddi.integrations.channels.ChannelTargetRouter;
import ai.labs.eddi.integrations.channels.ChannelTargetRouter.ResolvedTarget;
import ai.labs.eddi.integrations.channels.ObserveGate;
import ai.labs.eddi.integrations.slack.SlackEventHandler.SlackUser;
import ai.labs.eddi.integrations.slack.hitl.ISlackApprovalRecordStore;
import ai.labs.eddi.integrations.slack.hitl.InMemorySlackApprovalRecordStore;
import ai.labs.eddi.modules.llm.tools.ToolCostTracker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Slack identity namespacing ({@link SlackUserIdentity}), its compatibility
 * paths for data stored under the raw Slack id, and the persisted approval
 * record that {@code notifyApprovers} writes.
 */
class SlackUserIdentityTest {

    private static final String INTENT = "channel:slack:C1:agent-1:1700000000.000100";
    private static final String RAW = "U0ALICE";
    private static final String NAMESPACED = "slack:T1:U0ALICE";

    private IConversationService conversationService;
    private IUserConversationStore userConversationStore;
    private IUserMemoryStore userMemoryStore;
    private InMemorySlackApprovalRecordStore approvalRecords;
    private SlackWebApiClient slackApi;
    private SlackEventHandler handler;

    @BeforeEach
    void setUp() throws Exception {
        conversationService = mock(IConversationService.class);
        userConversationStore = mock(IUserConversationStore.class);
        userMemoryStore = mock(IUserMemoryStore.class);
        approvalRecords = new InMemorySlackApprovalRecordStore();
        slackApi = mock(SlackWebApiClient.class);
        handler = newHandler(approvalRecords);
        when(conversationService.startConversation(any(), anyString(), anyString(), any()))
                .thenReturn(new ConversationResult("conv-new", URI.create("eddi://conv-new")));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private SlackEventHandler newHandler(ISlackApprovalRecordStore records) {
        var cacheFactory = mock(ICacheFactory.class);
        doReturn(mock(ICache.class)).when(cacheFactory).getCache(anyString(), any(Duration.class));
        return new SlackEventHandler(mock(ChannelTargetRouter.class), mock(ObserveGate.class),
                mock(ToolCostTracker.class), slackApi, conversationService, mock(IGroupConversationService.class),
                userConversationStore, cacheFactory,
                new SlackConfig(SlackConfig.DEFAULT_REQUEST_TIMEOUT_SECONDS,
                        SlackConfig.DEFAULT_GROUP_COMPLETION_TIMEOUT_SECONDS,
                        SlackConfig.DEFAULT_API_MAX_RETRIES, SlackConfig.DEFAULT_API_RETRY_BASE_MS),
                records, userMemoryStore);
    }

    // ─── Id format ───

    @Test
    void eddiUserId_isPrefixedAndWorkspaceQualified() {
        assertEquals("slack:T1:U0ALICE", SlackUserIdentity.eddiUserId("T1", "U0ALICE"));
        assertEquals("slack:U0ALICE", SlackUserIdentity.eddiUserId(null, "U0ALICE"));
        assertEquals("slack:U0ALICE", SlackUserIdentity.eddiUserId(" ", "U0ALICE"));
    }

    @Test
    void onlySlackShapedIdsAreLegacy() {
        assertTrue(SlackUserIdentity.isLegacySlackUserId("U0ALICE"));
        assertTrue(SlackUserIdentity.isLegacySlackUserId("W012ABC"));
        assertFalse(SlackUserIdentity.isLegacySlackUserId("alice"));
        assertFalse(SlackUserIdentity.isLegacySlackUserId("slack:T1:U0ALICE"));
        assertFalse(SlackUserIdentity.isLegacySlackUserId("u0alice"));
        assertFalse(SlackUserIdentity.isLegacySlackUserId(null));
    }

    // ─── Conversation mapping ───

    @Test
    void newThread_startsConversationUnderNamespacedId() throws Exception {
        String conversationId = handler.getOrCreateConversation("agent-1", new SlackUser(RAW, NAMESPACED), INTENT);

        assertEquals("conv-new", conversationId);
        verify(conversationService).startConversation(eq(Deployment.Environment.production), eq("agent-1"),
                eq(NAMESPACED), any());
        ArgumentCaptor<UserConversation> mapping = ArgumentCaptor.forClass(UserConversation.class);
        verify(userConversationStore).createUserConversation(mapping.capture());
        assertEquals(NAMESPACED, mapping.getValue().getUserId());
    }

    @Test
    void namespacedMapping_isUsedWithoutTouchingLegacy() throws Exception {
        when(userConversationStore.readUserConversation(INTENT, NAMESPACED)).thenReturn(
                new UserConversation(INTENT, NAMESPACED, Deployment.Environment.production, "agent-1", "conv-ns"));

        assertEquals("conv-ns", handler.getOrCreateConversation("agent-1", new SlackUser(RAW, NAMESPACED), INTENT));

        verify(userConversationStore, never()).readUserConversation(INTENT, RAW);
        verify(conversationService, never()).startConversation(any(), any(), any(), any());
    }

    @Test
    void legacyMapping_keepsItsConversationAndIsRekeyed() throws Exception {
        when(userConversationStore.readUserConversation(INTENT, RAW)).thenReturn(
                new UserConversation(INTENT, RAW, Deployment.Environment.production, "agent-1", "conv-legacy"));

        String conversationId = handler.getOrCreateConversation("agent-1", new SlackUser(RAW, NAMESPACED), INTENT);

        assertEquals("conv-legacy", conversationId);
        verify(conversationService, never()).startConversation(any(), any(), any(), any());
        ArgumentCaptor<UserConversation> mapping = ArgumentCaptor.forClass(UserConversation.class);
        verify(userConversationStore).createUserConversation(mapping.capture());
        assertEquals(NAMESPACED, mapping.getValue().getUserId());
        assertEquals("conv-legacy", mapping.getValue().getConversationId());
        verify(userConversationStore).deleteUserConversation(INTENT, RAW);
    }

    @Test
    void legacyMapping_rekeyFailureStillReturnsTheConversation() throws Exception {
        when(userConversationStore.readUserConversation(INTENT, RAW)).thenReturn(
                new UserConversation(INTENT, RAW, Deployment.Environment.production, "agent-1", "conv-legacy"));
        doThrow(new IResourceStore.ResourceStoreException("db down"))
                .when(userConversationStore).createUserConversation(any());

        assertEquals("conv-legacy", handler.getOrCreateConversation("agent-1", new SlackUser(RAW, NAMESPACED), INTENT));

        // The legacy mapping stays, so the next message finds it again.
        verify(userConversationStore, never()).deleteUserConversation(INTENT, RAW);
    }

    // ─── Memory migration ───

    private static UserMemoryEntry entry(String id, String userId, String key, String agent, Object value) {
        Instant t = Instant.parse("2026-01-01T00:00:00Z");
        return new UserMemoryEntry(id, userId, key, value, "fact", Visibility.self, agent, List.of(), "conv-old",
                false, 3, t, t);
    }

    @Test
    void newConversation_movesLegacyMemoriesToNamespacedId() throws Exception {
        when(userMemoryStore.getAllEntries(RAW)).thenReturn(List.of(
                entry("m1", RAW, "favourite_colour", "agent-1", "blue"),
                entry("m2", RAW, "city", "agent-1", "Vienna")));
        // The namespaced identity already holds "city" for agent-1 — it wins.
        when(userMemoryStore.getAllEntries(NAMESPACED)).thenReturn(List.of(
                entry("n1", NAMESPACED, "city", "agent-1", "Graz")));

        handler.getOrCreateConversation("agent-1", new SlackUser(RAW, NAMESPACED), INTENT);

        ArgumentCaptor<UserMemoryEntry> moved = ArgumentCaptor.forClass(UserMemoryEntry.class);
        verify(userMemoryStore, times(1)).upsert(moved.capture());
        assertEquals(NAMESPACED, moved.getValue().userId());
        assertEquals("favourite_colour", moved.getValue().key());
        assertEquals("blue", moved.getValue().value());
        // Both legacy copies are removed — the conflicting one lost to the newer
        // identity.
        verify(userMemoryStore).deleteEntry("m1");
        verify(userMemoryStore).deleteEntry("m2");
    }

    @Test
    void memoryMigrationRunsBeforeTheConversationStarts() throws Exception {
        var order = org.mockito.Mockito.inOrder(userMemoryStore, conversationService);
        when(userMemoryStore.getAllEntries(RAW)).thenReturn(List.of(entry("m1", RAW, "k", "agent-1", "v")));
        when(userMemoryStore.getAllEntries(NAMESPACED)).thenReturn(List.of());

        handler.getOrCreateConversation("agent-1", new SlackUser(RAW, NAMESPACED), INTENT);

        order.verify(userMemoryStore).upsert(any());
        order.verify(conversationService).startConversation(any(), any(), eq(NAMESPACED), any());
    }

    @Test
    void memoryMigrationFailure_doesNotFailTheTurn() throws Exception {
        when(userMemoryStore.getAllEntries(RAW)).thenThrow(new IResourceStore.ResourceStoreException("db down"));

        assertEquals("conv-new", handler.getOrCreateConversation("agent-1", new SlackUser(RAW, NAMESPACED), INTENT));
    }

    @Test
    void nonSlackShapedRawId_isNeverLookedUpOrMigrated() throws Exception {
        handler.getOrCreateConversation("agent-1", new SlackUser("alice", "slack:T1:alice"), INTENT);

        verify(userConversationStore, never()).readUserConversation(INTENT, "alice");
        verify(userMemoryStore, never()).getAllEntries("alice");
    }

    // ─── Persisted approval record (notifyApprovers) ───

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

    @Test
    void notifyApprovers_recordsTheCardForThisPause() {
        Instant pausedAt = Instant.ofEpochMilli(5_000L);

        handler.notifyApprovers(resolvedWithApprovalChannel(), "conv-1", "agent-1", bookmarkPausedAt(pausedAt));

        var records = approvalRecords.findBySubject("acme-int", "conv-1");
        assertEquals(1, records.size());
        assertTrue(records.get(0).matchesPause(pausedAt));
    }

    @Test
    void notifyApprovers_idempotencySurvivesARestart() {
        Instant pausedAt = Instant.ofEpochMilli(5_000L);
        handler.notifyApprovers(resolvedWithApprovalChannel(), "conv-1", "agent-1", bookmarkPausedAt(pausedAt));

        // A fresh handler instance over the same persisted store — as after a restart.
        newHandler(approvalRecords).notifyApprovers(resolvedWithApprovalChannel(), "conv-1", "agent-1",
                bookmarkPausedAt(pausedAt));

        verify(slackApi, times(1)).postBlocksMessage(anyString(), eq("C_APPROVAL"), any(), any(), anyString());
    }

    @Test
    void notifyApprovers_recordFailure_postsWithoutButtons() {
        var failing = mock(ISlackApprovalRecordStore.class);
        when(failing.tryRecord(any(), any(), any(), any())).thenThrow(new IllegalStateException("db down"));

        newHandler(failing).notifyApprovers(resolvedWithApprovalChannel(), "conv-1", "agent-1",
                bookmarkPausedAt(Instant.ofEpochMilli(5_000L)));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> blocks = ArgumentCaptor.forClass(List.class);
        verify(slackApi).postBlocksMessage(anyString(), eq("C_APPROVAL"), any(), blocks.capture(), anyString());
        assertFalse(blocks.getValue().toString().contains(SlackHitlSupport.ACTION_APPROVE),
                "a card no decision can be accepted on must not carry buttons");
    }
}
