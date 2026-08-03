/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.impl;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupTaskConfig;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.SharedTaskList;
import ai.labs.eddi.configs.groups.model.SharedTaskList.TaskItem;
import ai.labs.eddi.configs.groups.model.SharedTaskList.TaskStatus;
import ai.labs.eddi.engine.internal.groups.LiveDiscussionRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link GroupTaskTools} (I5).
 * <p>
 * The caps and the rejection messages carry the weight. This is a write tool
 * handed to an LLM: everything it refuses, it refuses to a model that will read
 * the sentence and try again, so a rejection that does not say what was wrong
 * produces the same call a second time. And a cap that can be exceeded is a cap
 * that does not exist — the list is re-serialized into every subsequent turn's
 * prompt, so unbounded growth is a cost problem, not just a tidiness one.
 *
 * @author tests
 */
class GroupTaskToolsTest {

    private static final String GC_ID = "gc-1";
    private static final String GROUP_ID = "group-1";
    private static final String AGENT = "agent-a";

    private LiveDiscussionRegistry registry;
    private GroupConversation gc;

    private static final List<GroupMember> MEMBERS = List.of(
            new GroupMember("agent-a", "Ann", 1, "WRITER"),
            new GroupMember("agent-b", "Ben", 2, "REVIEWER"),
            new GroupMember("mod", "Mo", 0, "MODERATOR"));

    @BeforeEach
    void setUp() {
        registry = new LiveDiscussionRegistry();
        gc = new GroupConversation();
        gc.setId(GC_ID);
        gc.setGroupId(GROUP_ID);
        gc.setTaskList(new SharedTaskList());
        registry.register(gc);
    }

    private GroupTaskTools tools() {
        return tools(new GroupTaskConfig(true, 20, 3));
    }

    private GroupTaskTools tools(GroupTaskConfig config) {
        return new GroupTaskTools(registry, GC_ID, config, AGENT, MEMBERS, "mod");
    }

    private List<TaskItem> tasks() {
        return gc.getTaskList().all();
    }

    // =================================================================
    // Filing
    // =================================================================

    @Test
    void filedTask_landsOnTheLiveDiscussion_andIsExecutableNextWave() {
        String reply = tools().addGroupTask("Backfill migration", "The 003 migration never ran in staging.", null, null, null);

        assertTrue(reply.startsWith("Filed"), reply);
        assertEquals(1, tasks().size());
        TaskItem filed = tasks().get(0);
        assertEquals("Backfill migration", filed.subject());
        assertEquals(AGENT, filed.createdByAgentId(), "attribution is what separates agent-filed work from planned work");
        // The whole point: the wave loop re-queries this every wave, so a task filed
        // now is picked up without any scheduler change.
        assertTrue(gc.getTaskList().findExecutableTasks().stream().anyMatch(t -> t.id().equals(filed.id())));
    }

    @Test
    void filedTask_survivesTheLoopsNextPersist() {
        // The mutation must land on the instance the loop holds. Writing to a copy
        // loaded from the store would be clobbered by the loop's next whole-document
        // write, which is the entire reason F1's registry exists.
        tools().addGroupTask("A", "d", null, null, null);

        assertSame(gc, registry.get(GC_ID).orElseThrow());
        assertEquals(1, registry.get(GC_ID).orElseThrow().getTaskList().size());
    }

    @Test
    void dependenciesAreNamedBySubject_notById() {
        gc.getTaskList().addTask(new TaskItem("Write draft", "d", 0));

        String reply = tools().addGroupTask("Review draft", "d", List.of("write draft"), null, null);

        assertTrue(reply.startsWith("Filed"), reply);
        TaskItem filed = tasks().get(1);
        assertEquals(1, filed.dependsOnIds().size());
        assertEquals(tasks().get(0).id(), filed.dependsOnIds().get(0), "subject match is case-insensitive");
        // It depends on unfinished work, so it must NOT be executable yet.
        assertFalse(gc.getTaskList().findExecutableTasks().stream().anyMatch(t -> t.id().equals(filed.id())));
    }

    @Test
    void attributionSurvivesTheLoopsOwnStateTransitions() {
        // TaskItem gained createdByAgentId as a 14th component, and every mutator
        // rebuilds the record positionally. A mutator that forgot to carry it would
        // erase the author the moment the wave loop assigned the task — and the
        // discussion cap counts exactly this field, so the cap would silently reset
        // itself as tasks progressed.
        tools().addGroupTask("Filed work", "d", null, null, null);
        String id = tasks().get(0).id();

        gc.getTaskList().assignTask(id, "agent-b", "Ben");
        assertEquals(AGENT, gc.getTaskList().findById(id).createdByAgentId(), "lost on assign");

        gc.getTaskList().startTask(id);
        assertEquals(AGENT, gc.getTaskList().findById(id).createdByAgentId(), "lost on start");

        gc.getTaskList().completeTask(id, "done");
        assertEquals(AGENT, gc.getTaskList().findById(id).createdByAgentId(), "lost on complete");

        gc.getTaskList().verifyTask(id, true, "ok");
        assertEquals(AGENT, gc.getTaskList().findById(id).createdByAgentId(), "lost on verify");
    }

    @Test
    void plannedTasksCarryNoAttribution() {
        gc.getTaskList().addTask(new TaskItem("Planned", "d", 0));

        assertNull(tasks().get(0).createdByAgentId(),
                "config- and PLAN-authored tasks must stay distinguishable from agent-filed ones");
    }

    // =================================================================
    // Rejections — each one has to tell the model what to do instead
    // =================================================================

    @Test
    void unknownDependency_isRefusedRatherThanDropped() {
        // Filing it anyway would schedule the task immediately — the opposite of
        // what was asked for, and invisible.
        String reply = tools().addGroupTask("B", "d", List.of("no such task"), null, null);

        assertFalse(reply.startsWith("Filed"), reply);
        assertTrue(reply.contains("no such task"), reply);
        assertTrue(reply.contains("listGroupTasks"), "the model needs to know how to find the real subjects: " + reply);
        assertTrue(tasks().isEmpty());
    }

    @Test
    void duplicateSubject_isRefused() {
        tools().addGroupTask("Fix the flake", "d", null, null, null);

        String reply = tools().addGroupTask("fix the flake", "d again", null, null, null);

        assertFalse(reply.startsWith("Filed"), reply);
        assertEquals(1, tasks().size(), "case-insensitive, or the same work gets filed twice with different capitalization");
    }

    @Test
    void circularDependency_isRefusedAndRolledBack() {
        gc.getTaskList().addTask(new TaskItem("A", "d", 0));
        var a = tasks().get(0);
        // Make A depend on the not-yet-existing B by id after B is filed; the cycle
        // only closes when B depends back on A.
        var tools = tools();
        tools.addGroupTask("B", "d", List.of("A"), null, null);
        var b = tasks().get(1);
        gc.getTaskList().updateTask(new TaskItem(a.id(), a.subject(), a.description(), a.status(),
                a.assignedAgentId(), a.assignedDisplayName(), List.of(b.id()), a.result(), a.verificationNote(),
                a.verified(), a.priority(), a.createdAt(), a.completedAt(), a.createdByAgentId()));

        String reply = tools.addGroupTask("C", "d", List.of("B"), null, null);

        // C itself does not close a cycle, but the list already contains one, so the
        // insert must be rolled back rather than left behind.
        assertFalse(reply.startsWith("Filed"), reply);
        assertTrue(reply.contains("circular"), reply);
        assertEquals(2, tasks().size(), "the rejected task must not linger in the list");
    }

    @Test
    void oversizedSubject_isRefused() {
        String reply = tools().addGroupTask("x".repeat(SharedTaskList.MAX_AGENT_TASK_SUBJECT_LENGTH + 1), "d", null, null, null);

        assertFalse(reply.startsWith("Filed"), reply);
        assertTrue(reply.contains("description"), "tell the model where the detail belongs: " + reply);
        assertTrue(tasks().isEmpty());
    }

    @Test
    void oversizedDescription_isRefused() {
        String reply = tools().addGroupTask("A", "x".repeat(SharedTaskList.MAX_AGENT_TASK_DESCRIPTION_LENGTH + 1), null, null, null);

        assertFalse(reply.startsWith("Filed"), reply);
        assertTrue(tasks().isEmpty());
    }

    @Test
    void blankSubject_isRefused() {
        assertFalse(tools().addGroupTask("   ", "d", null, null, null).startsWith("Filed"));
        assertFalse(tools().addGroupTask(null, "d", null, null, null).startsWith("Filed"));
        assertTrue(tasks().isEmpty());
    }

    // =================================================================
    // Caps
    // =================================================================

    @Test
    void perTurnCap_stopsAtTheLimit() {
        var tools = tools(new GroupTaskConfig(true, 20, 2));

        assertTrue(tools.addGroupTask("A", "d", null, null, null).startsWith("Filed"));
        assertTrue(tools.addGroupTask("B", "d", null, null, null).startsWith("Filed"));
        String third = tools.addGroupTask("C", "d", null, null, null);

        assertFalse(third.startsWith("Filed"), third);
        assertTrue(third.contains("this turn"), third);
        assertEquals(2, tasks().size());
    }

    @Test
    void perTurnCap_isPerInstance_soANewTurnStartsFresh() {
        var config = new GroupTaskConfig(true, 20, 1);
        assertTrue(tools(config).addGroupTask("A", "d", null, null, null).startsWith("Filed"));

        // A new turn rebuilds the tool set, which is what makes the counter per-turn.
        assertTrue(tools(config).addGroupTask("B", "d", null, null, null).startsWith("Filed"));
        assertEquals(2, tasks().size());
    }

    @Test
    void rejectedCalls_doNotConsumeTheTurnBudget() {
        // Otherwise one malformed argument silences the rest of the turn.
        var tools = tools(new GroupTaskConfig(true, 20, 1));

        assertFalse(tools.addGroupTask("", "d", null, null, null).startsWith("Filed"));
        assertTrue(tools.addGroupTask("Real task", "d", null, null, null).startsWith("Filed"));
    }

    @Test
    void discussionCap_countsOnlyAgentFiledTasks() {
        // Config- and PLAN-authored tasks have no createdByAgentId, so a planned
        // backlog must not exhaust the agents' budget before they file anything.
        for (int i = 0; i < 5; i++) {
            gc.getTaskList().addTask(new TaskItem("planned-" + i, "d", 0));
        }
        var config = new GroupTaskConfig(true, 2, 10);
        var tools = tools(config);

        assertTrue(tools.addGroupTask("A", "d", null, null, null).startsWith("Filed"));
        assertTrue(tools.addGroupTask("B", "d", null, null, null).startsWith("Filed"));
        String third = tools.addGroupTask("C", "d", null, null, null);

        assertFalse(third.startsWith("Filed"), third);
        assertTrue(third.contains("full"), third);
        assertEquals(7, tasks().size(), "5 planned + 2 filed");
    }

    @Test
    void discussionCap_survivesANewTurn() {
        var config = new GroupTaskConfig(true, 1, 10);
        assertTrue(tools(config).addGroupTask("A", "d", null, null, null).startsWith("Filed"));

        // Unlike the per-turn cap, this one is read off the list itself, so a fresh
        // tool instance cannot reset it.
        assertFalse(tools(config).addGroupTask("B", "d", null, null, null).startsWith("Filed"));
    }

    // =================================================================
    // assignToRole
    // =================================================================

    @Test
    void namedRole_filesTheTaskAlreadyAssigned() {
        String reply = tools().addGroupTask("Review it", "d", null, null, "ROLE:REVIEWER");

        assertTrue(reply.contains("Ben"), reply);
        TaskItem filed = tasks().get(0);
        assertEquals("agent-b", filed.assignedAgentId());
        assertEquals("Ben", filed.assignedDisplayName());
        // Assigned at insert, not by a follow-up call — between the two, a concurrent
        // wave would have claimed it and overwritten the requested owner.
        assertEquals(TaskStatus.ASSIGNED, filed.status());
    }

    @Test
    void directMemberName_alsoResolves() {
        tools().addGroupTask("For Ann", "d", null, null, "Ann");

        assertEquals("agent-a", tasks().get(0).assignedAgentId());
    }

    @Test
    void unknownRole_isRefusedWithTheRosterNamed() {
        String reply = tools().addGroupTask("X", "d", null, null, "ROLE:NOBODY");

        assertFalse(reply.startsWith("Filed"), reply);
        assertTrue(reply.contains("REVIEWER"), "name the roles that do exist so the retry can succeed: " + reply);
        assertTrue(tasks().isEmpty(), "a task the agent wanted owned must not be filed unowned instead");
    }

    @Test
    void omittedOrAll_leavesTheTaskForTheLoopToAssign() {
        // "ALL" is deliberately not round-robined here: round-robin keys off a task
        // index the loop assigns, and a filed task has no position in the plan.
        tools().addGroupTask("A", "d", null, null, null);
        tools().addGroupTask("B", "d", null, null, "ALL");

        assertTrue(tasks().stream().allMatch(t -> t.status() == TaskStatus.PENDING));
        assertTrue(tasks().stream().allMatch(t -> t.assignedAgentId() == null));
    }

    // =================================================================
    // Not-live, and concurrency
    // =================================================================

    @Test
    void unregisteredDiscussion_refusesRatherThanWritingSomewhereElse() {
        registry.unregister(GC_ID);

        String reply = tools().addGroupTask("A", "d", null, null, null);

        assertFalse(reply.startsWith("Filed"), reply);
        assertTrue(reply.contains("finished") || reply.contains("paused"), reply);
        assertEquals(0, gc.getTaskList().size(), "a write to a stale copy would be silently clobbered anyway");
    }

    @Test
    void listGroupTasks_reportsStatusAndBlocking() {
        tools().addGroupTask("First", "d", null, null, "ROLE:REVIEWER");
        tools().addGroupTask("Second", "d", List.of("First"), null, null);

        String listing = tools().listGroupTasks();

        assertTrue(listing.contains("First"), listing);
        assertTrue(listing.contains("Ben"), listing);
        assertTrue(listing.contains("waits on 1"), listing);
    }

    @Test
    void listGroupTasks_emptyAndUnregistered() {
        assertTrue(tools().listGroupTasks().contains("empty"));
        registry.unregister(GC_ID);
        assertTrue(tools().listGroupTasks().contains("No task list"));
    }

    @Test
    void concurrentFilingFromParallelSpeakers_losesNothingAndDuplicatesNothing() throws Exception {
        // A PARALLEL phase runs every speaker at once, each with its own tool
        // instance against one shared list. Validation outside the lock would let two
        // callers both pass the duplicate check, or interleave into a corrupt list.
        int speakers = 8;
        var start = new CountDownLatch(1);
        var done = new CountDownLatch(speakers);
        var filed = new AtomicInteger();
        var threads = new ArrayList<Thread>();
        for (int i = 0; i < speakers; i++) {
            final int n = i;
            threads.add(Thread.ofVirtual().unstarted(() -> {
                try {
                    start.await();
                    // Half the speakers contend for the SAME subject.
                    String subject = n % 2 == 0 ? "shared subject" : "unique-" + n;
                    if (tools().addGroupTask(subject, "d", null, null, null).startsWith("Filed")) {
                        filed.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }));
        }
        threads.forEach(Thread::start);
        start.countDown();
        assertTrue(done.await(10, TimeUnit.SECONDS), "filing deadlocked");

        assertEquals(filed.get(), tasks().size(), "every accepted call must be in the list exactly once");
        assertEquals(1, tasks().stream().filter(t -> "shared subject".equals(t.subject())).count(),
                "exactly one of the four contending calls may win the shared subject");
        assertEquals(5, tasks().size(), "4 unique + 1 shared");
    }
}
