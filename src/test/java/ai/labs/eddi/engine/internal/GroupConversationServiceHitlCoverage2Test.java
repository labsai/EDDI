/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.AgentSigningService;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.crypto.NonceCacheService;
import ai.labs.eddi.configs.hitl.HitlTimeoutPolicy;
import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.IGroupConversationStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ContextScope;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionStyle;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.LifecyclePolicy;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.PhaseType;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.TurnOrder;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.configs.groups.model.GroupConversation.HitlPauseType;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.configs.groups.model.SharedTaskList;
import ai.labs.eddi.configs.groups.model.SharedTaskList.TaskItem;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionEventListener;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.lifecycle.model.ControlSignal;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Batch-2 branch-coverage tests for {@link GroupConversationService} HITL/group
 * machinery. Targets branches NOT already covered by
 * {@link GroupConversationServiceHitlTest} or the batch-1
 * {@link GroupConversationServiceHitlCoverageTest}: member-pause / tool-pause
 * resolution, {@code restoreGroupPause} config-fallback branches,
 * {@code failConversation}, {@code cleanupEphemeralAgents} lifecycle policies,
 * {@code cleanupAfterTerminalState}, participant/phase/protocol resolution, and
 * {@code taskPauseFingerprint} / {@code propagateDynamicAgentTracking} edges.
 */
class GroupConversationServiceHitlCoverage2Test {

    @Mock
    private IAgentGroupStore groupStore;
    @Mock
    private IGroupConversationStore conversationStore;
    @Mock
    private IConversationService conversationService;
    @Mock
    private IAgentFactory agentFactory;
    @Mock
    private ITemplatingEngine templatingEngine;
    @Mock
    private IJsonSerialization jsonSerialization;
    @Mock
    private AgentSigningService agentSigningService;
    @Mock
    private IAgentStore agentStore;
    @Mock
    private NonceCacheService nonceCacheService;
    @Mock
    private IScheduleStore scheduleStore;

    private GroupConversationService service;

    private static final int MAX_DEPTH = 3;
    private static final String DEFAULT_TENANT = "default";
    private static final String GROUP_ID = "group-hitl-cov2";
    private static final String USER_ID = "user-hitl-cov2";
    private static final String MOD_AGENT = "mod-agent";
    private static final String AGENT_A = "agent-a";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new GroupConversationService(
                groupStore, conversationStore, conversationService,
                agentFactory, templatingEngine, jsonSerialization,
                new SimpleMeterRegistry(), agentSigningService, agentStore,
                scheduleStore, nonceCacheService, null, DEFAULT_TENANT, MAX_DEPTH);
    }

    // =================================================================
    // Helpers
    // =================================================================

    private AgentGroupConfiguration buildConfig(List<DiscussionPhase> phases) {
        var config = new AgentGroupConfiguration();
        config.setName("HITL Coverage2 Group");
        config.setStyle(DiscussionStyle.CUSTOM);
        config.setPhases(phases);
        config.setMembers(List.of(new GroupMember(AGENT_A, "Agent A", 1, null)));
        config.setModeratorAgentId(MOD_AGENT);
        return config;
    }

    private GroupMember member() {
        return new GroupMember(AGENT_A, "Agent A", 1, null);
    }

    private DiscussionPhase phase(PhaseType type) {
        return new DiscussionPhase("P-" + type, type,
                "ALL", TurnOrder.SEQUENTIAL, ContextScope.FULL, false, null, 1, false);
    }

    private GroupConversation pausedPhaseGc(String id) {
        var gc = new GroupConversation();
        gc.setId(id);
        gc.setGroupId(GROUP_ID);
        gc.setUserId(USER_ID);
        gc.setState(GroupConversationState.AWAITING_APPROVAL);
        gc.setPausedAtPhaseIndex(0);
        gc.setPausedPhaseName("Gate");
        gc.setPausedAt(Instant.now());
        gc.setHitlPauseType(HitlPauseType.PHASE);
        gc.setOriginalQuestion("Coverage2 test");
        return gc;
    }

    private Method method(String name, Class<?>... params) throws NoSuchMethodException {
        Method m = GroupConversationService.class.getDeclaredMethod(name, params);
        m.setAccessible(true);
        return m;
    }

    private Object invoke(Method m, Object... args) throws Exception {
        try {
            return m.invoke(service, args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception ex) {
                throw ex;
            }
            throw e;
        }
    }

    private SimpleConversationMemorySnapshot snapshot(ConversationState state, String text) {
        var snap = new SimpleConversationMemorySnapshot();
        snap.setConversationState(state);
        var output = new ai.labs.eddi.engine.memory.model.ConversationOutput();
        output.put("output", List.of(text));
        snap.setConversationOutputs(new java.util.ArrayList<>(List.of(output)));
        return snap;
    }

    // =================================================================
    // handleMemberPause() — cancel success / cancel-throws + SKIPPED entry
    // =================================================================

    @Test
    @DisplayName("handleMemberPause: cancel succeeds → SKIPPED entry with pause note")
    void handleMemberPauseCancelSucceeds() throws Exception {
        var gc = pausedPhaseGc("gc-member-pause");
        var m = method("handleMemberPause", GroupMember.class, GroupConversation.class,
                String.class, int.class, DiscussionPhase.class, String.class,
                GroupDiscussionEventListener.class);

        var entry = (TranscriptEntry) invoke(m, member(), gc, "conv-1", 2,
                phase(PhaseType.OPINION), null, null);

        assertEquals(TranscriptEntryType.SKIPPED, entry.type());
        assertEquals(AGENT_A, entry.speakerAgentId());
        assertNotNull(entry.errorReason());
        verify(conversationService).cancelConversation("conv-1",
                ControlSignal.CANCEL_GRACEFUL, "system:group");
    }

    @Test
    @DisplayName("handleMemberPause: cancel throws → still returns SKIPPED (best-effort)")
    void handleMemberPauseCancelThrows() throws Exception {
        var gc = pausedPhaseGc("gc-member-pause-throw");
        doThrow(new RuntimeException("cancel failed"))
                .when(conversationService).cancelConversation(anyString(), any(), anyString());
        var m = method("handleMemberPause", GroupMember.class, GroupConversation.class,
                String.class, int.class, DiscussionPhase.class, String.class,
                GroupDiscussionEventListener.class);

        var entry = (TranscriptEntry) invoke(m, member(), gc, "conv-2", 0,
                phase(PhaseType.OPINION), "target-x", null);

        assertEquals(TranscriptEntryType.SKIPPED, entry.type());
        assertEquals("target-x", entry.targetAgentId());
    }

    @Test
    @DisplayName("handleMemberPause: with listener → onMemberPauseSkipped fired")
    void handleMemberPauseFiresListener() throws Exception {
        var gc = pausedPhaseGc("gc-member-pause-listener");
        var listener = mock(GroupDiscussionEventListener.class);
        var m = method("handleMemberPause", GroupMember.class, GroupConversation.class,
                String.class, int.class, DiscussionPhase.class, String.class,
                GroupDiscussionEventListener.class);

        invoke(m, member(), gc, "conv-3", 1, phase(PhaseType.CRITIQUE), null, listener);

        verify(listener).onMemberPauseSkipped(any());
    }

    // =================================================================
    // tryResolveMemberToolPause() — resume start failure / timeout / re-pause /
    // success
    // =================================================================

    private Method toolPauseMethod() throws NoSuchMethodException {
        return method("tryResolveMemberToolPause", GroupMember.class, GroupConversation.class,
                String.class, String.class, int.class, int.class, DiscussionPhase.class,
                TranscriptEntryType.class, String.class);
    }

    @Test
    @DisplayName("tryResolveMemberToolPause: resume start throws → null (fall back to SKIP+cancel)")
    void toolPauseResumeStartThrows() throws Exception {
        var gc = pausedPhaseGc("gc-tool-start-throw");
        doThrow(new RuntimeException("resume start failed"))
                .when(conversationService).resumeConversation(anyString(), any(), any());

        Object result = invoke(toolPauseMethod(), member(), gc, "conv-tp1", "input",
                5, 0, phase(PhaseType.OPINION), TranscriptEntryType.OPINION, null);

        assertNull(result, "A resume start failure must fall back (return null)");
    }

    @Test
    @DisplayName("tryResolveMemberToolPause: onSkipped callback → null")
    void toolPauseOnSkippedReturnsNull() throws Exception {
        var gc = pausedPhaseGc("gc-tool-skipped");
        doAnswer(inv -> {
            IConversationService.ConversationResponseHandler h = inv.getArgument(2);
            h.onSkipped(snapshot(ConversationState.READY, "ignored"));
            return null;
        }).when(conversationService).resumeConversation(anyString(), any(), any());

        Object result = invoke(toolPauseMethod(), member(), gc, "conv-tp2", "input",
                5, 0, phase(PhaseType.OPINION), TranscriptEntryType.OPINION, null);

        assertNull(result, "onSkipped resolves the future to null → fall back");
    }

    @Test
    @DisplayName("tryResolveMemberToolPause: resumed still AWAITING_HUMAN (re-paused) → null")
    void toolPauseRePausedReturnsNull() throws Exception {
        var gc = pausedPhaseGc("gc-tool-repaused");
        doAnswer(inv -> {
            IConversationService.ConversationResponseHandler h = inv.getArgument(2);
            h.onComplete(snapshot(ConversationState.AWAITING_HUMAN, "still paused"));
            return null;
        }).when(conversationService).resumeConversation(anyString(), any(), any());

        Object result = invoke(toolPauseMethod(), member(), gc, "conv-tp3", "input",
                5, 0, phase(PhaseType.OPINION), TranscriptEntryType.OPINION, null);

        assertNull(result, "A re-paused snapshot must fall back (return null)");
    }

    @Test
    @DisplayName("tryResolveMemberToolPause: graceful success → real contribution entry")
    void toolPauseSuccessReturnsEntry() throws Exception {
        var gc = pausedPhaseGc("gc-tool-success");
        doAnswer(inv -> {
            IConversationService.ConversationResponseHandler h = inv.getArgument(2);
            h.onComplete(snapshot(ConversationState.READY, "Tool-less answer"));
            return null;
        }).when(conversationService).resumeConversation(anyString(), any(), any());

        Object result = invoke(toolPauseMethod(), member(), gc, "conv-tp4", "input",
                5, 3, phase(PhaseType.CRITIQUE), TranscriptEntryType.CRITIQUE, "peer-1");

        assertNotNull(result, "Graceful success returns a real contribution");
        var entry = (TranscriptEntry) result;
        assertEquals(TranscriptEntryType.CRITIQUE, entry.type());
        assertEquals(AGENT_A, entry.speakerAgentId());
        assertEquals("peer-1", entry.targetAgentId());
    }

    @Test
    @DisplayName("tryResolveMemberToolPause: ERROR state with empty output → placeholder response entry")
    void toolPauseErrorStateEmptyOutput() throws Exception {
        var gc = pausedPhaseGc("gc-tool-error");
        // ERROR state + no output items → the empty-and-ERROR branch fills a
        // placeholder
        var errSnap = new SimpleConversationMemorySnapshot();
        errSnap.setConversationState(ConversationState.ERROR);
        errSnap.setConversationOutputs(new java.util.ArrayList<>());
        doAnswer(inv -> {
            IConversationService.ConversationResponseHandler h = inv.getArgument(2);
            h.onComplete(errSnap);
            return null;
        }).when(conversationService).resumeConversation(anyString(), any(), any());

        Object result = invoke(toolPauseMethod(), member(), gc, "conv-tp5", "input",
                5, 0, phase(PhaseType.OPINION), TranscriptEntryType.OPINION, null);

        assertNotNull(result);
        var entry = (TranscriptEntry) result;
        assertNotNull(entry.content(), "ERROR + empty output yields a placeholder content string");
        assertTrue(entry.content().contains("ERROR"), entry.content());
    }

    // =================================================================
    // restoreGroupPause() branches
    // =================================================================

    private Method restoreMethod() throws NoSuchMethodException {
        return method("restoreGroupPause", GroupConversation.class, int.class, String.class,
                HitlPauseType.class, Instant.class, AgentGroupConfiguration.class,
                String.class, String.class);
    }

    @Test
    @DisplayName("restoreGroupPause: config provided with finite policy → re-arms AWAITING_APPROVAL + schedule")
    void restoreWithConfigFinitePolicy() throws Exception {
        var gc = pausedPhaseGc("gc-restore-config");
        gc.setState(GroupConversationState.IN_PROGRESS); // resume flipped it
        var config = buildConfig(List.of(phase(PhaseType.OPINION)));
        var hitl = new AgentGroupConfiguration.HitlConfig();
        hitl.setTimeoutPolicy(HitlTimeoutPolicy.AUTO_REJECT);
        hitl.setApprovalTimeout("PT30M");
        config.setHitlConfig(hitl);

        var pausedAt = Instant.now();
        invoke(restoreMethod(), gc, 1, "Gate", HitlPauseType.PHASE, pausedAt, config,
                HitlTimeoutPolicy.WAIT_INDEFINITELY.name(), null);

        assertEquals(GroupConversationState.AWAITING_APPROVAL, gc.getState());
        assertEquals(1, gc.getPausedAtPhaseIndex());
        assertEquals(pausedAt, gc.getPausedAt());
        assertEquals(HitlTimeoutPolicy.AUTO_REJECT.name(), gc.getHitlTimeoutPolicy());
        verify(conversationStore).updateIfState(gc, GroupConversationState.IN_PROGRESS);
        verify(scheduleStore).createSchedule(any());
    }

    @Test
    @DisplayName("restoreGroupPause: null pauseType → defaults to PHASE")
    void restoreNullPauseTypeDefaultsPhase() throws Exception {
        var gc = pausedPhaseGc("gc-restore-null-type");
        gc.setState(GroupConversationState.IN_PROGRESS);
        var config = buildConfig(List.of(phase(PhaseType.OPINION))); // no hitlConfig

        invoke(restoreMethod(), gc, 0, "Gate", null, Instant.now(), config,
                HitlTimeoutPolicy.WAIT_INDEFINITELY.name(), null);

        assertEquals(HitlPauseType.PHASE, gc.getHitlPauseType(),
                "null pauseType must default to PHASE");
    }

    @Test
    @DisplayName("restoreGroupPause: null config, store read succeeds → uses fresh config")
    void restoreNullConfigStoreReadSucceeds() throws Exception {
        var gc = pausedPhaseGc("gc-restore-fresh");
        gc.setState(GroupConversationState.IN_PROGRESS);
        var resId = new IResourceStore.IResourceId() {
            public String getId() {
                return GROUP_ID;
            }
            public Integer getVersion() {
                return 1;
            }
        };
        doReturn(resId).when(groupStore).getCurrentResourceId(GROUP_ID);
        var config = buildConfig(List.of(phase(PhaseType.OPINION)));
        var hitl = new AgentGroupConfiguration.HitlConfig();
        hitl.setTimeoutPolicy(HitlTimeoutPolicy.ABORT);
        hitl.setApprovalTimeout("PT10M");
        config.setHitlConfig(hitl);
        doReturn(config).when(groupStore).read(GROUP_ID, 1);

        invoke(restoreMethod(), gc, 0, "Gate", HitlPauseType.PHASE, Instant.now(), null,
                "FALLBACK_POLICY", "PT99M");

        assertEquals(HitlTimeoutPolicy.ABORT.name(), gc.getHitlTimeoutPolicy(),
                "fresh config policy wins over the fallback");
    }

    @Test
    @DisplayName("restoreGroupPause: null config + store read throws → falls back to captured bookmark")
    void restoreNullConfigStoreReadThrows() throws Exception {
        var gc = pausedPhaseGc("gc-restore-fallback");
        gc.setState(GroupConversationState.IN_PROGRESS);
        doThrow(new RuntimeException("store down")).when(groupStore).getCurrentResourceId(GROUP_ID);

        invoke(restoreMethod(), gc, 0, "Gate", HitlPauseType.PHASE, Instant.now(), null,
                HitlTimeoutPolicy.AUTO_REJECT.name(), "PT20M");

        assertEquals(HitlTimeoutPolicy.AUTO_REJECT.name(), gc.getHitlTimeoutPolicy(),
                "captured fallback policy is used when config is unreadable");
        assertEquals("PT20M", gc.getHitlApprovalTimeout());
    }

    @Test
    @DisplayName("restoreGroupPause: null config + resId null → fallback bookmark (config null branch)")
    void restoreNullConfigResIdNull() throws Exception {
        var gc = pausedPhaseGc("gc-restore-resid-null");
        gc.setState(GroupConversationState.IN_PROGRESS);
        doReturn(null).when(groupStore).getCurrentResourceId(GROUP_ID);

        invoke(restoreMethod(), gc, 0, "Gate", HitlPauseType.PHASE, Instant.now(), null,
                HitlTimeoutPolicy.ABORT.name(), "PT15M");

        assertEquals(HitlTimeoutPolicy.ABORT.name(), gc.getHitlTimeoutPolicy());
    }

    @Test
    @DisplayName("restoreGroupPause: updateIfState throws → caught, no propagation")
    void restoreUpdateThrowsSwallowed() throws Exception {
        var gc = pausedPhaseGc("gc-restore-update-throw");
        gc.setState(GroupConversationState.IN_PROGRESS);
        var config = buildConfig(List.of(phase(PhaseType.OPINION)));
        doThrow(new IResourceStore.ResourceModifiedException("raced"))
                .when(conversationStore).updateIfState(any(), eq(GroupConversationState.IN_PROGRESS));

        assertDoesNotThrow(() -> invoke(restoreMethod(), gc, 0, "Gate", HitlPauseType.PHASE,
                Instant.now(), config, HitlTimeoutPolicy.WAIT_INDEFINITELY.name(), null));
    }

    // =================================================================
    // failConversation() branches
    // =================================================================

    @Test
    @DisplayName("failConversation: update succeeds → FAILED state")
    void failConversationSuccess() throws Exception {
        var gc = pausedPhaseGc("gc-fail-ok");
        gc.setState(GroupConversationState.IN_PROGRESS);
        var m = method("failConversation", GroupConversation.class);

        invoke(m, gc);

        assertEquals(GroupConversationState.FAILED, gc.getState());
        verify(conversationStore).update(gc);
    }

    @Test
    @DisplayName("failConversation: store update throws → swallowed, state still FAILED")
    void failConversationUpdateThrows() throws Exception {
        var gc = pausedPhaseGc("gc-fail-throw");
        gc.setState(GroupConversationState.IN_PROGRESS);
        doThrow(new RuntimeException("update failed")).when(conversationStore).update(gc);
        var m = method("failConversation", GroupConversation.class);

        assertDoesNotThrow(() -> invoke(m, gc));
        assertEquals(GroupConversationState.FAILED, gc.getState());
    }

    // =================================================================
    // cleanupEphemeralAgents() — lifecycle policy branches
    // =================================================================

    private Method cleanupEphemeralMethod() throws NoSuchMethodException {
        return method("cleanupEphemeralAgents", GroupConversation.class, AgentGroupConfiguration.class);
    }

    @Test
    @DisplayName("cleanupEphemeralAgents: no created agents → no-op")
    void cleanupNoCreatedAgents() throws Exception {
        var gc = pausedPhaseGc("gc-clean-none");
        var config = buildConfig(List.of(phase(PhaseType.OPINION)));

        invoke(cleanupEphemeralMethod(), gc, config);

        verify(agentFactory, never()).undeployAgent(any(), anyString(), any());
    }

    @Test
    @DisplayName("cleanupEphemeralAgents: KEEP_DEPLOYED policy → nothing undeployed")
    void cleanupKeepDeployed() throws Exception {
        var gc = pausedPhaseGc("gc-clean-keep");
        gc.getCreatedAgentIds().add("eph-1");
        var config = buildConfig(List.of(phase(PhaseType.OPINION)));
        var dyn = new AgentGroupConfiguration.DynamicAgentConfig();
        dyn.setLifecyclePolicy(LifecyclePolicy.KEEP_DEPLOYED);
        config.setDynamicAgents(dyn);

        invoke(cleanupEphemeralMethod(), gc, config);

        verify(agentFactory, never()).undeployAgent(any(), anyString(), any());
        verify(agentStore, never()).deleteAllPermanently(anyString());
    }

    @Test
    @DisplayName("cleanupEphemeralAgents: EPHEMERAL policy → undeploy + delete")
    void cleanupEphemeralDeletes() throws Exception {
        var gc = pausedPhaseGc("gc-clean-eph");
        gc.getCreatedAgentIds().add("eph-2");
        var config = buildConfig(List.of(phase(PhaseType.OPINION)));
        var dyn = new AgentGroupConfiguration.DynamicAgentConfig();
        dyn.setLifecyclePolicy(LifecyclePolicy.EPHEMERAL);
        config.setDynamicAgents(dyn);

        invoke(cleanupEphemeralMethod(), gc, config);

        verify(agentFactory).undeployAgent(any(), eq("eph-2"), any());
        verify(agentStore).deleteAllPermanently("eph-2");
    }

    @Test
    @DisplayName("cleanupEphemeralAgents: AGENT_DECIDES + retained → skipped")
    void cleanupAgentDecidesRetainedSkipped() throws Exception {
        var gc = pausedPhaseGc("gc-clean-retained");
        gc.getCreatedAgentIds().add("eph-3");
        gc.getRetainedAgentIds().add("eph-3");
        var config = buildConfig(List.of(phase(PhaseType.OPINION)));
        var dyn = new AgentGroupConfiguration.DynamicAgentConfig();
        dyn.setLifecyclePolicy(LifecyclePolicy.AGENT_DECIDES);
        config.setDynamicAgents(dyn);

        invoke(cleanupEphemeralMethod(), gc, config);

        verify(agentFactory, never()).undeployAgent(any(), eq("eph-3"), any());
    }

    @Test
    @DisplayName("cleanupEphemeralAgents: undeploy throws → swallowed (best-effort)")
    void cleanupUndeployThrows() throws Exception {
        var gc = pausedPhaseGc("gc-clean-throw");
        gc.getCreatedAgentIds().add("eph-4");
        var config = buildConfig(List.of(phase(PhaseType.OPINION)));
        // null dynamicAgents → defaults to EPHEMERAL policy
        doThrow(new RuntimeException("undeploy failed"))
                .when(agentFactory).undeployAgent(any(), eq("eph-4"), any());

        assertDoesNotThrow(() -> invoke(cleanupEphemeralMethod(), gc, config));
    }

    // =================================================================
    // cleanupAfterTerminalState() branches
    // =================================================================

    @Test
    @DisplayName("cleanupAfterTerminalState: resId null → warns, ephemeral cleanup skipped")
    void terminalCleanupResIdNull() throws Exception {
        var gc = pausedPhaseGc("gc-terminal-resid-null");
        doReturn(null).when(groupStore).getCurrentResourceId(GROUP_ID);
        var m = method("cleanupAfterTerminalState", GroupConversation.class);

        assertDoesNotThrow(() -> invoke(m, gc));
        verify(groupStore, never()).read(anyString(), anyInt());
    }

    @Test
    @DisplayName("cleanupAfterTerminalState: store read throws → swallowed")
    void terminalCleanupStoreThrows() throws Exception {
        var gc = pausedPhaseGc("gc-terminal-throw");
        doThrow(new RuntimeException("boom")).when(groupStore).getCurrentResourceId(GROUP_ID);
        var m = method("cleanupAfterTerminalState", GroupConversation.class);

        assertDoesNotThrow(() -> invoke(m, gc));
    }

    @Test
    @DisplayName("cleanupAfterTerminalState: config read succeeds → delegates to cleanupEphemeralAgents")
    void terminalCleanupDelegates() throws Exception {
        var gc = pausedPhaseGc("gc-terminal-ok");
        var resId = new IResourceStore.IResourceId() {
            public String getId() {
                return GROUP_ID;
            }
            public Integer getVersion() {
                return 1;
            }
        };
        doReturn(resId).when(groupStore).getCurrentResourceId(GROUP_ID);
        doReturn(buildConfig(List.of(phase(PhaseType.OPINION)))).when(groupStore).read(GROUP_ID, 1);
        var m = method("cleanupAfterTerminalState", GroupConversation.class);

        assertDoesNotThrow(() -> invoke(m, gc));
        verify(groupStore).read(GROUP_ID, 1);
    }

    // =================================================================
    // resolveParticipants() branches
    // =================================================================

    private Method resolveParticipantsMethod() throws NoSuchMethodException {
        return method("resolveParticipants", DiscussionPhase.class, List.class, String.class);
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("resolveParticipants: MODERATOR with no moderator configured → falls back to ALL")
    void participantsModeratorMissingFallsBackAll() throws Exception {
        var phase = new DiscussionPhase("Mod", PhaseType.SYNTHESIS,
                "MODERATOR", TurnOrder.SEQUENTIAL, ContextScope.FULL, false, null, 1, false);
        var members = List.of(member());

        var result = (List<GroupMember>) invoke(resolveParticipantsMethod(), phase, members, null);

        assertEquals(members.size(), result.size(), "no moderator → ALL");
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("resolveParticipants: MODERATOR configured → returns single moderator member")
    void participantsModeratorPresent() throws Exception {
        var phase = new DiscussionPhase("Mod", PhaseType.SYNTHESIS,
                "MODERATOR", TurnOrder.SEQUENTIAL, ContextScope.FULL, false, null, 1, false);
        var members = List.of(member());

        var result = (List<GroupMember>) invoke(resolveParticipantsMethod(), phase, members, MOD_AGENT);

        assertEquals(1, result.size());
        assertEquals(MOD_AGENT, result.get(0).agentId());
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("resolveParticipants: ROLE:x with matching member → filtered list")
    void participantsRoleMatches() throws Exception {
        var phase = new DiscussionPhase("Role", PhaseType.OPINION,
                "ROLE:PRO", TurnOrder.SEQUENTIAL, ContextScope.FULL, false, null, 1, false);
        var members = List.of(new GroupMember("pro-1", "Pro", 0, "PRO"),
                new GroupMember("con-1", "Con", 1, "CON"));

        var result = (List<GroupMember>) invoke(resolveParticipantsMethod(), phase, members, null);

        assertEquals(1, result.size());
        assertEquals("pro-1", result.get(0).agentId());
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("resolveParticipants: ROLE:x with no matching member → falls back to ALL")
    void participantsRoleNoMatchFallsBackAll() throws Exception {
        var phase = new DiscussionPhase("Role", PhaseType.OPINION,
                "ROLE:NOBODY", TurnOrder.SEQUENTIAL, ContextScope.FULL, false, null, 1, false);
        var members = List.of(new GroupMember("pro-1", "Pro", 0, "PRO"));

        var result = (List<GroupMember>) invoke(resolveParticipantsMethod(), phase, members, null);

        assertEquals(members.size(), result.size(), "no member with role → ALL");
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("resolveParticipants: null participants (defaults ALL) → sorted member list")
    void participantsNullDefaultsAll() throws Exception {
        var phase = new DiscussionPhase("Def", PhaseType.OPINION,
                null, TurnOrder.SEQUENTIAL, ContextScope.FULL, false, null, 1, false);
        var members = List.of(new GroupMember("b", "B", 2, null),
                new GroupMember("a", "A", 1, null));

        var result = (List<GroupMember>) invoke(resolveParticipantsMethod(), phase, members, null);

        assertEquals(2, result.size());
        assertEquals("a", result.get(0).agentId(), "sorted by speakingOrder");
    }

    // =================================================================
    // resolveTaskAssignment() branches
    // =================================================================

    private Method resolveTaskAssignmentMethod() throws NoSuchMethodException {
        return method("resolveTaskAssignment", String.class, List.class, String.class, int.class);
    }

    @Test
    @DisplayName("resolveTaskAssignment: ALL round-robins across non-moderator members")
    void taskAssignAllRoundRobin() throws Exception {
        var members = List.of(new GroupMember("a", "A", 0, null),
                new GroupMember("b", "B", 1, null),
                new GroupMember(MOD_AGENT, "Mod", 2, null));

        Object first = invoke(resolveTaskAssignmentMethod(), "ALL", members, MOD_AGENT, 0);
        Object second = invoke(resolveTaskAssignmentMethod(), "ALL", members, MOD_AGENT, 1);

        assertEquals("a", first);
        assertEquals("b", second);
    }

    @Test
    @DisplayName("resolveTaskAssignment: null role behaves like ALL")
    void taskAssignNullRole() throws Exception {
        var members = List.of(new GroupMember("a", "A", 0, null));
        Object result = invoke(resolveTaskAssignmentMethod(), null, members, MOD_AGENT, 0);
        assertEquals("a", result);
    }

    @Test
    @DisplayName("resolveTaskAssignment: ALL but only moderator eligible → first member")
    void taskAssignAllOnlyModerator() throws Exception {
        var members = List.of(new GroupMember(MOD_AGENT, "Mod", 0, null));
        Object result = invoke(resolveTaskAssignmentMethod(), "ALL", members, MOD_AGENT, 0);
        assertEquals(MOD_AGENT, result, "eligible empty → falls back to first member");
    }

    @Test
    @DisplayName("resolveTaskAssignment: ROLE:x matches → that agent")
    void taskAssignRoleMatches() throws Exception {
        var members = List.of(new GroupMember("a", "A", 0, "WRITER"),
                new GroupMember("b", "B", 1, "EDITOR"));
        Object result = invoke(resolveTaskAssignmentMethod(), "ROLE:EDITOR", members, MOD_AGENT, 0);
        assertEquals("b", result);
    }

    @Test
    @DisplayName("resolveTaskAssignment: ROLE:x no match → null")
    void taskAssignRoleNoMatch() throws Exception {
        var members = List.of(new GroupMember("a", "A", 0, "WRITER"));
        Object result = invoke(resolveTaskAssignmentMethod(), "ROLE:NOBODY", members, MOD_AGENT, 0);
        assertNull(result);
    }

    // =================================================================
    // findMember() / findMemberIncludingDynamic() branches
    // =================================================================

    @Test
    @DisplayName("findMember: null agentId → null")
    void findMemberNullAgentId() throws Exception {
        var m = method("findMember", List.class, String.class);
        Object result = invoke(m, List.of(member()), null);
        assertNull(result);
    }

    @Test
    @DisplayName("findMember: no match → null; match → member")
    void findMemberMatchOrNull() throws Exception {
        var m = method("findMember", List.class, String.class);
        assertNull(invoke(m, List.of(member()), "nope"));
        assertNotNull(invoke(m, List.of(member()), AGENT_A));
    }

    @Test
    @DisplayName("findMemberIncludingDynamic: not in config, present in dynamic members → found")
    void findMemberIncludingDynamicFromDynamic() throws Exception {
        var gc = pausedPhaseGc("gc-dyn-member");
        var dyn = new GroupMember("dyn-1", "Dyn", 0, null);
        gc.getDynamicMembers().add(dyn);
        var m = method("findMemberIncludingDynamic", List.class, GroupConversation.class, String.class);

        Object result = invoke(m, List.of(member()), gc, "dyn-1");

        assertNotNull(result, "dynamic member should be found");
    }

    @Test
    @DisplayName("findMemberIncludingDynamic: not found anywhere → null")
    void findMemberIncludingDynamicNotFound() throws Exception {
        var gc = pausedPhaseGc("gc-dyn-missing");
        var m = method("findMemberIncludingDynamic", List.class, GroupConversation.class, String.class);
        assertNull(invoke(m, List.of(member()), gc, "ghost"));
    }

    // =================================================================
    // resolvePhases() / resolveProtocol() branches
    // =================================================================

    @Test
    @DisplayName("resolvePhases: custom phases present → returned as-is")
    void resolvePhasesCustom() throws Exception {
        var m = method("resolvePhases", AgentGroupConfiguration.class);
        var config = buildConfig(List.of(phase(PhaseType.OPINION)));
        @SuppressWarnings("unchecked")
        var result = (List<DiscussionPhase>) invoke(m, config);
        assertEquals(1, result.size());
    }

    @Test
    @DisplayName("resolvePhases: no phases, style null → defaults to ROUND_TABLE preset (non-empty)")
    void resolvePhasesStyleNullDefault() throws Exception {
        var m = method("resolvePhases", AgentGroupConfiguration.class);
        var config = buildConfig(null);
        config.setStyle(null);
        config.setPhases(null);
        @SuppressWarnings("unchecked")
        var result = (List<DiscussionPhase>) invoke(m, config);
        assertFalse(result.isEmpty(), "style preset expansion should yield phases");
    }

    @Test
    @DisplayName("resolveProtocol: config protocol null → default protocol")
    void resolveProtocolDefault() throws Exception {
        var m = method("resolveProtocol", AgentGroupConfiguration.class);
        var config = buildConfig(List.of(phase(PhaseType.OPINION)));
        config.setProtocol(null);
        Object result = invoke(m, config);
        assertNotNull(result);
    }

    @Test
    @DisplayName("resolveProtocol: config protocol present → returned")
    void resolveProtocolPresent() throws Exception {
        var m = method("resolveProtocol", AgentGroupConfiguration.class);
        var config = buildConfig(List.of(phase(PhaseType.OPINION)));
        var protocol = new AgentGroupConfiguration.ProtocolConfig(30,
                AgentGroupConfiguration.ProtocolConfig.MemberFailurePolicy.ABORT, 1,
                AgentGroupConfiguration.ProtocolConfig.MemberUnavailablePolicy.SKIP);
        config.setProtocol(protocol);
        Object result = invoke(m, config);
        assertSame(protocol, result);
    }

    // =================================================================
    // taskPauseFingerprint() branches
    // =================================================================

    @Test
    @DisplayName("taskPauseFingerprint: null task list → phase-only prefix")
    void fingerprintNullTaskList() throws Exception {
        var gc = pausedPhaseGc("gc-fp-null");
        gc.setTaskList(null);
        var m = method("taskPauseFingerprint", GroupConversation.class, int.class);
        String fp = (String) invoke(m, gc, 2);
        assertTrue(fp.startsWith("phase=2"), fp);
    }

    @Test
    @DisplayName("taskPauseFingerprint: non-terminal tasks included, terminal excluded")
    void fingerprintTasks() throws Exception {
        var gc = pausedPhaseGc("gc-fp-tasks");
        var taskList = new SharedTaskList();
        taskList.addTask(new TaskItem("Pending", "p", 0)); // PENDING → included
        gc.setTaskList(taskList);
        var m = method("taskPauseFingerprint", GroupConversation.class, int.class);

        String fp = (String) invoke(m, gc, 0);

        assertTrue(fp.contains("PENDING"), fp);
    }

    @Test
    @DisplayName("taskPauseFingerprint: two calls with identical state produce equal fingerprints")
    void fingerprintStable() throws Exception {
        var gc = pausedPhaseGc("gc-fp-stable");
        var taskList = new SharedTaskList();
        taskList.addTask(new TaskItem("A", "a", 0));
        gc.setTaskList(taskList);
        var m = method("taskPauseFingerprint", GroupConversation.class, int.class);

        String fp1 = (String) invoke(m, gc, 1);
        String fp2 = (String) invoke(m, gc, 1);

        assertEquals(fp1, fp2, "identical task state → identical fingerprint (no-progress guard)");
    }

    // =================================================================
    // propagateDynamicAgentTracking() branches (static)
    // =================================================================

    @Test
    @DisplayName("propagateDynamicAgentTracking: null snapshot → no-op")
    void propagateNullSnapshot() {
        var gc = pausedPhaseGc("gc-prop-null");
        GroupConversationService.propagateDynamicAgentTracking(null, gc);
        assertTrue(gc.getCreatedAgentIds().isEmpty());
    }

    @Test
    @DisplayName("propagateDynamicAgentTracking: null steps → no-op")
    void propagateNullSteps() {
        var gc = pausedPhaseGc("gc-prop-nosteps");
        var snap = new SimpleConversationMemorySnapshot();
        snap.setConversationSteps(null);
        GroupConversationService.propagateDynamicAgentTracking(snap, gc);
        assertTrue(gc.getCreatedAgentIds().isEmpty());
    }

    @Test
    @DisplayName("propagateDynamicAgentTracking: empty steps → no-op")
    void propagateEmptySteps() {
        var gc = pausedPhaseGc("gc-prop-empty");
        var snap = new SimpleConversationMemorySnapshot();
        snap.setConversationSteps(new java.util.ArrayList<>());
        GroupConversationService.propagateDynamicAgentTracking(snap, gc);
        assertTrue(gc.getCreatedAgentIds().isEmpty());
    }

    // =================================================================
    // errorEntry() null-member branch
    // =================================================================

    @Test
    @DisplayName("errorEntry: null member → uses 'unknown'/'Unknown' fallbacks")
    void errorEntryNullMember() throws Exception {
        var m = method("errorEntry", GroupMember.class, int.class, DiscussionPhase.class, String.class);
        var entry = (TranscriptEntry) invoke(m, (GroupMember) null, 0, phase(PhaseType.OPINION), "boom");
        assertEquals("unknown", entry.speakerAgentId());
        assertEquals(TranscriptEntryType.ERROR, entry.type());
    }

    @Test
    @DisplayName("errorEntry: real member → uses member ids")
    void errorEntryRealMember() throws Exception {
        var m = method("errorEntry", GroupMember.class, int.class, DiscussionPhase.class, String.class);
        var entry = (TranscriptEntry) invoke(m, member(), 0, phase(PhaseType.OPINION), "boom");
        assertEquals(AGENT_A, entry.speakerAgentId());
    }

    // =================================================================
    // handleAgentFailure() branches
    // =================================================================

    @Test
    @DisplayName("handleAgentFailure: ABORT policy → throws GroupDiscussionException")
    void handleAgentFailureAbort() throws Exception {
        var m = method("handleAgentFailure", GroupMember.class, int.class, DiscussionPhase.class,
                AgentGroupConfiguration.ProtocolConfig.class, Throwable.class, String.class, String.class);
        var protocol = new AgentGroupConfiguration.ProtocolConfig(60,
                AgentGroupConfiguration.ProtocolConfig.MemberFailurePolicy.ABORT, 2,
                AgentGroupConfiguration.ProtocolConfig.MemberUnavailablePolicy.SKIP);

        assertThrows(ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionException.class,
                () -> invoke(m, member(), 0, phase(PhaseType.OPINION), protocol,
                        new RuntimeException("fail"), "Agent failed", null));
    }

    @Test
    @DisplayName("handleAgentFailure: SKIP policy → SKIPPED entry")
    void handleAgentFailureSkip() throws Exception {
        var m = method("handleAgentFailure", GroupMember.class, int.class, DiscussionPhase.class,
                AgentGroupConfiguration.ProtocolConfig.class, Throwable.class, String.class, String.class);
        var protocol = new AgentGroupConfiguration.ProtocolConfig(60,
                AgentGroupConfiguration.ProtocolConfig.MemberFailurePolicy.SKIP, 2,
                AgentGroupConfiguration.ProtocolConfig.MemberUnavailablePolicy.SKIP);

        var entry = (TranscriptEntry) invoke(m, member(), 1, phase(PhaseType.OPINION), protocol,
                new RuntimeException("fail"), "Agent failed", "tgt");

        assertEquals(TranscriptEntryType.SKIPPED, entry.type());
        assertEquals("tgt", entry.targetAgentId());
    }

    // =================================================================
    // deleteGroupConversation() member-conv end-throws branch
    // =================================================================

    @Test
    @DisplayName("deleteGroupConversation: member conv endConversation throws → swallowed, delete still runs")
    void deleteGroupConversationMemberEndThrows() throws Exception {
        var gc = pausedPhaseGc("gc-del-member");
        gc.setState(GroupConversationState.COMPLETED); // not AWAITING → skip terminal cleanup path
        gc.getMemberConversationIds().put(AGENT_A, "priv-conv-1");
        doReturn(gc).when(conversationStore).read("gc-del-member");
        doThrow(new RuntimeException("end failed")).when(conversationService).endConversation("priv-conv-1");

        service.deleteGroupConversation("gc-del-member");

        verify(conversationStore).delete("gc-del-member");
    }

    @Test
    @DisplayName("deleteGroupConversation: not found → swallowed (no throw)")
    void deleteGroupConversationNotFound() throws Exception {
        doThrow(new IResourceStore.ResourceNotFoundException("gone"))
                .when(conversationStore).read("gc-del-missing");

        assertDoesNotThrow(() -> service.deleteGroupConversation("gc-del-missing"));
        verify(conversationStore, never()).delete("gc-del-missing");
    }

    @Test
    @DisplayName("deleteGroupConversation: AWAITING_APPROVAL → runs terminal cleanup + schedule delete")
    void deleteGroupConversationPausedRunsCleanup() throws Exception {
        var gc = pausedPhaseGc("gc-del-paused");
        doReturn(gc).when(conversationStore).read("gc-del-paused");
        doReturn(null).when(groupStore).getCurrentResourceId(GROUP_ID);

        service.deleteGroupConversation("gc-del-paused");

        verify(scheduleStore).deleteSchedulesByName(anyString());
        verify(conversationStore).delete("gc-del-paused");
    }
}
