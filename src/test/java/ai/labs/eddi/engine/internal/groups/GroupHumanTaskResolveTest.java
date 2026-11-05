/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.IGroupConversationStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.MemberType;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.PhaseType;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.configs.groups.model.GroupConversation.HitlPauseType;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.configs.groups.model.HumanRequestDetail;
import ai.labs.eddi.configs.groups.model.SharedTaskList;
import ai.labs.eddi.configs.groups.model.SharedTaskList.TaskItem;
import ai.labs.eddi.configs.groups.model.SharedTaskList.TaskStatus;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.internal.GroupConversationService;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A human task's answer through the shared human-turn resolution: it completes
 * the task inside the same compare-and-swap, a timeout fails it, and an
 * executor that refuses the resume puts it back.
 */
@DisplayName("Human task resolution through the human-turn path")
class GroupHumanTaskResolveTest {

    private static final String HUMAN = "hannah@acme.com";

    private IGroupConversationStore conversationStore;
    private ExecutorService executor;
    private GroupHitlCoordinator coordinator;
    private final List<Runnable> submitted = new ArrayList<>();
    private GroupConversation gc;
    private TaskItem task;

    @BeforeEach
    void setUp() throws Exception {
        conversationStore = mock(IGroupConversationStore.class);
        var groupStore = mock(IAgentGroupStore.class);
        var service = mock(GroupConversationService.class);
        executor = mock(ExecutorService.class);
        when(executor.submit(any(Runnable.class))).thenAnswer(inv -> {
            submitted.add(inv.getArgument(0));
            return null;
        });
        var meters = new SimpleMeterRegistry();
        coordinator = new GroupHitlCoordinator(groupStore, conversationStore, mock(IScheduleStore.class), mock(AuditLedgerService.class),
                new GroupSigningGuard(null, null, null, "default"), new ConcurrentHashMap<>(), executor, new CallerIdentityContext(null, null),
                service, meters.counter("p"), meters.counter("r"), meters.counter("f"));

        var config = new AgentGroupConfiguration();
        config.setModeratorAgentId("lead");
        config.setMembers(List.of(new GroupMember("lead", "Lead", 0, null), new GroupMember(HUMAN, "Hannah", 1, null, MemberType.HUMAN)));
        config.setPhases(List.of(new DiscussionPhase("Plan", PhaseType.PLAN), new DiscussionPhase("Execute", PhaseType.EXECUTE)));
        var rid = mock(IResourceStore.IResourceId.class);
        when(rid.getVersion()).thenReturn(1);
        when(groupStore.getCurrentResourceId("g")).thenReturn(rid);
        when(groupStore.read(eq("g"), anyInt())).thenReturn(config);
        when(service.effectivePhases(any(), any())).thenReturn(config.getPhases());

        gc = new GroupConversation();
        gc.setId("gc-1");
        gc.setGroupId("g");
        gc.setOriginalQuestion("Launch?");
        gc.setTaskList(new SharedTaskList());
        task = gc.getTaskList().addTask(new TaskItem("Sign-off", "legal review", 0));
        gc.getTaskList().assignTask(task.id(), HUMAN, "Hannah");
        gc.setState(GroupConversationState.AWAITING_HUMAN_INPUT);
        gc.setHitlPauseType(HitlPauseType.HUMAN_TURN);
        gc.setPausedAt(Instant.now());
        gc.setPausedPhaseName("Execute");
        gc.setHitlApprovalTimeout("PT4H");
        gc.setResumePoint(new GroupConversation.ResumePoint(1, 0, -1, GroupConversation.RESUME_KIND_HUMAN_TURN));
        gc.setPendingHumanInput(new GroupConversation.PendingHumanInput(HUMAN, "Hannah", 1, 0, -1, "TASK_RESULT", "Do the review",
                "SKIP_TURN", Instant.now(), new HumanRequestDetail(HumanRequestDetail.KIND_TASK, null, task.id(), null, null)));
        when(conversationStore.read("gc-1")).thenReturn(gc);
    }

    private TaskStatus status() {
        return gc.getTaskList().findById(task.id()).status();
    }

    @Test
    @DisplayName("a submission completes the task and records a TASK_RESULT entry")
    void submissionCompletes() throws Exception {
        coordinator.submitHumanInput("gc-1", HUMAN, "Signed off", HUMAN, null);

        assertEquals(TaskStatus.COMPLETED, status());
        assertEquals("Signed off", gc.getTaskList().findById(task.id()).result());
        assertEquals(TranscriptEntryType.TASK_RESULT, gc.getTranscript().getLast().type());
        assertEquals(1, submitted.size(), "the EXECUTE phase resumes to run what depended on it");
    }

    @Test
    @DisplayName("a timeout fails the task")
    void timeoutFails() {
        coordinator.skipHumanTurnOnTimeout("gc-1", null);

        assertEquals(TaskStatus.FAILED, status());
        assertEquals(TranscriptEntryType.SKIPPED, gc.getTranscript().getLast().type());
    }

    @Test
    @DisplayName("an executor that refuses the resume puts the task back")
    void rollbackRestoresTask() {
        when(executor.submit(any(Runnable.class))).thenThrow(new RejectedExecutionException("full"));

        assertThrows(Exception.class, () -> coordinator.submitHumanInput("gc-1", HUMAN, "Signed off", HUMAN, null));

        assertEquals(TaskStatus.ASSIGNED, status());
        assertEquals(GroupConversationState.AWAITING_HUMAN_INPUT, gc.getState());
    }
}
