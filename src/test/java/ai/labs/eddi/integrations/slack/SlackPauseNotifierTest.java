/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.slack;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.channels.model.ChannelIntegrationConfiguration;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.IGroupConversationStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.configs.hitl.model.HitlNotifyConfig;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService;
import ai.labs.eddi.engine.events.GroupHitlPauseCommittedEvent;
import ai.labs.eddi.engine.events.HitlPauseCommittedEvent;
import ai.labs.eddi.engine.events.HitlResumeCompletedEvent;
import ai.labs.eddi.engine.hitl.context.RenderedApprovalContext;
import ai.labs.eddi.engine.hitl.context.RenderedApprovalContext.Item;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.integrations.channels.ChannelTargetRouter;
import ai.labs.eddi.integrations.slack.hitl.ISlackApprovalRecordStore;
import ai.labs.eddi.integrations.slack.hitl.ISlackApprovalRecordStore.SlackApprovalRecord;
import ai.labs.eddi.integrations.slack.hitl.InMemorySlackApprovalRecordStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("SlackPauseNotifier — Slack approval cards for every configured pause")
@SuppressWarnings({"unchecked", "rawtypes"})
class SlackPauseNotifierTest {

    private static final String INT = "ops-slack";
    private static final String CONV = "conv-1";
    private static final String GC = "gc-7";
    private static final String GROUP_SUBJECT = SlackHitlSupport.GROUP_VALUE_PREFIX + GC;
    private static final Instant PAUSED_AT = Instant.parse("2026-11-15T08:00:00Z");
    private static final String PAUSE_ID = HitlDecision.pauseIdOf(PAUSED_AT);
    private static final String TS = "1763193600.000100";

    private IConversationMemoryStore memoryStore;
    private IGroupConversationStore groupConversationStore;
    private IAgentStore agentStore;
    private IAgentGroupStore groupStore;
    private IDocumentDescriptorStore descriptorStore;
    private ChannelTargetRouter router;
    private SlackWebApiClient slackApi;
    private InMemorySlackApprovalRecordStore records;
    private SimpleMeterRegistry meters;
    private AgentConfiguration agent;
    private ChannelIntegrationConfiguration integration;

    @BeforeEach
    void setUp() throws Exception {
        memoryStore = mock(IConversationMemoryStore.class);
        groupConversationStore = mock(IGroupConversationStore.class);
        agentStore = mock(IAgentStore.class);
        groupStore = mock(IAgentGroupStore.class);
        descriptorStore = mock(IDocumentDescriptorStore.class);
        router = mock(ChannelTargetRouter.class);
        slackApi = mock(SlackWebApiClient.class);
        records = new InMemorySlackApprovalRecordStore();
        meters = new SimpleMeterRegistry();

        agent = agentNotifying(INT);
        when(agentStore.read("agent-1", 3)).thenReturn(agent);
        conversationPaused(PAUSED_AT);
        integration = integration(INT, "U_APPROVER");
        when(router.getIntegrationByName("slack", INT)).thenReturn(Optional.of(integration));
        when(slackApi.postBlocksMessage(anyString(), anyString(), any(), any(), anyString())).thenReturn(TS);
        when(slackApi.postBlocksMessage(anyString(), anyString(), any(), any(), anyString(), eq(false))).thenReturn(TS);
    }

    /**
     * The platform config is handed out as a copy: change it by setting it again.
     */
    private void changeConfig(Consumer<Map<String, String>> change) {
        var pc = new HashMap<>(integration.getPlatformConfig());
        change.accept(pc);
        integration.setPlatformConfig(pc);
    }

    private SlackPauseNotifier notifier() {
        return notifier(null);
    }

    private SlackPauseNotifier notifier(String defaultIntegration) {
        var notifier = new SlackPauseNotifier(memoryStore, groupConversationStore, agentStore, groupStore, descriptorStore, router, slackApi,
                records, meters, Optional.ofNullable(defaultIntegration));
        notifier.registerMeters();
        return notifier;
    }

    private static AgentConfiguration agentNotifying(String integrationName) {
        var agent = new AgentConfiguration();
        var hitl = new AgentConfiguration.HitlConfig();
        if (integrationName != null) {
            var notify = new HitlNotifyConfig();
            var slack = new HitlNotifyConfig.Slack();
            slack.setIntegration(integrationName);
            notify.setSlack(slack);
            hitl.setNotify(notify);
        }
        agent.setHitlConfig(hitl);
        return agent;
    }

    private static ChannelIntegrationConfiguration integration(String name, String approvers) {
        var cfg = new ChannelIntegrationConfiguration();
        cfg.setName(name);
        cfg.setChannelType("slack");
        var pc = new HashMap<String, String>();
        pc.put("botToken", "xoxb-ops");
        pc.put("signingSecret", "secret");
        pc.put(SlackHitlSupport.CFG_HITL_APPROVAL_CHANNEL, "C_APPROVALS");
        if (approvers != null) {
            pc.put(SlackHitlSupport.CFG_HITL_APPROVER_USER_IDS, approvers);
        }
        cfg.setPlatformConfig(pc);
        return cfg;
    }

    private ConversationMemorySnapshot conversationPaused(Instant pausedAt) throws Exception {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setConversationState(ConversationState.AWAITING_HUMAN);
        snapshot.setAgentId("agent-1");
        snapshot.setAgentVersion(3);
        snapshot.setHitlPausedAt(pausedAt);
        snapshot.setHitlPauseReason("Send the weekly outreach batch");
        when(memoryStore.getConversationState(CONV)).thenReturn(ConversationState.AWAITING_HUMAN);
        when(memoryStore.loadConversationMemorySnapshot(CONV)).thenReturn(snapshot);
        return snapshot;
    }

    private GroupConversation groupPaused(Instant pausedAt) throws Exception {
        var gc = new GroupConversation();
        gc.setId(GC);
        gc.setGroupId("group-1");
        gc.setState(GroupConversationState.AWAITING_APPROVAL);
        gc.setPausedAt(pausedAt);
        gc.setPausedPhaseName("Review");
        gc.setHitlPauseReason("Requires human approval (PHASE) — phase: Review");
        when(groupConversationStore.read(GC)).thenReturn(gc);
        var group = new AgentGroupConfiguration();
        group.setName("Content team");
        var hitl = new AgentGroupConfiguration.HitlConfig();
        var notify = new HitlNotifyConfig();
        var slack = new HitlNotifyConfig.Slack();
        slack.setIntegration(INT);
        notify.setSlack(slack);
        hitl.setNotify(notify);
        group.setHitlConfig(hitl);
        var resourceId = mock(IResourceStore.IResourceId.class);
        when(resourceId.getVersion()).thenReturn(2);
        when(groupStore.getCurrentResourceId("group-1")).thenReturn(resourceId);
        when(groupStore.read("group-1", 2)).thenReturn(group);
        return gc;
    }

    private List<Map<String, Object>> postedBlocks() {
        ArgumentCaptor<List> blocks = ArgumentCaptor.forClass(List.class);
        verify(slackApi).postBlocksMessage(eq("Bearer xoxb-ops"), eq("C_APPROVALS"), isNull(), blocks.capture(), anyString());
        return blocks.getValue();
    }

    private static String buttonValue(List<Map<String, Object>> blocks) {
        for (Map<String, Object> block : blocks) {
            if ("actions".equals(block.get("type"))) {
                var elements = (List<Map<String, Object>>) block.get("elements");
                return (String) elements.get(0).get("value");
            }
        }
        return null;
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

    private double count(String outcome) {
        return meters.counter(SlackPauseNotifier.METER, "outcome", outcome).count();
    }

    @Nested
    @DisplayName("conversation pauses")
    class Conversations {

        @Test
        @DisplayName("a configured agent's pause posts the card with buttons bound to this integration, card and pause")
        void postsBoundCard() {
            notifier().onPauseCommitted(new HitlPauseCommittedEvent(CONV, PAUSE_ID));

            List<Map<String, Object>> blocks = postedBlocks();
            String value = buttonValue(blocks);
            assertNotNull(value, "approvers are configured, so the card has buttons");
            SlackHitlSupport.ActionValue parsed = SlackHitlSupport.parseActionValue(value);
            assertEquals(INT, parsed.integrationName());
            assertEquals(CONV, parsed.subject());

            SlackApprovalRecord record = records.get(INT, CONV, PAUSE_ID);
            assertNotNull(record, "the card is recorded for exactly this pause");
            assertTrue(record.matchesCard(parsed.cardId()));
            assertTrue(record.matchesPause(PAUSED_AT));
            assertEquals(TS, record.messageTs(), "the card's ts is kept for the resolution update");
            assertEquals("C_APPROVALS", record.approvalChannelId());
            assertTrue(flatten(blocks).contains("Send the weekly outreach batch"));
            assertEquals(1.0, count("posted"));
        }

        @Test
        @DisplayName("the agent's name is on the card when its descriptor has one")
        void agentNameOnCard() throws Exception {
            var descriptor = new DocumentDescriptor();
            descriptor.setName("Weekly outreach");
            when(descriptorStore.readDescriptor("agent-1", 3)).thenReturn(descriptor);

            notifier().onPauseCommitted(new HitlPauseCommittedEvent(CONV, PAUSE_ID));

            assertTrue(flatten(postedBlocks()).contains("Weekly outreach (agent-1)"));
        }

        @Test
        @DisplayName("nothing configured and no deployment default: off — no card, no record")
        void offByDefault() throws Exception {
            when(agentStore.read("agent-1", 3)).thenReturn(agentNotifying(null));

            notifier().onPauseCommitted(new HitlPauseCommittedEvent(CONV, PAUSE_ID));

            verify(slackApi, never()).postBlocksMessage(anyString(), anyString(), any(), any(), anyString());
            assertEquals(0, records.size());
        }

        @Test
        @DisplayName("the deployment default applies when the agent names no integration")
        void deploymentDefault() throws Exception {
            when(agentStore.read("agent-1", 3)).thenReturn(agentNotifying(null));

            notifier(" " + INT + " ").onPauseCommitted(new HitlPauseCommittedEvent(CONV, PAUSE_ID));

            assertNotNull(buttonValue(postedBlocks()));
            assertNotNull(records.get(INT, CONV, PAUSE_ID));
        }

        @Test
        @DisplayName("the agent's own integration wins over the deployment default")
        void agentWinsOverDefault() {
            notifier("other-slack").onPauseCommitted(new HitlPauseCommittedEvent(CONV, PAUSE_ID));

            assertNotNull(records.get(INT, CONV, PAUSE_ID));
            verify(router, never()).getIntegrationByName("slack", "other-slack");
        }

        @Test
        @DisplayName("a pause decided before the observer ran gets no card")
        void stalePause() throws Exception {
            conversationPaused(PAUSED_AT.plusSeconds(60));

            notifier().onPauseCommitted(new HitlPauseCommittedEvent(CONV, PAUSE_ID));

            verify(slackApi, never()).postBlocksMessage(anyString(), anyString(), any(), any(), anyString());
            assertEquals(0, records.size());
        }

        @Test
        @DisplayName("a conversation that is no longer paused gets no card")
        void notPaused() {
            when(memoryStore.getConversationState(CONV)).thenReturn(ConversationState.READY);

            notifier().onPauseCommitted(new HitlPauseCommittedEvent(CONV, PAUSE_ID));

            verify(slackApi, never()).postBlocksMessage(anyString(), anyString(), any(), any(), anyString());
        }

        @Test
        @DisplayName("one card per pause and integration: a second announcement (or the Slack thread path) posts nothing")
        void onePerPause() {
            var notifier = notifier();
            notifier.onPauseCommitted(new HitlPauseCommittedEvent(CONV, PAUSE_ID));
            notifier.onPauseCommitted(new HitlPauseCommittedEvent(CONV, PAUSE_ID));

            verify(slackApi).postBlocksMessage(anyString(), anyString(), any(), any(), anyString());
            assertEquals(1.0, count("duplicate"));
        }

        @Test
        @DisplayName("without approver ids the card is a notification without buttons")
        void noApproversNoButtons() {
            changeConfig(pc -> pc.remove(SlackHitlSupport.CFG_HITL_APPROVER_USER_IDS));

            notifier().onPauseCommitted(new HitlPauseCommittedEvent(CONV, PAUSE_ID));

            List<Map<String, Object>> blocks = postedBlocks();
            assertNull(buttonValue(blocks));
            assertTrue(flatten(blocks).contains(SlackHitlSupport.NO_APPROVERS_NOTE));
        }

        @Test
        @DisplayName("an unknown integration name posts nothing and is counted")
        void unknownIntegration() throws Exception {
            when(agentStore.read("agent-1", 3)).thenReturn(agentNotifying("nobody"));
            when(router.getIntegrationByName("slack", "nobody")).thenReturn(Optional.empty());

            notifier().onPauseCommitted(new HitlPauseCommittedEvent(CONV, PAUSE_ID));

            verify(slackApi, never()).postBlocksMessage(anyString(), anyString(), any(), any(), anyString());
            assertEquals(1.0, count("no_integration"));
        }

        @Test
        @DisplayName("an integration without an approval channel posts nothing and writes no record")
        void noApprovalChannel() {
            changeConfig(pc -> pc.remove(SlackHitlSupport.CFG_HITL_APPROVAL_CHANNEL));

            notifier().onPauseCommitted(new HitlPauseCommittedEvent(CONV, PAUSE_ID));

            verify(slackApi, never()).postBlocksMessage(anyString(), anyString(), any(), any(), anyString());
            assertEquals(0, records.size());
            assertEquals(1.0, count("no_channel"));
        }

        @Test
        @DisplayName("an integration without a bot token posts nothing — no other integration's token is used")
        void noToken() {
            changeConfig(pc -> pc.remove("botToken"));

            notifier().onPauseCommitted(new HitlPauseCommittedEvent(CONV, PAUSE_ID));

            verify(slackApi, never()).postBlocksMessage(anyString(), anyString(), any(), any(), anyString());
            assertEquals(0, records.size());
            assertEquals(1.0, count("no_token"));
        }

        @Test
        @DisplayName("a refused post gives the record back, so a later announcement can retry")
        void failedPostForgetsRecord() {
            when(slackApi.postBlocksMessage(anyString(), anyString(), any(), any(), anyString())).thenReturn(null);

            notifier().onPauseCommitted(new HitlPauseCommittedEvent(CONV, PAUSE_ID));

            assertEquals(0, records.size());
            assertEquals(1.0, count("failed"));
        }

        @Test
        @DisplayName("a delivery exception gives the record back too")
        void deliveryExceptionForgetsRecord() {
            when(slackApi.postBlocksMessage(anyString(), anyString(), any(), any(), anyString()))
                    .thenThrow(new SlackDeliveryException("503"));

            notifier().onPauseCommitted(new HitlPauseCommittedEvent(CONV, PAUSE_ID));

            assertEquals(0, records.size());
        }

        @Test
        @DisplayName("a record store failure posts the card without buttons (fail closed)")
        void recordFailureFailsClosed() {
            var failing = mock(ISlackApprovalRecordStore.class);
            when(failing.tryRecord(anyString(), anyString(), anyString(), anyString(), anyString())).thenThrow(new IllegalStateException("db"));
            var notifier = new SlackPauseNotifier(memoryStore, groupConversationStore, agentStore, groupStore, descriptorStore, router,
                    slackApi, failing, meters, Optional.empty());

            notifier.onPauseCommitted(new HitlPauseCommittedEvent(CONV, PAUSE_ID));

            assertNull(buttonValue(postedBlocks()));
            verify(failing, never()).markPosted(anyString(), anyString(), anyString(), anyString());
        }

        @Test
        @DisplayName("approval context: only for an opted-in integration, and then with unfurling off")
        void approvalContextOptIn() throws Exception {
            var snapshot = conversationPaused(PAUSED_AT);
            snapshot.setHitlApprovalContext(
                    RenderedApprovalContext.of(List.of(new Item("Draft", "Hello <!channel>", false, "agent", null, null)), false));

            notifier().onPauseCommitted(new HitlPauseCommittedEvent(CONV, PAUSE_ID));
            assertFalse(flatten(postedBlocks()).contains("Hello <!channel>"), "not opted in: no conversation text");

            records = new InMemorySlackApprovalRecordStore();
            changeConfig(pc -> pc.put(SlackHitlSupport.CFG_HITL_APPROVAL_CONTEXT, "true"));
            notifier().onPauseCommitted(new HitlPauseCommittedEvent(CONV, PAUSE_ID));
            ArgumentCaptor<List> blocks = ArgumentCaptor.forClass(List.class);
            verify(slackApi).postBlocksMessage(eq("Bearer xoxb-ops"), eq("C_APPROVALS"), isNull(), blocks.capture(), anyString(), eq(false));
            assertTrue(flatten(blocks.getValue()).contains("Hello <!channel>"));
        }
    }

    @Nested
    @DisplayName("group pauses")
    class Groups {

        @Test
        @DisplayName("a configured group's pause posts a group card bound to group:<id>")
        void postsGroupCard() throws Exception {
            groupPaused(PAUSED_AT);

            notifier().onGroupPauseCommitted(new GroupHitlPauseCommittedEvent(GC, PAUSE_ID));

            List<Map<String, Object>> blocks = postedBlocks();
            SlackHitlSupport.ActionValue parsed = SlackHitlSupport.parseActionValue(buttonValue(blocks));
            assertEquals(GROUP_SUBJECT, parsed.subject());
            SlackApprovalRecord record = records.get(INT, GROUP_SUBJECT, PAUSE_ID);
            assertTrue(record.matchesCard(parsed.cardId()));
            assertEquals(TS, record.messageTs());
            assertTrue(flatten(blocks).contains("Content team — Review"));
        }

        @Test
        @DisplayName("a group discussion that moved on before the observer ran gets no card")
        void staleGroupPause() throws Exception {
            groupPaused(PAUSED_AT).setState(GroupConversationState.IN_PROGRESS);

            notifier().onGroupPauseCommitted(new GroupHitlPauseCommittedEvent(GC, PAUSE_ID));

            verify(slackApi, never()).postBlocksMessage(anyString(), anyString(), any(), any(), anyString());
        }

        @Test
        @DisplayName("a group without notify uses the deployment default; without one it is off")
        void groupDefault() throws Exception {
            groupPaused(PAUSED_AT);
            groupStore.read("group-1", 2).getHitlConfig().setNotify(null);

            notifier().onGroupPauseCommitted(new GroupHitlPauseCommittedEvent(GC, PAUSE_ID));
            verify(slackApi, never()).postBlocksMessage(anyString(), anyString(), any(), any(), anyString());

            notifier(INT).onGroupPauseCommitted(new GroupHitlPauseCommittedEvent(GC, PAUSE_ID));
            assertNotNull(records.get(INT, GROUP_SUBJECT, PAUSE_ID));
        }
    }

    @Nested
    @DisplayName("deciding on the card")
    class Deciding {

        private IConversationService conversationService;
        private SlackInteractivityHandler handler;

        @BeforeEach
        void handler() {
            conversationService = mock(IConversationService.class);
            handler = new SlackInteractivityHandler(router, conversationService, mock(IGroupConversationService.class), slackApi,
                    new ObjectMapper(), records);
        }

        private String approve(String value) {
            return """
                    {"type":"block_actions","user":{"id":"U_APPROVER"},"channel":{"id":"C_APPROVALS"},
                     "message":{"ts":"%s"},"actions":[{"action_id":"hitl_approve","value":"%s"}]}
                    """.formatted(TS, value);
        }

        @Test
        @DisplayName("an approver's click on the posted card resumes the scheduled run's conversation")
        void clickResumes() throws Exception {
            var snapshot = conversationPaused(PAUSED_AT);
            when(conversationService.getConversationMemorySnapshot(CONV)).thenReturn(snapshot);
            notifier().onPauseCommitted(new HitlPauseCommittedEvent(CONV, PAUSE_ID));

            handler.handlePayload(approve(buttonValue(postedBlocks())));

            ArgumentCaptor<HitlDecision> decision = ArgumentCaptor.forClass(HitlDecision.class);
            verify(conversationService).resumeConversation(eq(CONV), decision.capture(), isNull());
            assertEquals(HitlVerdict.APPROVED, decision.getValue().getVerdict());
            assertEquals(PAUSE_ID, decision.getValue().getPauseId());
        }

        @Test
        @DisplayName("the card cannot approve a later pause of the same conversation")
        void cardBoundToItsPause() throws Exception {
            conversationPaused(PAUSED_AT);
            notifier().onPauseCommitted(new HitlPauseCommittedEvent(CONV, PAUSE_ID));
            String value = buttonValue(postedBlocks());
            var later = conversationPaused(PAUSED_AT.plusSeconds(3600));
            when(conversationService.getConversationMemorySnapshot(CONV)).thenReturn(later);

            handler.handlePayload(approve(value));

            verify(conversationService, never()).resumeConversation(any(), any(), any());
        }

        @Test
        @DisplayName("a user who is not on the integration's approver list cannot decide")
        void nonApproverRefused() throws Exception {
            var snapshot = conversationPaused(PAUSED_AT);
            when(conversationService.getConversationMemorySnapshot(CONV)).thenReturn(snapshot);
            notifier().onPauseCommitted(new HitlPauseCommittedEvent(CONV, PAUSE_ID));

            handler.handlePayload(approve(buttonValue(postedBlocks())).replace("U_APPROVER", "U_SOMEONE"));

            verify(conversationService, never()).resumeConversation(any(), any(), any());
        }
    }

    @Nested
    @DisplayName("resolution")
    class Resolution {

        private SimpleConversationMemorySnapshot resumed() {
            var snapshot = new SimpleConversationMemorySnapshot();
            snapshot.setAgentId("agent-1");
            snapshot.setAgentVersion(3);
            snapshot.setConversationState(ConversationState.READY);
            return snapshot;
        }

        @Test
        @DisplayName("a resume decided anywhere replaces the card's buttons with the outcome and drops the record")
        void resumeUpdatesCard() {
            var notifier = notifier();
            notifier.onPauseCommitted(new HitlPauseCommittedEvent(CONV, PAUSE_ID));

            notifier.onResumeCompleted(new HitlResumeCompletedEvent(CONV, HitlVerdict.REJECTED, "admin", resumed()));

            ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
            verify(slackApi).updateMessage(eq("Bearer xoxb-ops"), eq("C_APPROVALS"), eq(TS), text.capture(), any());
            assertTrue(text.getValue().startsWith("⛔ Approval rejected by admin"), text.getValue());
            assertEquals(0, records.size());
        }

        @Test
        @DisplayName("a timeout shows as cancelled or expired")
        void timeoutUpdatesCard() {
            var notifier = notifier();
            notifier.onPauseCommitted(new HitlPauseCommittedEvent(CONV, PAUSE_ID));

            notifier.onResumeCompleted(new HitlResumeCompletedEvent(CONV, null, "system:timeout", resumed()));

            ArgumentCaptor<String> text = ArgumentCaptor.forClass(String.class);
            verify(slackApi).updateMessage(anyString(), anyString(), eq(TS), text.capture(), any());
            assertTrue(text.getValue().contains("cancelled or expired"), text.getValue());
        }

        @Test
        @DisplayName("the sweep leaves a card whose pause is still current")
        void sweepKeepsLiveCard() {
            var notifier = notifier();
            notifier.onPauseCommitted(new HitlPauseCommittedEvent(CONV, PAUSE_ID));

            notifier.sweepOnce();

            verify(slackApi, never()).updateMessage(anyString(), anyString(), anyString(), anyString(), any());
            assertEquals(1, records.size());
        }

        @Test
        @DisplayName("the sweep resolves a card whose conversation was decided without an event here (another pod, a cancel)")
        void sweepResolvesConversation() {
            var notifier = notifier();
            notifier.onPauseCommitted(new HitlPauseCommittedEvent(CONV, PAUSE_ID));
            when(memoryStore.getConversationState(CONV)).thenReturn(ConversationState.ENDED);

            notifier.sweepOnce();

            verify(slackApi).updateMessage(eq("Bearer xoxb-ops"), eq("C_APPROVALS"), eq(TS), eq(SlackPauseNotifier.RESOLVED_TEXT), any());
            assertEquals(0, records.size());
            assertEquals(1.0, count("resolved"));
        }

        @Test
        @DisplayName("the sweep resolves a card whose pause was replaced by a newer one")
        void sweepResolvesReplacedPause() throws Exception {
            var notifier = notifier();
            notifier.onPauseCommitted(new HitlPauseCommittedEvent(CONV, PAUSE_ID));
            conversationPaused(PAUSED_AT.plusSeconds(600));

            notifier.sweepOnce();

            verify(slackApi).updateMessage(anyString(), anyString(), eq(TS), eq(SlackPauseNotifier.RESOLVED_TEXT), any());
        }

        @Test
        @DisplayName("a subject that cannot be read is left for the next sweep")
        void sweepUnreadableKeeps() throws Exception {
            var notifier = notifier();
            notifier.onPauseCommitted(new HitlPauseCommittedEvent(CONV, PAUSE_ID));
            when(memoryStore.loadConversationMemorySnapshot(CONV)).thenThrow(new IResourceStore.ResourceStoreException("down"));

            notifier.sweepOnce();

            verify(slackApi, never()).updateMessage(anyString(), anyString(), anyString(), anyString(), any());
            assertEquals(1, records.size());
        }

        @Test
        @DisplayName("group cards: a rejection and a cancel each say what happened")
        void sweepGroupOutcomes() throws Exception {
            var gc = groupPaused(PAUSED_AT);
            var notifier = notifier();
            notifier.onGroupPauseCommitted(new GroupHitlPauseCommittedEvent(GC, PAUSE_ID));

            notifier.sweepOnce();
            verify(slackApi, never()).updateMessage(anyString(), anyString(), anyString(), anyString(), any());

            gc.setState(GroupConversationState.REJECTED);
            notifier.sweepOnce();
            verify(slackApi).updateMessage(anyString(), anyString(), eq(TS), eq(SlackPauseNotifier.GROUP_REJECTED_TEXT), any());
            assertEquals(0, records.size());

            gc.setState(GroupConversationState.CANCELLED);
            assertEquals(SlackPauseNotifier.GROUP_CANCELLED_TEXT,
                    notifier.resolutionText(new SlackApprovalRecord(INT, GROUP_SUBJECT, PAUSE_ID, "c", "C", PAUSED_AT, PAUSED_AT, TS)));
            gc.setState(GroupConversationState.IN_PROGRESS);
            assertEquals(SlackPauseNotifier.GROUP_DECIDED_TEXT,
                    notifier.resolutionText(new SlackApprovalRecord(INT, GROUP_SUBJECT, PAUSE_ID, "c", "C", PAUSED_AT, PAUSED_AT, TS)));
        }

        @Test
        @DisplayName("a card posted by the Slack thread path (no ts) is not touched")
        void untrackedCardsUntouched() {
            records.tryRecord(INT, CONV, PAUSE_ID, ISlackApprovalRecordStore.newCardId(), "C_APPROVALS");
            when(memoryStore.getConversationState(CONV)).thenReturn(ConversationState.ENDED);

            notifier().sweepOnce();

            verify(slackApi, never()).updateMessage(anyString(), anyString(), anyString(), anyString(), any());
            assertEquals(1, records.size());
        }
    }
}
