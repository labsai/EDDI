/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.IGroupConversationStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ContextScope;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionStyle;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.PhaseType;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ProtocolConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.TurnOrder;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.configs.groups.model.SharedTaskList;
import ai.labs.eddi.configs.groups.model.SharedTaskList.TaskItem;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IConversationService;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionEventListener;
import ai.labs.eddi.engine.api.IGroupConversationService.GroupDiscussionException;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.engine.tenancy.QuotaAccountingUnavailableException;
import ai.labs.eddi.engine.tenancy.QuotaExceededException;
import ai.labs.eddi.engine.tenancy.QuotaRefusal;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

/**
 * The two group engines that decide whether a phase survives a member failure
 * must treat <em>every</em> quota refusal as fatal to the whole discussion, not
 * just {@link QuotaExceededException}.
 * <p>
 * {@code QuotaAccountingUnavailableException} had to extend
 * {@code RejectedExecutionException} (so every surface that already answers 503
 * for backpressure answers 503 for it), which means it is a <em>sibling</em> of
 * {@code QuotaExceededException}, not a subclass. The moment
 * {@code ConversationService} started throwing it for accounting outages, the
 * {@code instanceof QuotaExceededException} guards in
 * {@code PhaseExecutionEngine} and {@code TaskForceEngine} silently stopped
 * matching: under the default SKIP policy a quota-store outage became a
 * transcript full of ERROR/SKIPPED entries and a discussion reported complete,
 * and under RETRY it became {@code maxRetries} attempts per member against a
 * store that cannot answer. Both guards now match the {@code QuotaRefusal}
 * marker, and these tests hold them to it.
 * <p>
 * Deliberately driven through the private phase methods by reflection, the same
 * way {@code GroupConversationServiceConcurrencyTest} does: the guards live
 * inside the fan-out, which no public entry point lets a test reach without
 * standing up a whole discussion.
 */
@DisplayName("GroupConversationService — a quota refusal aborts the phase")
class GroupConversationServiceQuotaRefusalTest {

    private static final String OUTAGE_REASON = "Quota accounting unavailable — denying request for safety";

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
    private IAgent agent;

    private GroupConversationService service;

    private static final String QUESTION = "What should we do?";

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.openMocks(this);
        service = new GroupConversationService(
                groupStore, conversationStore, conversationService,
                agentFactory, templatingEngine, jsonSerialization,
                new SimpleMeterRegistry(), null, null, null, null, null,
                new CallerIdentityContext(null, null), "default", 3);

        doReturn(agent).when(agentFactory).getLatestReadyAgent(any(), any());
    }

    /**
     * The parallel-opinion path: the refusal is raised inside a {@code supplyAsync}
     * body, wrapped in a {@code CompletionException}, and has to be recognised
     * again after {@code Future.get()} rewraps it in an {@code ExecutionException}.
     * Both hops are the changed lines.
     */
    @Test
    @Timeout(90)
    @DisplayName("parallel phase: a quota-store outage propagates out instead of becoming an error transcript entry")
    void parallelPhase_accountingUnavailable_abortsTheDiscussion() throws Exception {
        var gc = groupConversation("gc-parallel-quota-outage");
        List<GroupMember> members = List.of(member(0), member(1));

        doThrow(new QuotaAccountingUnavailableException(OUTAGE_REASON))
                .when(conversationService).startConversation(any(), any(), any(), any());

        var thrown = assertThrows(GroupDiscussionException.class,
                () -> invoke(phaseMethod("executeParallelPhase"), gc, config(members), members,
                        phase(PhaseType.OPINION, TurnOrder.PARALLEL), protocol(30), QUESTION, 0, null,
                        new AtomicInteger(0), 50));

        assertInstanceOf(QuotaAccountingUnavailableException.class, thrown.getCause(),
                "the outage must reach the caller intact, so the REST layer can answer 503 rather than 'discussion complete'");
        assertTrue(thrown.getMessage().contains("accounting unavailable"), thrown.getMessage());
        assertFalse(thrown.getMessage().contains("Tenant quota exceeded"),
                "the abort must not claim the tenant went over a limit");
        assertTrue(gc.getTranscript().stream().noneMatch(e -> e.type() == TranscriptEntryType.ERROR),
                "a quota refusal is not one member's failure, so it must not be written up as one: " + gc.getTranscript());
    }

    /**
     * The over-limit half of the same guard, which the marker interface must not
     * have broken while widening it.
     */
    @Test
    @Timeout(90)
    @DisplayName("parallel phase: an over-limit denial still aborts")
    void parallelPhase_quotaExceeded_stillAborts() throws Exception {
        var gc = groupConversation("gc-parallel-quota-exceeded");
        List<GroupMember> members = List.of(member(0));

        doThrow(new QuotaExceededException("Daily conversation limit reached (1000)"))
                .when(conversationService).startConversation(any(), any(), any(), any());

        var thrown = assertThrows(GroupDiscussionException.class,
                () -> invoke(phaseMethod("executeParallelPhase"), gc, config(members), members,
                        phase(PhaseType.OPINION, TurnOrder.PARALLEL), protocol(30), QUESTION, 0, null,
                        new AtomicInteger(0), 50));

        assertInstanceOf(QuotaExceededException.class, thrown.getCause());
        assertTrue(thrown.getMessage().startsWith("Tenant quota exceeded"), thrown.getMessage());
    }

    /**
     * The TASK_FORCE wave: the refusal is collected into the wave's error list by
     * the worker and re-thrown by the wave loop, which is a second, independent
     * {@code QuotaRefusal} guard. Under SKIP — the default — the wave would
     * otherwise mark the task FAILED and carry on.
     */
    @Test
    @Timeout(90)
    @DisplayName("task-force wave: a quota-store outage aborts the wave rather than failing the task")
    void taskExecutionPhase_accountingUnavailable_abortsTheWave() throws Exception {
        var gc = groupConversation("gc-taskforce-quota-outage");
        var member = member(0);
        var taskList = new SharedTaskList();
        var task = taskList.addTask(new TaskItem("Task A", "do A", 0));
        taskList.assignTask(task.id(), member.agentId(), member.displayName());
        gc.setTaskList(taskList);

        doThrow(new QuotaAccountingUnavailableException(OUTAGE_REASON))
                .when(conversationService).startConversation(any(), any(), any(), any());

        var thrown = assertThrows(GroupDiscussionException.class,
                () -> invoke(phaseMethod("executeTaskExecutionPhase"), gc, config(List.of(member)), List.of(member),
                        phase(PhaseType.EXECUTE, TurnOrder.PARALLEL), protocol(30), QUESTION, 0, null,
                        new AtomicInteger(0), 50));

        assertInstanceOf(QuotaAccountingUnavailableException.class, thrown.getCause(),
                "the wave must surface the outage, not swallow it into a FAILED task under the SKIP policy");
        assertEquals(1, gc.getTaskList().all().size());
        assertFalse(thrown.getMessage().contains("Tenant quota exceeded"), thrown.getMessage());
    }

    /**
     * The other side of the guard, and the reason it is a guard rather than a
     * blanket abort: an ordinary member failure is one agent's problem, so it
     * becomes an ERROR entry in the transcript and the remaining speakers still
     * run. Widening the match to the {@code QuotaRefusal} marker must not have
     * widened it to every {@code GroupDiscussionException}.
     */
    @Test
    @Timeout(90)
    @DisplayName("parallel phase: an ordinary member failure is recorded, not propagated")
    void parallelPhase_ordinaryFailure_isRecordedAsAnErrorEntry() throws Exception {
        var gc = groupConversation("gc-parallel-ordinary-failure");
        List<GroupMember> members = List.of(member(0), member(1));

        // ABORT is what makes executeAgentTurn raise a GroupDiscussionException at
        // the same call site the quota guard sits on — with a cause that is not a
        // quota refusal.
        var abortOnFailure = new ProtocolConfig(30, ProtocolConfig.MemberFailurePolicy.ABORT, 0,
                ProtocolConfig.MemberUnavailablePolicy.SKIP);
        doThrow(new IllegalStateException("agent backend exploded"))
                .when(conversationService).startConversation(any(), any(), any(), any());

        invoke(phaseMethod("executeParallelPhase"), gc, config(members), members,
                phase(PhaseType.OPINION, TurnOrder.PARALLEL), abortOnFailure, QUESTION, 0, null,
                new AtomicInteger(0), 50);

        assertEquals(2, gc.getTranscript().size(),
                "both members must be accounted for rather than the phase unwinding on the first failure");
        assertTrue(gc.getTranscript().stream().allMatch(e -> e.type() == TranscriptEntryType.ERROR),
                "a non-quota failure belongs in the transcript: " + gc.getTranscript());
    }

    /**
     * The task-force twin, and the case that separates the two guards. Under ABORT
     * an ordinary member failure reaches the same {@code catch} the quota guard
     * sits in, and must fall through it: the task is written up as FAILED and the
     * wave's own ABORT policy — not the quota guard — is what ends it. Widening the
     * match to the {@code QuotaRefusal} marker must not have turned every member
     * failure into an immediate wave abort that skips {@code recordTaskFailure} and
     * leaves the task in flight.
     */
    @Test
    @Timeout(90)
    @DisplayName("task-force wave: an ordinary member failure is written up before the ABORT policy ends the wave")
    void taskExecutionPhase_ordinaryFailure_isRecordedBeforeAbort() throws Exception {
        var gc = groupConversation("gc-taskforce-ordinary-failure");
        var member = member(0);
        var taskList = new SharedTaskList();
        var task = taskList.addTask(new TaskItem("Task A", "do A", 0));
        taskList.assignTask(task.id(), member.agentId(), member.displayName());
        gc.setTaskList(taskList);

        var abortOnFailure = new ProtocolConfig(30, ProtocolConfig.MemberFailurePolicy.ABORT, 0,
                ProtocolConfig.MemberUnavailablePolicy.SKIP);
        doThrow(new RuntimeException("agent backend exploded"))
                .when(conversationService).startConversation(any(), any(), any(), any());

        var thrown = assertThrows(GroupDiscussionException.class,
                () -> invoke(phaseMethod("executeTaskExecutionPhase"), gc, config(List.of(member)), List.of(member),
                        phase(PhaseType.EXECUTE, TurnOrder.PARALLEL), abortOnFailure, QUESTION, 0, null,
                        new AtomicInteger(0), 50));

        assertFalse(thrown.getCause() instanceof QuotaRefusal,
                "the wave must end on the member's own failure, not be re-labelled as a quota refusal");
        assertFalse(thrown.getMessage().contains("quota"), thrown.getMessage());
        assertEquals(SharedTaskList.TaskStatus.FAILED, gc.getTaskList().all().getFirst().status(),
                "the task the failing member held must be written up, not left in flight");
    }

    // ==================== helpers ====================

    private GroupConversation groupConversation(String id) {
        var gc = new GroupConversation();
        gc.setId(id);
        gc.setGroupId("group-quota-refusal");
        gc.setUserId("user-quota-refusal");
        gc.setState(GroupConversationState.IN_PROGRESS);
        gc.setOriginalQuestion(QUESTION);
        gc.setTranscript(new ArrayList<>());
        gc.setMemberConversationIds(new ConcurrentHashMap<>());
        return gc;
    }

    private GroupMember member(int index) {
        return new GroupMember("agent-" + index, "Agent " + index, index, null);
    }

    private AgentGroupConfiguration config(List<GroupMember> members) {
        var config = new AgentGroupConfiguration();
        config.setName("Quota Refusal Group");
        config.setStyle(DiscussionStyle.CUSTOM);
        config.setMembers(members);
        return config;
    }

    private DiscussionPhase phase(PhaseType type, TurnOrder turnOrder) {
        return new DiscussionPhase("P-" + type, type, "ALL", turnOrder, ContextScope.FULL, false, null, 1, false);
    }

    /**
     * SKIP is the default failure policy — the one the broken guard fell through
     * to.
     */
    private ProtocolConfig protocol(int agentTimeoutSeconds) {
        return new ProtocolConfig(agentTimeoutSeconds, ProtocolConfig.MemberFailurePolicy.SKIP, 0,
                ProtocolConfig.MemberUnavailablePolicy.SKIP);
    }

    private Method phaseMethod(String name) throws NoSuchMethodException {
        Method m = GroupConversationService.class.getDeclaredMethod(name,
                GroupConversation.class, AgentGroupConfiguration.class, List.class, DiscussionPhase.class,
                ProtocolConfig.class, String.class, int.class, GroupDiscussionEventListener.class,
                AtomicInteger.class, int.class);
        m.setAccessible(true);
        return m;
    }

    private void invoke(Method m, Object... args) throws Exception {
        try {
            m.invoke(service, args);
        } catch (InvocationTargetException e) {
            if (e.getCause() instanceof Exception ex) {
                throw ex;
            }
            throw e;
        }
    }
}
