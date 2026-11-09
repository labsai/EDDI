/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.apicalls.IApiCallsStore;
import ai.labs.eddi.configs.apicalls.model.ApiCall;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.groups.IGroupConversationStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ApprovedAction;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ApprovedActionFailurePolicy;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ApprovedActionType;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ContextScope;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.OnApprovedConfig;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.PhaseType;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.TurnOrder;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.configs.groups.model.SharedTaskList;
import ai.labs.eddi.configs.groups.model.SharedTaskList.ApprovedActionRecord;
import ai.labs.eddi.configs.groups.model.SharedTaskList.ApprovedActionStatus;
import ai.labs.eddi.configs.groups.model.SharedTaskList.ReviewerEdit;
import ai.labs.eddi.configs.groups.model.SharedTaskList.TaskItem;
import ai.labs.eddi.configs.groups.model.SharedTaskList.TaskStatus;
import ai.labs.eddi.configs.groups.validation.ApprovedResultActionValidation;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.modules.apicalls.impl.IApiCallExecutor;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class GroupApprovedResultActionsTest {

    private static final String URI = "eddi://ai.labs.httpcalls/httpcallsstore/httpcalls/6740a1b2c3d4e5f6a7b8c9d0?version=2";
    private static final String CALL = "postComment";

    private IApiCallsStore apiCallsStore;
    private IApiCallExecutor executor;
    private ApprovedResultActionValidation validation;
    private AuditLedgerService ledger;
    private SimpleMeterRegistry meters;
    private IGroupConversationStore store;
    private GroupApprovedResultActions actions;
    private ApiCall call;

    @BeforeEach
    void setUp() throws Exception {
        apiCallsStore = mock(IApiCallsStore.class);
        executor = mock(IApiCallExecutor.class);
        validation = mock(ApprovedResultActionValidation.class);
        ledger = mock(AuditLedgerService.class);
        when(ledger.isEnabled()).thenReturn(true);
        meters = new SimpleMeterRegistry();
        store = mock(IGroupConversationStore.class);
        actions = new GroupApprovedResultActions(apiCallsStore, executor, validation, ledger, meters);

        call = new ApiCall();
        call.setName(CALL);
        var httpCalls = new ApiCallsConfiguration();
        httpCalls.setTargetServerUrl("https://api.github.com");
        httpCalls.setHttpCalls(List.of(call));
        when(apiCallsStore.read("6740a1b2c3d4e5f6a7b8c9d0", 2)).thenReturn(httpCalls);
        when(validation.referenceProblems(anyString(), any())).thenReturn(List.of());
    }

    private static DiscussionPhase phase(ApprovedActionFailurePolicy onFailure) {
        return new DiscussionPhase("Draft", PhaseType.EXECUTE, "ALL", TurnOrder.SEQUENTIAL, ContextScope.FULL, false, null, 1, true, null,
                false, null, null, new OnApprovedConfig(new ApprovedAction(ApprovedActionType.HTTPCALL, URI, CALL), onFailure));
    }

    /** A discussion with one task submitted for approval, and the task's id. */
    private static GroupConversation awaitingGc(TaskItem task, String agentResult) {
        var gc = new GroupConversation();
        gc.setId("gc-1");
        gc.setGroupId("group-1");
        gc.setUserId("user-1");
        gc.setState(GroupConversationState.IN_PROGRESS);
        var list = new SharedTaskList();
        list.addTask(task);
        list.assignTask(task.id(), "writer", "Writer");
        list.startTask(task.id());
        list.submitForApproval(task.id(), agentResult);
        gc.setTaskList(list);
        return gc;
    }

    private static Map<String, Object> created(int code) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("httpCode", code);
        result.put("body", Map.of("html_url", "https://github.com/acme/repo/issues/7#issuecomment-1", "id", 1));
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> taskData(ArgumentCaptor<Map<String, Object>> data) {
        return (Map<String, Object>) data.getValue().get("task");
    }

    @Nested
    class ApprovedRunsOnce {

        @Test
        void editedResult_isSentExactlyAsApproved_once() throws Exception {
            var task = new TaskItem("Reply to issue #7", "draft a reply", 0);
            var gc = awaitingGc(task, "agent draft");
            gc.getTaskList().approveTaskWithEditedResult(task.id(), "Edited by the reviewer.", new ReviewerEdit("alice", "now", "sha", 11));
            when(executor.execute(any(), any(), anyMap(), anyString())).thenReturn(created(201));

            assertEquals(1, actions.recordApprovals(gc, phase(null), 0, Set.of(task.id()), "alice"));
            actions.runOwed(gc, store, () -> false);
            actions.runOwed(gc, store, () -> false); // a second leg finds nothing owed

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
            verify(executor, times(1)).execute(same(call), any(), data.capture(), eq("https://api.github.com"));
            assertEquals("Edited by the reviewer.", taskData(data).get("result"));
            assertEquals(true, taskData(data).get("edited"));
            assertEquals("alice", taskData(data).get("approvedBy"));
            ApprovedActionRecord record = gc.getTaskList().getApprovedActions().get(task.id());
            assertEquals(ApprovedActionStatus.SUCCEEDED, record.status());
            assertEquals(201, record.httpCode());
            assertEquals("https://github.com/acme/repo/issues/7#issuecomment-1", record.responseUrl());
            assertNotNull(record.startedAt());
            assertNotNull(record.finishedAt());
            assertEquals(TaskStatus.COMPLETED, gc.getTaskList().findById(task.id()).status());
            assertEquals(1.0, meters.counter(GroupApprovedResultActions.METRIC, "outcome", "succeeded").count());
        }

        @Test
        void theClaimIsPersistedBeforeTheRequestIsSent() throws Exception {
            var task = new TaskItem("Post", "p", 0);
            var gc = awaitingGc(task, "text");
            gc.getTaskList().approveTask(task.id());
            when(executor.execute(any(), any(), anyMap(), anyString())).thenAnswer(inv -> {
                assertEquals(ApprovedActionStatus.RUNNING, gc.getTaskList().getApprovedActions().get(task.id()).status(),
                        "the request is sent only after it was claimed");
                return created(200);
            });
            actions.recordApprovals(gc, phase(null), 0, Set.of(task.id()), "alice");

            actions.runOwed(gc, store, () -> false);

            InOrder order = inOrder(store, executor);
            order.verify(store).updateIfState(gc, GroupConversationState.IN_PROGRESS);
            order.verify(executor).execute(any(), any(), anyMap(), anyString());
            order.verify(store).updateIfState(gc, GroupConversationState.IN_PROGRESS);
        }

        @Test
        void aRetriedApproval_doesNotAddASecondAction() {
            var task = new TaskItem("Post", "p", 0);
            var gc = awaitingGc(task, "text");
            gc.getTaskList().approveTask(task.id());

            assertEquals(1, actions.recordApprovals(gc, phase(null), 0, Set.of(task.id()), "alice"));
            assertEquals(0, actions.recordApprovals(gc, phase(null), 0, Set.of(task.id()), "bob"));
            assertEquals("alice", gc.getTaskList().getApprovedActions().get(task.id()).approvedBy());
        }

        @Test
        void theAuditEntryNamesTheApproverTheTargetAndTheOutcome() throws Exception {
            var task = new TaskItem("Post", "p", 0);
            var gc = awaitingGc(task, "text");
            gc.getTaskList().approveTask(task.id());
            when(executor.execute(any(), any(), anyMap(), anyString())).thenReturn(created(201));
            actions.recordApprovals(gc, phase(null), 0, Set.of(task.id()), "alice");

            actions.runOwed(gc, store, () -> false);

            var entry = ArgumentCaptor.forClass(AuditEntry.class);
            verify(ledger).submit(entry.capture());
            assertEquals(GroupApprovedResultActions.AUDIT_TASK_ID, entry.getValue().taskId());
            assertEquals("gc-1", entry.getValue().conversationId());
            assertEquals("group-1", entry.getValue().agentId());
            assertEquals("alice", entry.getValue().output().get("approvedBy"));
            assertEquals(URI + "#" + CALL, entry.getValue().output().get("target"));
            assertEquals("SUCCEEDED", entry.getValue().output().get("status"));
            assertEquals("group:group-1", entry.getValue().output().get("runAs"));
            assertFalse(entry.getValue().output().containsValue("text"), "the result is audited by digest, not verbatim");
        }
    }

    @Nested
    class NothingRuns {

        @Test
        void aRejectedTask_owesNothing() throws Exception {
            var task = new TaskItem("Post", "p", 0);
            var gc = awaitingGc(task, "text");
            gc.getTaskList().rejectTask(task.id(), "no");

            assertEquals(0, actions.recordApprovals(gc, phase(null), 0, Set.of(task.id()), "alice"));
            actions.runOwed(gc, store, () -> false);

            verifyNoInteractions(executor);
            assertTrue(gc.getTaskList().getApprovedActions().isEmpty());
        }

        @Test
        void aTaskResetForRetry_owesNothing() throws Exception {
            var task = new TaskItem("Post", "p", 0);
            var gc = awaitingGc(task, "text");
            gc.getTaskList().resetFromAnyToAssigned(task.id(), "try again");

            assertEquals(0, actions.recordApprovals(gc, phase(null), 0, Set.of(task.id()), "alice"));
            verifyNoInteractions(executor);
        }

        @Test
        void aTimeoutAutoApproval_isRecordedAndSkipped_notSent() throws Exception {
            var task = new TaskItem("Post", "p", 0);
            var gc = awaitingGc(task, "text");
            gc.getTaskList().approveTask(task.id());
            actions.recordApprovals(gc, phase(null), 0, Set.of(task.id()), "system:timeout");

            actions.runOwed(gc, store, () -> false);

            verifyNoInteractions(executor);
            assertEquals(ApprovedActionStatus.SKIPPED, gc.getTaskList().getApprovedActions().get(task.id()).status());
            assertEquals(TaskStatus.COMPLETED, gc.getTaskList().findById(task.id()).status(), "a skip is not a failure");
        }

        @Test
        void aCancelledDiscussion_skipsWhatItOwes() throws Exception {
            var task = new TaskItem("Post", "p", 0);
            var gc = awaitingGc(task, "text");
            gc.getTaskList().approveTask(task.id());
            actions.recordApprovals(gc, phase(null), 0, Set.of(task.id()), "alice");

            actions.runOwed(gc, store, () -> true);

            verifyNoInteractions(executor);
            assertEquals(ApprovedActionStatus.SKIPPED, gc.getTaskList().getApprovedActions().get(task.id()).status());
        }

        @Test
        void aPhaseWithoutOnApproved_recordsNothing() {
            var task = new TaskItem("Post", "p", 0);
            var gc = awaitingGc(task, "text");
            gc.getTaskList().approveTask(task.id());
            var plain = new DiscussionPhase("Draft", PhaseType.EXECUTE, "ALL", TurnOrder.SEQUENTIAL, ContextScope.FULL, false, null, 1, true);

            assertEquals(0, actions.recordApprovals(gc, plain, 0, Set.of(task.id()), "alice"));
        }
    }

    @Nested
    class CrashSafety {

        @Test
        void aRecordLeftRunningByACrash_isNeverSentAgain_andFailsTheTask() throws Exception {
            var task = new TaskItem("Post", "p", 0);
            var gc = awaitingGc(task, "text");
            gc.getTaskList().approveTask(task.id());
            actions.recordApprovals(gc, phase(null), 0, Set.of(task.id()), "alice");
            // what the store holds after a pod died between the claim and the outcome
            var record = gc.getTaskList().getApprovedActions().get(task.id());
            gc.getTaskList().updateApprovedAction(task.id(), record.running(Instant.now()));

            actions.runOwed(gc, store, () -> false);

            verifyNoInteractions(executor);
            assertEquals(ApprovedActionStatus.OUTCOME_UNKNOWN, gc.getTaskList().getApprovedActions().get(task.id()).status());
            assertEquals(TaskStatus.FAILED, gc.getTaskList().findById(task.id()).status(), "FAIL_TASK treats an unknown outcome as a failure");
        }

        @Test
        void aCrashBetweenApprovalAndClaim_sendsOnTheNextLeg_once() throws Exception {
            var task = new TaskItem("Post", "p", 0);
            var gc = awaitingGc(task, "text");
            gc.getTaskList().approveTask(task.id());
            actions.recordApprovals(gc, phase(null), 0, Set.of(task.id()), "alice");
            // the record was persisted PENDING with the approval; the first leg never ran
            when(executor.execute(any(), any(), anyMap(), anyString())).thenReturn(created(201));

            actions.runOwed(gc, store, () -> false);
            actions.runOwed(gc, store, () -> false);

            verify(executor, times(1)).execute(any(), any(), anyMap(), anyString());
        }

        @Test
        void aClaimThatCannotBePersisted_sendsNothing_andStaysOwed() throws Exception {
            var task = new TaskItem("Post", "p", 0);
            var gc = awaitingGc(task, "text");
            gc.getTaskList().approveTask(task.id());
            actions.recordApprovals(gc, phase(null), 0, Set.of(task.id()), "alice");
            doThrow(new IResourceStore.ResourceModifiedException("state changed")).when(store).updateIfState(any(), any());

            actions.runOwed(gc, store, () -> false);

            verifyNoInteractions(executor);
            assertEquals(ApprovedActionStatus.PENDING, gc.getTaskList().getApprovedActions().get(task.id()).status());
        }
    }

    @Nested
    class Failures {

        @Test
        void aNon2xx_failsTheTaskUnderFailTask() throws Exception {
            var task = new TaskItem("Post", "p", 0);
            var gc = awaitingGc(task, "text");
            gc.getTaskList().approveTask(task.id());
            when(executor.execute(any(), any(), anyMap(), anyString())).thenReturn(Map.of("httpCode", 422, "body", "Validation Failed"));
            actions.recordApprovals(gc, phase(ApprovedActionFailurePolicy.FAIL_TASK), 0, Set.of(task.id()), "alice");

            actions.runOwed(gc, store, () -> false);

            var record = gc.getTaskList().getApprovedActions().get(task.id());
            assertEquals(ApprovedActionStatus.FAILED, record.status());
            assertEquals(422, record.httpCode());
            assertTrue(record.summary().contains("422"));
            var failed = gc.getTaskList().findById(task.id());
            assertEquals(TaskStatus.FAILED, failed.status());
            assertTrue(failed.verificationNote().contains("Approved-result action FAILED"));
        }

        @Test
        void aRefusedCall_keepsTheTaskUnderKeepCompleted() throws Exception {
            var task = new TaskItem("Post", "p", 0);
            var gc = awaitingGc(task, "text");
            gc.getTaskList().approveTask(task.id());
            when(executor.execute(any(), any(), anyMap(), anyString())).thenThrow(new LifecycleException("connect timed out"));
            actions.recordApprovals(gc, phase(ApprovedActionFailurePolicy.KEEP_COMPLETED), 0, Set.of(task.id()), "alice");

            actions.runOwed(gc, store, () -> false);

            assertEquals(ApprovedActionStatus.FAILED, gc.getTaskList().getApprovedActions().get(task.id()).status());
            assertEquals(TaskStatus.COMPLETED, gc.getTaskList().findById(task.id()).status());
            assertEquals(1.0, meters.counter(GroupApprovedResultActions.METRIC, "outcome", "failed").count());
        }

        @Test
        void aGrantNarrowedAfterTheSave_refusesTheCallAtRunTime() throws Exception {
            var task = new TaskItem("Post", "p", 0);
            var gc = awaitingGc(task, "text");
            gc.getTaskList().approveTask(task.id());
            when(validation.referenceProblems(anyString(), any())).thenReturn(List.of("references vault secret x, which is not granted"));
            actions.recordApprovals(gc, phase(null), 0, Set.of(task.id()), "alice");

            actions.runOwed(gc, store, () -> false);

            verifyNoInteractions(executor);
            var record = gc.getTaskList().getApprovedActions().get(task.id());
            assertEquals(ApprovedActionStatus.FAILED, record.status());
            assertTrue(record.summary().startsWith("Refused: "));
        }
    }

    @Nested
    class ResultIsData {

        @Test
        void aResultWithTemplateSyntax_reachesTheCallVerbatim() throws Exception {
            String hostile = "Ship it {task.id} {#eval 'x'/} {json:serialize(group)} ${vault:github-token} {{x}}";
            var task = new TaskItem("Post", "p", 0);
            var gc = awaitingGc(task, hostile);
            gc.getTaskList().approveTask(task.id());
            when(executor.execute(any(), any(), anyMap(), anyString())).thenReturn(created(201));
            actions.recordApprovals(gc, phase(null), 0, Set.of(task.id()), "alice");

            actions.runOwed(gc, store, () -> false);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<Map<String, Object>> data = ArgumentCaptor.forClass(Map.class);
            verify(executor).execute(any(), any(), data.capture(), anyString());
            assertEquals(hostile, taskData(data).get("result"), "a value in the template data, never rendered before the call");
            assertEquals(Set.of("task", "group", "action"), data.getValue().keySet());
        }

        @Test
        void templateData_carriesTheTaskTheGroupAndAStableIdempotencyKey() {
            var task = new TaskItem("Post", "describe", 0);
            var gc = awaitingGc(task, "text");
            gc.getTaskList().approveTask(task.id());
            actions.recordApprovals(gc, phase(null), 0, Set.of(task.id()), "alice");
            var record = gc.getTaskList().getApprovedActions().get(task.id());

            var data = GroupApprovedResultActions.templateData(gc, gc.getTaskList().findById(task.id()), record);

            assertEquals(Map.of("id", "group-1", "conversationId", "gc-1"), data.get("group"));
            assertEquals(GroupApprovedResultActions.idempotencyKey("gc-1", task.id()), ((Map<?, ?>) data.get("action")).get("idempotencyKey"));
            assertEquals(32, record.idempotencyKey().length());
            assertNotEquals(GroupApprovedResultActions.idempotencyKey("gc-2", task.id()), record.idempotencyKey());
        }
    }

    @Test
    void responseUrl_readsHtmlUrlThenUrl_httpOnly() {
        assertEquals("https://x/1", GroupApprovedResultActions.responseUrl(Map.of("html_url", "https://x/1", "url", "https://api/1")));
        assertEquals("https://api/1", GroupApprovedResultActions.responseUrl(Map.of("url", "https://api/1")));
        assertNull(GroupApprovedResultActions.responseUrl(Map.of("url", "javascript:alert(1)")));
        assertNull(GroupApprovedResultActions.responseUrl("plain text"));
    }

    @Test
    void actionOf_splitsTheTargetOnTheLastHash() {
        var action = GroupApprovedResultActions.actionOf(URI + "#" + CALL);
        assertEquals(URI, action.uri());
        assertEquals(CALL, action.call());
    }
}
