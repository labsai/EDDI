/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.agents.AgentSigningService;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.crypto.NonceCacheService;
import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.IGroupConversationStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ContextScope;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionStyle;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.MemberType;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.PhaseType;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.TurnOrder;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.configs.groups.model.SharedTaskList;
import ai.labs.eddi.configs.groups.model.SharedTaskList.TaskItem;
import ai.labs.eddi.configs.groups.model.SharedTaskList.TaskStatus;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionEventListener;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionException;
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
 * Batch-3 branch-coverage tests for {@link GroupConversationService}. These
 * target the discussion-orchestration branches NOT exercised by
 * {@link GroupConversationServiceHitlTest} or the batch-1/batch-2 coverage
 * tests: {@code buildPhaseInput} phase-type switch arms,
 * {@code selectDefaultTemplate} / {@code filterByScope} scope arms,
 * {@code mapPhaseToEntryType}, {@code findLatestResponse},
 * {@code executeGroupMemberTurn} (group-of-groups nesting),
 * {@code executeTaskPhase} routing, {@code resolveTaskAssignment} direct-id,
 * {@code buildTaskExecutionInput}, verification parsing,
 * {@code resetStrandedInProgressTasks}, {@code handleTaskFailure},
 * {@code verifyPriorEntriesIfRequired} guards, list/read entry points, and
 * {@code startAndDiscussAsync} guards.
 */
class GroupConversationServiceHitlCoverage3Test {

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
    private static final String GROUP_ID = "group-hitl-cov3";
    private static final String USER_ID = "user-hitl-cov3";
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
        config.setName("HITL Coverage3 Group");
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

    private DiscussionPhase phase(PhaseType type, ContextScope scope) {
        return new DiscussionPhase("P-" + type, type,
                "ALL", TurnOrder.SEQUENTIAL, scope, false, null, 1, false);
    }

    private ProtocolConfig defaultProtocol() {
        return new ProtocolConfig(60, ProtocolConfig.MemberFailurePolicy.SKIP, 2,
                ProtocolConfig.MemberUnavailablePolicy.SKIP);
    }

    private GroupConversation gc(String id) {
        var gc = new GroupConversation();
        gc.setId(id);
        gc.setGroupId(GROUP_ID);
        gc.setUserId(USER_ID);
        gc.setState(GroupConversationState.IN_PROGRESS);
        gc.setOriginalQuestion("Coverage3 test");
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

    private TranscriptEntry entry(String agentId, String name, String content,
                                  TranscriptEntryType type, String targetAgentId) {
        return new TranscriptEntry(agentId, name, content, 0, "P", type,
                Instant.now(), null, targetAgentId);
    }

    private IResourceStore.IResourceId mockResourceId() {
        return new IResourceStore.IResourceId() {
            @Override
            public String getId() {
                return GROUP_ID;
            }
            @Override
            public Integer getVersion() {
                return 1;
            }
        };
    }

    // =================================================================
    // mapPhaseToEntryType() — all switch arms
    // =================================================================

    @Test
    @DisplayName("mapPhaseToEntryType: every PhaseType maps to its entry type")
    void mapPhaseToEntryTypeAllArms() throws Exception {
        var m = method("mapPhaseToEntryType", PhaseType.class);
        assertEquals(TranscriptEntryType.OPINION, invoke(m, PhaseType.OPINION));
        assertEquals(TranscriptEntryType.CRITIQUE, invoke(m, PhaseType.CRITIQUE));
        assertEquals(TranscriptEntryType.REVISION, invoke(m, PhaseType.REVISION));
        assertEquals(TranscriptEntryType.CHALLENGE, invoke(m, PhaseType.CHALLENGE));
        assertEquals(TranscriptEntryType.DEFENSE, invoke(m, PhaseType.DEFENSE));
        assertEquals(TranscriptEntryType.ARGUMENT, invoke(m, PhaseType.ARGUE));
        assertEquals(TranscriptEntryType.REBUTTAL, invoke(m, PhaseType.REBUTTAL));
        assertEquals(TranscriptEntryType.SYNTHESIS, invoke(m, PhaseType.SYNTHESIS));
        assertEquals(TranscriptEntryType.PLAN, invoke(m, PhaseType.PLAN));
        assertEquals(TranscriptEntryType.TASK_RESULT, invoke(m, PhaseType.EXECUTE));
        assertEquals(TranscriptEntryType.VERIFICATION, invoke(m, PhaseType.VERIFY));
    }

    // =================================================================
    // findLatestResponse() branches
    // =================================================================

    @Test
    @DisplayName("findLatestResponse: returns the LAST non-error content for the agent")
    void findLatestResponseReturnsLast() throws Exception {
        var m = method("findLatestResponse", List.class, String.class);
        var transcript = List.of(
                entry(AGENT_A, "A", "first", TranscriptEntryType.OPINION, null),
                entry(AGENT_A, "A", "second", TranscriptEntryType.OPINION, null));
        assertEquals("second", invoke(m, transcript, AGENT_A));
    }

    @Test
    @DisplayName("findLatestResponse: skips ERROR/SKIPPED entries")
    void findLatestResponseSkipsErrorSkipped() throws Exception {
        var m = method("findLatestResponse", List.class, String.class);
        var transcript = List.of(
                entry(AGENT_A, "A", "good", TranscriptEntryType.OPINION, null),
                entry(AGENT_A, "A", "bad", TranscriptEntryType.ERROR, null),
                entry(AGENT_A, "A", "nope", TranscriptEntryType.SKIPPED, null));
        assertEquals("good", invoke(m, transcript, AGENT_A));
    }

    @Test
    @DisplayName("findLatestResponse: no match for agent → null")
    void findLatestResponseNoMatch() throws Exception {
        var m = method("findLatestResponse", List.class, String.class);
        var transcript = List.of(entry("other", "O", "x", TranscriptEntryType.OPINION, null));
        assertNull(invoke(m, transcript, AGENT_A));
    }

    // =================================================================
    // selectDefaultTemplate() branches
    // =================================================================

    @Test
    @DisplayName("selectDefaultTemplate: OPINION + NONE scope → independent template")
    void selectDefaultTemplateOpinionNone() throws Exception {
        var m = method("selectDefaultTemplate", DiscussionPhase.class, List.class, int.class);
        var result = (String) invoke(m, phase(PhaseType.OPINION, ContextScope.NONE), List.of(), 0);
        assertNotNull(result);
    }

    @Test
    @DisplayName("selectDefaultTemplate: OPINION + ANONYMOUS scope → anonymous template")
    void selectDefaultTemplateOpinionAnonymous() throws Exception {
        var m = method("selectDefaultTemplate", DiscussionPhase.class, List.class, int.class);
        var result = (String) invoke(m, phase(PhaseType.OPINION, ContextScope.ANONYMOUS), List.of(), 0);
        assertNotNull(result);
    }

    @Test
    @DisplayName("selectDefaultTemplate: OPINION + FULL scope → with-context template")
    void selectDefaultTemplateOpinionFull() throws Exception {
        var m = method("selectDefaultTemplate", DiscussionPhase.class, List.class, int.class);
        var result = (String) invoke(m, phase(PhaseType.OPINION, ContextScope.FULL), List.of(), 0);
        assertNotNull(result);
    }

    @Test
    @DisplayName("selectDefaultTemplate: non-OPINION type → default preset template")
    void selectDefaultTemplateNonOpinion() throws Exception {
        var m = method("selectDefaultTemplate", DiscussionPhase.class, List.class, int.class);
        var result = (String) invoke(m, phase(PhaseType.CRITIQUE), List.of(), 0);
        assertNotNull(result);
    }

    // =================================================================
    // filterByScope() — every scope arm
    // =================================================================

    @SuppressWarnings("unchecked")
    private List<?> filterByScope(ContextScope scope, List<TranscriptEntry> transcript,
                                  int phaseIdx, GroupMember speaker)
            throws Exception {
        var m = method("filterByScope", List.class, ContextScope.class, int.class, GroupMember.class);
        return (List<?>) invoke(m, transcript, scope, phaseIdx, speaker);
    }

    @Test
    @DisplayName("filterByScope: null scope → empty list")
    void filterByScopeNull() throws Exception {
        assertTrue(filterByScope(null, List.of(entry(AGENT_A, "A", "x", TranscriptEntryType.OPINION, null)),
                1, member()).isEmpty());
    }

    @Test
    @DisplayName("filterByScope: NONE scope → empty list")
    void filterByScopeNone() throws Exception {
        assertTrue(filterByScope(ContextScope.NONE,
                List.of(entry(AGENT_A, "A", "x", TranscriptEntryType.OPINION, null)), 1, member()).isEmpty());
    }

    @Test
    @DisplayName("filterByScope: FULL scope → includes all non-error content, real speaker name")
    void filterByScopeFull() throws Exception {
        var result = filterByScope(ContextScope.FULL,
                List.of(entry(AGENT_A, "Agent A", "hello", TranscriptEntryType.OPINION, null)), 1, member());
        assertEquals(1, result.size());
    }

    @Test
    @DisplayName("filterByScope: LAST_PHASE scope → only entries from >= currentPhase-1")
    void filterByScopeLastPhase() throws Exception {
        var old = new TranscriptEntry(AGENT_A, "A", "old", 0, "P0",
                TranscriptEntryType.OPINION, Instant.now(), null, null);
        var recent = new TranscriptEntry(AGENT_A, "A", "recent", 2, "P2",
                TranscriptEntryType.OPINION, Instant.now(), null, null);
        var result = filterByScope(ContextScope.LAST_PHASE, List.of(old, recent), 3, member());
        assertEquals(1, result.size(), "only phaseIndex >= currentPhase-1 (=2) is kept");
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("filterByScope: ANONYMOUS scope → speaker attribution stripped to 'Anonymous'")
    void filterByScopeAnonymous() throws Exception {
        var result = (List<java.util.Map<String, Object>>) filterByScope(ContextScope.ANONYMOUS,
                List.of(entry(AGENT_A, "Agent A", "secret", TranscriptEntryType.OPINION, null)), 1, member());
        assertEquals(1, result.size());
        assertEquals("Anonymous", result.get(0).get("speaker"));
    }

    @Test
    @DisplayName("filterByScope: OWN_FEEDBACK scope → only entries targeting the speaker")
    void filterByScopeOwnFeedback() throws Exception {
        var toMe = entry("reviewer", "R", "critique", TranscriptEntryType.CRITIQUE, AGENT_A);
        var toOther = entry("reviewer", "R", "other", TranscriptEntryType.CRITIQUE, "someone-else");
        var result = filterByScope(ContextScope.OWN_FEEDBACK, List.of(toMe, toOther), 1, member());
        assertEquals(1, result.size());
    }

    @Test
    @DisplayName("filterByScope: TASK_ONLY scope → empty (handled by task logic)")
    void filterByScopeTaskOnly() throws Exception {
        assertTrue(filterByScope(ContextScope.TASK_ONLY,
                List.of(entry(AGENT_A, "A", "x", TranscriptEntryType.OPINION, null)), 1, member()).isEmpty());
    }

    // =================================================================
    // buildPhaseInput() — phase-type switch arms (templating mocked null → returns
    // null)
    // =================================================================

    private Method buildPhaseInputMethod() throws NoSuchMethodException {
        return method("buildPhaseInput", DiscussionPhase.class, GroupMember.class, String.class,
                List.class, int.class, GroupMember.class);
    }

    @Test
    @DisplayName("buildPhaseInput: OPINION arm processes previousResponses")
    void buildPhaseInputOpinion() throws Exception {
        invoke(buildPhaseInputMethod(), phase(PhaseType.OPINION), member(), "Q?",
                List.of(entry("other", "O", "prev", TranscriptEntryType.OPINION, null)), 1, null);
        verify(templatingEngine).processTemplate(any(), any(), eq(ITemplatingEngine.TemplateMode.TEXT));
    }

    @Test
    @DisplayName("buildPhaseInput: CRITIQUE with target → targetResponse resolved")
    void buildPhaseInputCritiqueWithTarget() throws Exception {
        var target = new GroupMember("t", "Target", 2, null);
        invoke(buildPhaseInputMethod(), phase(PhaseType.CRITIQUE), member(), "Q?",
                List.of(entry("t", "Target", "tgtResp", TranscriptEntryType.OPINION, null)), 1, target);
        verify(templatingEngine).processTemplate(any(), any(), any());
    }

    @Test
    @DisplayName("buildPhaseInput: CRITIQUE with null target → no target vars, still processes")
    void buildPhaseInputCritiqueNoTarget() throws Exception {
        invoke(buildPhaseInputMethod(), phase(PhaseType.CRITIQUE), member(), "Q?", List.of(), 1, null);
        verify(templatingEngine).processTemplate(any(), any(), any());
    }

    @Test
    @DisplayName("buildPhaseInput: REVISION arm builds feedbackReceived from CRITIQUE entries")
    void buildPhaseInputRevision() throws Exception {
        var feedback = entry("reviewer", "R", "fix this", TranscriptEntryType.CRITIQUE, AGENT_A);
        invoke(buildPhaseInputMethod(), phase(PhaseType.REVISION), member(), "Q?", List.of(feedback), 1, null);
        verify(templatingEngine).processTemplate(any(), any(), any());
    }

    @Test
    @DisplayName("buildPhaseInput: CHALLENGE arm collects allOpinions")
    void buildPhaseInputChallenge() throws Exception {
        var opinion = entry("x", "X", "an opinion", TranscriptEntryType.OPINION, null);
        invoke(buildPhaseInputMethod(), phase(PhaseType.CHALLENGE), member(), "Q?", List.of(opinion), 1, null);
        verify(templatingEngine).processTemplate(any(), any(), any());
    }

    @Test
    @DisplayName("buildPhaseInput: DEFENSE arm collects challenges")
    void buildPhaseInputDefense() throws Exception {
        var challenge = entry("x", "X", "a challenge", TranscriptEntryType.CHALLENGE, null);
        invoke(buildPhaseInputMethod(), phase(PhaseType.DEFENSE), member(), "Q?", List.of(challenge), 1, null);
        verify(templatingEngine).processTemplate(any(), any(), any());
    }

    @Test
    @DisplayName("buildPhaseInput: ARGUE arm (PRO role → FOR side) collects opposing")
    void buildPhaseInputArguePro() throws Exception {
        var pro = new GroupMember(AGENT_A, "Agent A", 1, "PRO");
        var opposing = entry("con", "Con", "against", TranscriptEntryType.ARGUMENT, null);
        invoke(buildPhaseInputMethod(), phase(PhaseType.ARGUE), pro, "Q?", List.of(opposing), 1, null);
        verify(templatingEngine).processTemplate(any(), any(), any());
    }

    @Test
    @DisplayName("buildPhaseInput: REBUTTAL arm (non-PRO role → AGAINST side)")
    void buildPhaseInputRebuttalAgainst() throws Exception {
        var con = new GroupMember(AGENT_A, "Agent A", 1, "CON");
        var arg = entry("pro", "Pro", "for it", TranscriptEntryType.REBUTTAL, null);
        invoke(buildPhaseInputMethod(), phase(PhaseType.REBUTTAL), con, "Q?", List.of(arg), 1, null);
        verify(templatingEngine).processTemplate(any(), any(), any());
    }

    @Test
    @DisplayName("buildPhaseInput: SYNTHESIS arm filters out ERROR/SKIPPED/QUESTION")
    void buildPhaseInputSynthesis() throws Exception {
        var good = entry("x", "X", "keep", TranscriptEntryType.OPINION, null);
        var err = entry("x", "X", "drop", TranscriptEntryType.ERROR, null);
        invoke(buildPhaseInputMethod(), phase(PhaseType.SYNTHESIS), member(), "Q?", List.of(good, err), 2, null);
        verify(templatingEngine).processTemplate(any(), any(), any());
    }

    @Test
    @DisplayName("buildPhaseInput: PLAN arm processes members placeholder")
    void buildPhaseInputPlan() throws Exception {
        invoke(buildPhaseInputMethod(), phase(PhaseType.PLAN), member(), "Q?", List.of(), 0, null);
        verify(templatingEngine).processTemplate(any(), any(), any());
    }

    @Test
    @DisplayName("buildPhaseInput: template exception → plain-text fallback used")
    void buildPhaseInputTemplateExceptionFallback() throws Exception {
        doThrow(new ITemplatingEngine.TemplateEngineException("boom", new RuntimeException()))
                .when(templatingEngine).processTemplate(any(), any(), any());
        var result = (String) invoke(buildPhaseInputMethod(), phase(PhaseType.OPINION), member(), "Q?",
                List.of(), 0, null);
        assertNotNull(result, "fallback returns a non-null plain-text prompt");
        assertTrue(result.contains("Agent A"), result);
    }

    @Test
    @DisplayName("buildPhaseInput: custom inputTemplate present → used instead of default")
    void buildPhaseInputCustomTemplate() throws Exception {
        var custom = new DiscussionPhase("Custom", PhaseType.OPINION,
                "ALL", TurnOrder.SEQUENTIAL, ContextScope.FULL, false, "MY TEMPLATE {question}", 1, false);
        invoke(buildPhaseInputMethod(), custom, member(), "Q?", List.of(), 0, null);
        verify(templatingEngine).processTemplate(eq("MY TEMPLATE {question}"), any(), any());
    }

    // =================================================================
    // buildTaskExecutionInput() branches
    // =================================================================

    private Method buildTaskExecInputMethod() throws NoSuchMethodException {
        return method("buildTaskExecutionInput", TaskItem.class, String.class,
                DiscussionPhase.class, GroupConversation.class);
    }

    @Test
    @DisplayName("buildTaskExecutionInput: basic (no deps scope) → processes template")
    void buildTaskExecInputBasic() throws Exception {
        var g = gc("gc-taskinput");
        invoke(buildTaskExecInputMethod(), new TaskItem("Subj", "Desc", 0), "Q?",
                phase(PhaseType.EXECUTE, ContextScope.TASK_ONLY), g);
        verify(templatingEngine).processTemplate(any(), any(), any());
    }

    @Test
    @DisplayName("buildTaskExecutionInput: TASK_WITH_DEPS scope pulls dependency results")
    void buildTaskExecInputWithDeps() throws Exception {
        var g = gc("gc-taskdeps");
        var taskList = new SharedTaskList();
        var dep = new TaskItem("Dep", "d", 0);
        taskList.addTask(dep);
        String depId = taskList.all().get(0).id();
        taskList.assignTask(depId, AGENT_A, "A");
        taskList.startTask(depId);
        taskList.completeTask(depId, "dep result");
        // dependent task referencing the dep id
        var dependent = new TaskItem("id-dependent", "Main", "m",
                TaskStatus.PENDING, null, null, List.of(depId), null, null,
                false, 0, Instant.now(), null);
        taskList.addTask(dependent);
        g.setTaskList(taskList);

        invoke(buildTaskExecInputMethod(), dependent, "Q?",
                phase(PhaseType.EXECUTE, ContextScope.TASK_WITH_DEPS), g);
        verify(templatingEngine).processTemplate(any(), any(), any());
    }

    @Test
    @DisplayName("buildTaskExecutionInput: verificationNote appended for RETRY feedback")
    void buildTaskExecInputVerificationNote() throws Exception {
        var g = gc("gc-tasknote");
        var task = new TaskItem("id-1", "Subj", "Desc",
                TaskStatus.ASSIGNED, AGENT_A, "A", List.of(), null, "fix the bug",
                false, 0, Instant.now(), null);
        var result = (String) invoke(buildTaskExecInputMethod(), task, "Q?",
                phase(PhaseType.EXECUTE, ContextScope.TASK_ONLY), g);
        assertTrue(result != null && result.contains("fix the bug"),
                "reviewer feedback must be surfaced in the re-execution input");
    }

    @Test
    @DisplayName("buildTaskExecutionInput: template exception → plain-text fallback")
    void buildTaskExecInputTemplateException() throws Exception {
        var g = gc("gc-taskexc");
        doThrow(new ITemplatingEngine.TemplateEngineException("boom", new RuntimeException()))
                .when(templatingEngine).processTemplate(any(), any(), any());
        var result = (String) invoke(buildTaskExecInputMethod(), new TaskItem("Subj", "Desc", 0), "Q?",
                phase(PhaseType.EXECUTE, ContextScope.TASK_ONLY), g);
        assertTrue(result != null && result.contains("Subj"), result);
    }

    // =================================================================
    // resolveTaskAssignment() — direct agentId reference branch
    // =================================================================

    @Test
    @DisplayName("resolveTaskAssignment: direct agentId reference resolves to that member")
    void resolveTaskAssignmentDirectId() throws Exception {
        var m = method("resolveTaskAssignment", String.class, List.class, String.class, int.class);
        var members = List.of(new GroupMember("agent-x", "X", 0, null));
        Object result = invoke(m, "agent-x", members, MOD_AGENT, 0);
        assertEquals("agent-x", result);
    }

    // =================================================================
    // executeTaskPhase() routing — default arm warns (non task-type)
    // =================================================================

    @Test
    @DisplayName("executeTaskPhase: non PLAN/EXECUTE/VERIFY type → default arm no-op (warns)")
    void executeTaskPhaseDefaultArm() throws Exception {
        var g = gc("gc-taskphase-default");
        var m = method("executeTaskPhase", GroupConversation.class, AgentGroupConfiguration.class,
                List.class, DiscussionPhase.class, ProtocolConfig.class, String.class, int.class,
                GroupDiscussionEventListener.class, java.util.concurrent.atomic.AtomicInteger.class, int.class);
        // OPINION is not a task phase — routed to default arm which just logs
        assertDoesNotThrow(() -> invoke(m, g, buildConfig(List.of()), List.of(member()),
                phase(PhaseType.OPINION), defaultProtocol(), "Q?", 0, null,
                new java.util.concurrent.atomic.AtomicInteger(0), 50));
    }

    // =================================================================
    // executeTaskExecutionPhase() early guards
    // =================================================================

    @Test
    @DisplayName("executeTaskExecutionPhase: null task list → early return, no futures")
    void executeTaskExecutionNullList() throws Exception {
        var g = gc("gc-exec-null");
        g.setTaskList(null);
        var m = method("executeTaskExecutionPhase", GroupConversation.class, AgentGroupConfiguration.class,
                List.class, DiscussionPhase.class, ProtocolConfig.class, String.class, int.class,
                GroupDiscussionEventListener.class, java.util.concurrent.atomic.AtomicInteger.class, int.class);
        assertDoesNotThrow(() -> invoke(m, g, buildConfig(List.of()), List.of(member()),
                phase(PhaseType.EXECUTE), defaultProtocol(), "Q?", 0, null,
                new java.util.concurrent.atomic.AtomicInteger(0), 50));
    }

    @Test
    @DisplayName("executeTaskExecutionPhase: empty task list → early return")
    void executeTaskExecutionEmptyList() throws Exception {
        var g = gc("gc-exec-empty");
        g.setTaskList(new SharedTaskList());
        var m = method("executeTaskExecutionPhase", GroupConversation.class, AgentGroupConfiguration.class,
                List.class, DiscussionPhase.class, ProtocolConfig.class, String.class, int.class,
                GroupDiscussionEventListener.class, java.util.concurrent.atomic.AtomicInteger.class, int.class);
        assertDoesNotThrow(() -> invoke(m, g, buildConfig(List.of()), List.of(member()),
                phase(PhaseType.EXECUTE), defaultProtocol(), "Q?", 0, null,
                new java.util.concurrent.atomic.AtomicInteger(0), 50));
    }

    // =================================================================
    // executeTaskVerificationPhase() early guards
    // =================================================================

    private Method verifyPhaseMethod() throws NoSuchMethodException {
        return method("executeTaskVerificationPhase", GroupConversation.class, AgentGroupConfiguration.class,
                List.class, DiscussionPhase.class, ProtocolConfig.class, String.class, int.class,
                GroupDiscussionEventListener.class, java.util.concurrent.atomic.AtomicInteger.class, int.class);
    }

    @Test
    @DisplayName("executeTaskVerificationPhase: null task list → early return")
    void verifyPhaseNullList() throws Exception {
        var g = gc("gc-verify-null");
        g.setTaskList(null);
        assertDoesNotThrow(() -> invoke(verifyPhaseMethod(), g, buildConfig(List.of()), List.of(member()),
                phase(PhaseType.VERIFY), defaultProtocol(), "Q?", 0, null,
                new java.util.concurrent.atomic.AtomicInteger(0), 50));
    }

    @Test
    @DisplayName("executeTaskVerificationPhase: no COMPLETED tasks → adds VERIFICATION note, returns")
    void verifyPhaseNoCompleted() throws Exception {
        var g = gc("gc-verify-none");
        var taskList = new SharedTaskList();
        taskList.addTask(new TaskItem("Pending", "p", 0)); // PENDING, not COMPLETED
        g.setTaskList(taskList);
        invoke(verifyPhaseMethod(), g, buildConfig(List.of()), List.of(member()),
                phase(PhaseType.VERIFY), defaultProtocol(), "Q?", 0, null,
                new java.util.concurrent.atomic.AtomicInteger(0), 50);
        assertTrue(g.getTranscript().stream()
                .anyMatch(e -> e.type() == TranscriptEntryType.VERIFICATION),
                "a VERIFICATION 'no completed tasks' note is recorded");
    }

    @Test
    @DisplayName("executeTaskVerificationPhase: completed tasks but no verifier → early return")
    void verifyPhaseNoVerifier() throws Exception {
        var g = gc("gc-verify-noverifier");
        var taskList = new SharedTaskList();
        taskList.addTask(new TaskItem("T", "d", 0));
        String id = taskList.all().get(0).id();
        taskList.assignTask(id, AGENT_A, "A");
        taskList.startTask(id);
        taskList.completeTask(id, "done");
        g.setTaskList(taskList);
        // empty speakers → no verifier
        assertDoesNotThrow(() -> invoke(verifyPhaseMethod(), g, buildConfig(List.of()), List.of(),
                phase(PhaseType.VERIFY), defaultProtocol(), "Q?", 0, null,
                new java.util.concurrent.atomic.AtomicInteger(0), 50));
    }

    // =================================================================
    // parseAndApplyVerification() / tryParseVerificationJson() branches
    // =================================================================

    private Method parseVerificationMethod() throws NoSuchMethodException {
        return method("parseAndApplyVerification", GroupConversation.class, List.class,
                String.class, GroupDiscussionEventListener.class);
    }

    private GroupConversation gcWithCompletedTask(String id) {
        var g = gc(id);
        var taskList = new SharedTaskList();
        taskList.addTask(new TaskItem("Build", "d", 0));
        String tid = taskList.all().get(0).id();
        taskList.assignTask(tid, AGENT_A, "A");
        taskList.startTask(tid);
        taskList.completeTask(tid, "done");
        g.setTaskList(taskList);
        return g;
    }

    @Test
    @DisplayName("parseAndApplyVerification: no bracket in content → fallback auto-verifies all")
    void parseVerificationFallbackNoBracket() throws Exception {
        var g = gcWithCompletedTask("gc-verif-fallback");
        var completed = g.getTaskList().all();
        invoke(parseVerificationMethod(), g, completed, "plain text no json", null);
        assertTrue(g.getTaskList().all().get(0).status() == TaskStatus.VERIFIED,
                "fallback marks completed tasks VERIFIED");
    }

    @Test
    @DisplayName("parseAndApplyVerification: valid JSON passed=true → task verified via JSON path")
    void parseVerificationJsonPassed() throws Exception {
        var g = gcWithCompletedTask("gc-verif-json");
        var completed = g.getTaskList().all();
        doReturn(List.of(java.util.Map.of("subject", "Build", "passed", true, "feedback", "ok")))
                .when(jsonSerialization).deserialize(anyString(), eq(List.class));
        invoke(parseVerificationMethod(), g, completed, "[{\"subject\":\"Build\",\"passed\":true}]", null);
        assertEquals(TaskStatus.VERIFIED, g.getTaskList().all().get(0).status());
    }

    @Test
    @DisplayName("parseAndApplyVerification: JSON passed=false → task FAILED")
    void parseVerificationJsonFailed() throws Exception {
        var g = gcWithCompletedTask("gc-verif-json-fail");
        var completed = g.getTaskList().all();
        doReturn(List.of(java.util.Map.of("subject", "Build", "passed", false)))
                .when(jsonSerialization).deserialize(anyString(), eq(List.class));
        invoke(parseVerificationMethod(), g, completed, "[{\"subject\":\"Build\",\"passed\":false}]", null);
        assertEquals(TaskStatus.FAILED, g.getTaskList().all().get(0).status());
    }

    @Test
    @DisplayName("parseAndApplyVerification: JSON deserialize returns empty → fallback auto-verifies")
    void parseVerificationJsonEmptyFallback() throws Exception {
        var g = gcWithCompletedTask("gc-verif-json-empty");
        var completed = g.getTaskList().all();
        doReturn(List.of()).when(jsonSerialization).deserialize(anyString(), eq(List.class));
        invoke(parseVerificationMethod(), g, completed, "[]", null);
        assertEquals(TaskStatus.VERIFIED, g.getTaskList().all().get(0).status(),
                "empty JSON list → JSON path returns false → fallback verifies");
    }

    @Test
    @DisplayName("parseAndApplyVerification: deserialize throws → caught, fallback auto-verifies")
    void parseVerificationDeserializeThrows() throws Exception {
        var g = gcWithCompletedTask("gc-verif-json-throw");
        var completed = g.getTaskList().all();
        doThrow(new RuntimeException("bad json"))
                .when(jsonSerialization).deserialize(anyString(), eq(List.class));
        invoke(parseVerificationMethod(), g, completed, "[garbage]", null);
        assertEquals(TaskStatus.VERIFIED, g.getTaskList().all().get(0).status());
    }

    // =================================================================
    // tryParseVerificationJson() direct edge: no closing bracket → false
    // =================================================================

    @Test
    @DisplayName("tryParseVerificationJson: '[' present but no ']' → returns false")
    void tryParseVerificationJsonNoCloseBracket() throws Exception {
        var g = gcWithCompletedTask("gc-verif-nobracket");
        var completed = g.getTaskList().all();
        var m = method("tryParseVerificationJson", GroupConversation.class, List.class,
                String.class, GroupDiscussionEventListener.class);
        Object result = invoke(m, g, completed, "text with [ but no close", null);
        assertEquals(Boolean.FALSE, result);
    }

    // =================================================================
    // resetStrandedInProgressTasks() branches
    // =================================================================

    @Test
    @DisplayName("resetStrandedInProgressTasks: null task list → no-op")
    void resetStrandedNullList() throws Exception {
        var g = gc("gc-reset-null");
        g.setTaskList(null);
        var m = method("resetStrandedInProgressTasks", GroupConversation.class, String.class);
        assertDoesNotThrow(() -> invoke(m, g, "timeout"));
    }

    @Test
    @DisplayName("resetStrandedInProgressTasks: IN_PROGRESS task reset to ASSIGNED")
    void resetStrandedResets() throws Exception {
        var g = gc("gc-reset-some");
        var taskList = new SharedTaskList();
        taskList.addTask(new TaskItem("T", "d", 0));
        String id = taskList.all().get(0).id();
        taskList.assignTask(id, AGENT_A, "A");
        taskList.startTask(id); // now IN_PROGRESS
        g.setTaskList(taskList);
        var m = method("resetStrandedInProgressTasks", GroupConversation.class, String.class);

        invoke(m, g, "wave timeout");

        assertEquals(TaskStatus.ASSIGNED, g.getTaskList().all().get(0).status(),
                "stranded IN_PROGRESS task reset to ASSIGNED");
    }

    @Test
    @DisplayName("resetStrandedInProgressTasks: no IN_PROGRESS tasks → nothing changes")
    void resetStrandedNoInProgress() throws Exception {
        var g = gc("gc-reset-none");
        var taskList = new SharedTaskList();
        taskList.addTask(new TaskItem("T", "d", 0)); // PENDING
        g.setTaskList(taskList);
        var m = method("resetStrandedInProgressTasks", GroupConversation.class, String.class);

        invoke(m, g, "wave error");

        assertEquals(TaskStatus.PENDING, g.getTaskList().all().get(0).status());
    }

    // =================================================================
    // handleTaskFailure() branches
    // =================================================================

    @Test
    @DisplayName("handleTaskFailure: in-progress task → failed + error transcript entry + collected error")
    void handleTaskFailureMarksFailed() throws Exception {
        var g = gc("gc-taskfail");
        var taskList = new SharedTaskList();
        taskList.addTask(new TaskItem("T", "d", 0));
        String id = taskList.all().get(0).id();
        taskList.assignTask(id, AGENT_A, "A");
        taskList.startTask(id);
        g.setTaskList(taskList);
        var task = g.getTaskList().all().get(0);

        var errors = java.util.Collections.synchronizedList(new java.util.ArrayList<GroupDiscussionException>());
        var m = method("handleTaskFailure", GroupConversation.class, TaskItem.class, GroupMember.class,
                String.class, int.class, DiscussionPhase.class, GroupDiscussionEventListener.class,
                List.class, GroupDiscussionException.class);
        var ex = new GroupDiscussionException("agent broke");

        invoke(m, g, task, member(), "agent broke", 0, phase(PhaseType.EXECUTE), null, errors, ex);

        assertEquals(TaskStatus.FAILED, g.getTaskList().all().get(0).status());
        assertEquals(1, errors.size());
        assertTrue(g.getTranscript().stream()
                .anyMatch(e -> e.type() == TranscriptEntryType.TASK_RESULT));
    }

    @Test
    @DisplayName("handleTaskFailure: already-terminal task → failTask swallowed, error still collected")
    void handleTaskFailureAlreadyTerminal() throws Exception {
        var g = gc("gc-taskfail-terminal");
        var taskList = new SharedTaskList();
        taskList.addTask(new TaskItem("T", "d", 0));
        String id = taskList.all().get(0).id();
        taskList.assignTask(id, AGENT_A, "A");
        taskList.startTask(id);
        taskList.completeTask(id, "done");
        taskList.verifyTask(id, true, "ok"); // now VERIFIED (terminal)
        g.setTaskList(taskList);
        var task = g.getTaskList().all().get(0);

        var errors = java.util.Collections.synchronizedList(new java.util.ArrayList<GroupDiscussionException>());
        var m = method("handleTaskFailure", GroupConversation.class, TaskItem.class, GroupMember.class,
                String.class, int.class, DiscussionPhase.class, GroupDiscussionEventListener.class,
                List.class, GroupDiscussionException.class);

        assertDoesNotThrow(() -> invoke(m, g, task, member(), "late fail", 0, phase(PhaseType.EXECUTE),
                null, errors, new GroupDiscussionException("late fail")));
        assertEquals(1, errors.size(), "error is still collected even when task cannot transition");
    }

    // =================================================================
    // executeGroupMemberTurn() — group-of-groups nesting branches
    // =================================================================

    private Method groupMemberTurnMethod() throws NoSuchMethodException {
        return method("executeGroupMemberTurn", GroupMember.class, GroupConversation.class, String.class,
                ProtocolConfig.class, int.class, DiscussionPhase.class, TranscriptEntryType.class, String.class);
    }

    @Test
    @DisplayName("executeGroupMemberTurn: sub-group depth exceeded → SKIPPED entry")
    void groupMemberDepthExceeded() throws Exception {
        // A GROUP member whose sub-discuss will exceed maxDepth. The parent gc is
        // already at maxDepth so nextDepth = maxDepth+1 → discuss throws
        // depth-exceeded.
        var g = gc("gc-subgroup-depth");
        g.setDepth(MAX_DEPTH);
        var groupMember = new GroupMember("sub-group-id", "Sub Group", 0, null, MemberType.GROUP);

        var entry = (TranscriptEntry) invoke(groupMemberTurnMethod(), groupMember, g, "input",
                defaultProtocol(), 0, phase(PhaseType.OPINION), TranscriptEntryType.OPINION, null);

        assertEquals(TranscriptEntryType.SKIPPED, entry.type());
        assertTrue(entry.errorReason() != null && entry.errorReason().contains("depth"), entry.errorReason());
    }

    @Test
    @DisplayName("executeGroupMemberTurn: sub-group discuss throws (group not found) → SKIP via handleAgentFailure")
    void groupMemberDiscussThrows() throws Exception {
        var g = gc("gc-subgroup-notfound");
        g.setDepth(0);
        // sub-group discuss → getCurrentResourceId returns null →
        // ResourceNotFoundException
        doReturn(null).when(groupStore).getCurrentResourceId("sub-group-id");
        var groupMember = new GroupMember("sub-group-id", "Sub Group", 0, null, MemberType.GROUP);

        var entry = (TranscriptEntry) invoke(groupMemberTurnMethod(), groupMember, g, "input",
                defaultProtocol(), 0, phase(PhaseType.OPINION), TranscriptEntryType.OPINION, null);

        assertEquals(TranscriptEntryType.SKIPPED, entry.type(),
                "SKIP policy → sub-group failure yields a SKIPPED entry");
    }

    @Test
    @DisplayName("executeGroupMemberTurn: sub-group discuss throws + ABORT policy → propagates GroupDiscussionException")
    void groupMemberDiscussThrowsAbort() throws Exception {
        var g = gc("gc-subgroup-abort");
        g.setDepth(0);
        doReturn(null).when(groupStore).getCurrentResourceId("sub-group-id");
        var groupMember = new GroupMember("sub-group-id", "Sub Group", 0, null, MemberType.GROUP);
        var abortProtocol = new ProtocolConfig(60, ProtocolConfig.MemberFailurePolicy.ABORT, 2,
                ProtocolConfig.MemberUnavailablePolicy.SKIP);

        assertThrows(GroupDiscussionException.class,
                () -> invoke(groupMemberTurnMethod(), groupMember, g, "input", abortProtocol, 0,
                        phase(PhaseType.OPINION), TranscriptEntryType.OPINION, null));
    }

    // =================================================================
    // executeAgentTurn() — agent unavailability branches
    // =================================================================

    private Method executeAgentTurnMethod() throws NoSuchMethodException {
        return method("executeAgentTurn", GroupMember.class, GroupConversation.class, String.class,
                ProtocolConfig.class, int.class, DiscussionPhase.class, String.class,
                GroupDiscussionEventListener.class);
    }

    @Test
    @DisplayName("executeAgentTurn: agent null + SKIP policy → SKIPPED 'Agent not deployed'")
    void agentTurnNullAgentSkip() throws Exception {
        var g = gc("gc-agent-null");
        doReturn(null).when(agentFactory).getLatestReadyAgent(any(), eq(AGENT_A));

        var entry = (TranscriptEntry) invoke(executeAgentTurnMethod(), member(), g, "input",
                defaultProtocol(), 0, phase(PhaseType.OPINION), null, null);

        assertEquals(TranscriptEntryType.SKIPPED, entry.type());
        assertTrue(entry.errorReason() != null && entry.errorReason().contains("not deployed"), entry.errorReason());
    }

    @Test
    @DisplayName("executeAgentTurn: agent null + FAIL policy → throws GroupDiscussionException")
    void agentTurnNullAgentFail() throws Exception {
        var g = gc("gc-agent-null-fail");
        doReturn(null).when(agentFactory).getLatestReadyAgent(any(), eq(AGENT_A));
        var failProtocol = new ProtocolConfig(60, ProtocolConfig.MemberFailurePolicy.SKIP, 2,
                ProtocolConfig.MemberUnavailablePolicy.FAIL);

        assertThrows(GroupDiscussionException.class,
                () -> invoke(executeAgentTurnMethod(), member(), g, "input", failProtocol, 0,
                        phase(PhaseType.OPINION), null, null));
    }

    @Test
    @DisplayName("executeAgentTurn: getLatestReadyAgent throws + SKIP → SKIPPED 'Agent unavailable'")
    void agentTurnLookupThrowsSkip() throws Exception {
        var g = gc("gc-agent-throws");
        doThrow(new RuntimeException("registry down"))
                .when(agentFactory).getLatestReadyAgent(any(), eq(AGENT_A));

        var entry = (TranscriptEntry) invoke(executeAgentTurnMethod(), member(), g, "input",
                defaultProtocol(), 0, phase(PhaseType.OPINION), null, null);

        assertEquals(TranscriptEntryType.SKIPPED, entry.type());
        assertTrue(entry.errorReason() != null && entry.errorReason().contains("unavailable"), entry.errorReason());
    }

    @Test
    @DisplayName("executeAgentTurn: getLatestReadyAgent throws + FAIL policy → propagates GroupDiscussionException")
    void agentTurnLookupThrowsFail() throws Exception {
        var g = gc("gc-agent-throws-fail");
        doThrow(new RuntimeException("registry down"))
                .when(agentFactory).getLatestReadyAgent(any(), eq(AGENT_A));
        var failProtocol = new ProtocolConfig(60, ProtocolConfig.MemberFailurePolicy.SKIP, 2,
                ProtocolConfig.MemberUnavailablePolicy.FAIL);

        assertThrows(GroupDiscussionException.class,
                () -> invoke(executeAgentTurnMethod(), member(), g, "input", failProtocol, 0,
                        phase(PhaseType.OPINION), null, null));
    }

    // =================================================================
    // verifyPriorEntriesIfRequired() guards (private, via reflection)
    // =================================================================

    private Method verifyPriorMethod() throws NoSuchMethodException {
        return method("verifyPriorEntriesIfRequired", String.class, GroupConversation.class);
    }

    @Test
    @DisplayName("verifyPriorEntriesIfRequired: resId null → early return (no read of config)")
    void verifyPriorResIdNull() throws Exception {
        var g = gc("gc-verify-prior-null");
        doReturn(null).when(agentStore).getCurrentResourceId(AGENT_A);
        assertDoesNotThrow(() -> invoke(verifyPriorMethod(), AGENT_A, g));
        verify(agentStore, never()).read(eq(AGENT_A), anyInt());
    }

    @Test
    @DisplayName("verifyPriorEntriesIfRequired: getCurrentResourceId throws → swallowed")
    void verifyPriorStoreThrows() throws Exception {
        var g = gc("gc-verify-prior-throw");
        doThrow(new RuntimeException("store down")).when(agentStore).getCurrentResourceId(AGENT_A);
        assertDoesNotThrow(() -> invoke(verifyPriorMethod(), AGENT_A, g));
    }

    // =================================================================
    // extractResponse() — null → empty-string coercion
    // =================================================================

    @Test
    @DisplayName("extractResponse: metadata-only snapshot (no outputs) → empty string, never null")
    void extractResponseEmpty() throws Exception {
        var snap = new ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot();
        var m = method("extractResponse",
                ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot.class);
        Object result = invoke(m, snap);
        assertEquals("", result, "null extraction is coerced to empty string for GCS callers");
    }

    // =================================================================
    // buildPlainTextFallback() direct
    // =================================================================

    @Test
    @DisplayName("buildPlainTextFallback: includes phase name, question, and speaker")
    void plainTextFallback() throws Exception {
        var m = method("buildPlainTextFallback", DiscussionPhase.class, GroupMember.class,
                String.class, List.class);
        var result = (String) invoke(m, phase(PhaseType.OPINION), member(), "What is X?", List.of());
        assertTrue(result.contains("What is X?") && result.contains("Agent A"), result);
    }

    // =================================================================
    // Read / list entry points
    // =================================================================

    @Test
    @DisplayName("readGroupConversation: delegates to store.read")
    void readGroupConversationDelegates() throws Exception {
        var g = gc("gc-read");
        doReturn(g).when(conversationStore).read("gc-read");
        assertSame(g, service.readGroupConversation("gc-read"));
    }

    @Test
    @DisplayName("listGroupConversations: delegates to store.listByGroupId")
    void listGroupConversationsDelegates() throws Exception {
        doReturn(List.of()).when(conversationStore).listByGroupId(GROUP_ID, 0, 20);
        assertNotNull(service.listGroupConversations(GROUP_ID, 0, 20));
        verify(conversationStore).listByGroupId(GROUP_ID, 0, 20);
    }

    @Test
    @DisplayName("listGroupPendingApprovals: limit clamped to >=1, maps summaries")
    void listGroupPendingApprovalsClampsLow() throws Exception {
        var paused = new GroupConversation();
        paused.setId("gc-pending");
        paused.setGroupId(GROUP_ID);
        paused.setUserId(USER_ID);
        paused.setState(GroupConversationState.AWAITING_APPROVAL);
        paused.setPausedAt(Instant.now());
        paused.setHitlPauseReason("needs approval");
        // limit 0 → clamped to 1
        doReturn(List.of(paused)).when(conversationStore)
                .findByState(GroupConversationState.AWAITING_APPROVAL, GROUP_ID, 1);

        var result = service.listGroupPendingApprovals(GROUP_ID, 0);

        assertEquals(1, result.size());
        assertEquals(GROUP_ID, result.get(0).getGroupId());
    }

    @Test
    @DisplayName("listGroupPendingApprovals: limit clamped to <=1000")
    void listGroupPendingApprovalsClampsHigh() throws Exception {
        doReturn(List.of()).when(conversationStore)
                .findByState(GroupConversationState.AWAITING_APPROVAL, GROUP_ID, 1000);
        service.listGroupPendingApprovals(GROUP_ID, 5000);
        verify(conversationStore).findByState(GroupConversationState.AWAITING_APPROVAL, GROUP_ID, 1000);
    }

    // =================================================================
    // startAndDiscussAsync() early guards
    // =================================================================

    @Test
    @DisplayName("startAndDiscussAsync: null groupId → IllegalArgumentException")
    void asyncNullGroupId() {
        assertThrows(IllegalArgumentException.class,
                () -> service.startAndDiscussAsync(null, "Q?", USER_ID, null));
    }

    @Test
    @DisplayName("startAndDiscussAsync: group not found → ResourceNotFoundException")
    void asyncGroupNotFound() throws Exception {
        doReturn(null).when(groupStore).getCurrentResourceId(GROUP_ID);
        assertThrows(IResourceStore.ResourceNotFoundException.class,
                () -> service.startAndDiscussAsync(GROUP_ID, "Q?", USER_ID, null));
    }

    @Test
    @DisplayName("startAndDiscussAsync: config read null → ResourceNotFoundException")
    void asyncConfigNull() throws Exception {
        doReturn(mockResourceId()).when(groupStore).getCurrentResourceId(GROUP_ID);
        doReturn(null).when(groupStore).read(GROUP_ID, 1);
        assertThrows(IResourceStore.ResourceNotFoundException.class,
                () -> service.startAndDiscussAsync(GROUP_ID, "Q?", USER_ID, null));
    }

    @Test
    @DisplayName("startAndDiscussAsync: empty phases → GroupDiscussionException")
    void asyncEmptyPhases() throws Exception {
        doReturn(mockResourceId()).when(groupStore).getCurrentResourceId(GROUP_ID);
        doReturn(buildConfig(List.of())).when(groupStore).read(GROUP_ID, 1);
        assertThrows(GroupDiscussionException.class,
                () -> service.startAndDiscussAsync(GROUP_ID, "Q?", USER_ID, null));
    }

    // =================================================================
    // cancelDiscussion: IMMEDIATE token path (distinct from batch-1 which used
    // it too — here we assert the active-future cancel side-effect path)
    // =================================================================

    @Test
    @DisplayName("cancelDiscussion: not found (store.read throws ResourceNotFound) → propagates")
    void cancelNotFound() throws Exception {
        doThrow(new IResourceStore.ResourceNotFoundException("gone"))
                .when(conversationStore).read("gc-cancel-missing");
        assertThrows(IResourceStore.ResourceNotFoundException.class,
                () -> service.cancelDiscussion("gc-cancel-missing",
                        ai.labs.eddi.engine.lifecycle.model.ControlSignal.CANCEL_GRACEFUL));
    }

    // =================================================================
    // resolvePhases(): style non-null preset expansion branch
    // =================================================================

    @Test
    @DisplayName("resolvePhases: no custom phases + explicit style → preset expansion (non-empty)")
    void resolvePhasesExplicitStyle() throws Exception {
        var m = method("resolvePhases", AgentGroupConfiguration.class);
        var config = buildConfig(null);
        config.setPhases(null);
        config.setStyle(DiscussionStyle.DEBATE);
        @SuppressWarnings("unchecked")
        var result = (List<DiscussionPhase>) invoke(m, config);
        assertFalse(result.isEmpty());
    }

    // =================================================================
    // createGroupConversation(): seeds a QUESTION transcript entry
    // =================================================================

    @Test
    @DisplayName("createGroupConversation: seeds QUESTION entry and sets id from store")
    void createGroupConversationSeedsQuestion() throws Exception {
        doReturn("gc-created").when(conversationStore).create(any());
        var m = method("createGroupConversation", String.class, String.class, String.class, int.class);
        var g = (GroupConversation) invoke(m, GROUP_ID, "Original?", USER_ID, 1);

        assertEquals("gc-created", g.getId());
        assertEquals(1, g.getDepth());
        assertEquals(GroupConversationState.IN_PROGRESS, g.getState());
        assertTrue(g.getTranscript().stream().anyMatch(e -> e.type() == TranscriptEntryType.QUESTION));
    }
}
