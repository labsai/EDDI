/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.rest;

import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.IGroupWorkspaceStore;
import ai.labs.eddi.configs.groups.IRestGroupOutcomes.OutcomeRequest;
import ai.labs.eddi.configs.groups.IRestGroupWorkspace.BacklogTaskRequest;
import ai.labs.eddi.configs.groups.IRestGroupWorkspace.BetReviewRequest;
import ai.labs.eddi.configs.groups.model.GroupWorkspace;
import ai.labs.eddi.configs.groups.model.SharedTaskList.Bet;
import ai.labs.eddi.configs.groups.model.SharedTaskList.TaskItem;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.groups.outcomes.OutcomeEvent;
import ai.labs.eddi.engine.groups.outcomes.OutcomeEventService;
import ai.labs.eddi.engine.groups.outcomes.OutcomeEventService.Result;
import ai.labs.eddi.engine.groups.outcomes.OutcomeEventService.Status;
import ai.labs.eddi.engine.schedule.IScheduleStore;
import ai.labs.eddi.engine.runtime.internal.TeamCadenceService;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.security.Principal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@DisplayName("Team learning loop — REST surfaces (bets and outcomes)")
class RestGroupLearningLoopTest {

    private static final String GROUP = "group-1";

    private IGroupWorkspaceStore workspaceStore;
    private IAgentGroupStore groupStore;
    private ResourceAccessGuard guard;
    private RestGroupWorkspace workspaceRest;
    private RestGroupOutcomes outcomesRest;
    private OutcomeEventService outcomes;
    private GroupWorkspace workspace;

    @BeforeEach
    void setUp() throws Exception {
        workspaceStore = mock(IGroupWorkspaceStore.class);
        groupStore = mock(IAgentGroupStore.class);
        guard = mock(ResourceAccessGuard.class);
        outcomes = mock(OutcomeEventService.class);
        var identity = mock(SecurityIdentity.class);
        var principal = mock(Principal.class);
        lenient().when(principal.getName()).thenReturn("hooks@example.com");
        lenient().when(identity.getPrincipal()).thenReturn(principal);
        workspaceRest = new RestGroupWorkspace(workspaceStore, groupStore, mock(IScheduleStore.class), mock(TeamCadenceService.class), identity,
                guard);
        outcomesRest = new RestGroupOutcomes(outcomes, groupStore, identity, guard);

        var resourceId = mock(IResourceStore.IResourceId.class);
        lenient().when(groupStore.getCurrentResourceId(GROUP)).thenReturn(resourceId);
        workspace = new GroupWorkspace();
        workspace.setGroupId(GROUP);
        lenient().when(workspaceStore.readOrCreate(GROUP)).thenReturn(workspace);
        lenient().when(workspaceStore.find(GROUP)).thenReturn(workspace);
        lenient().when(workspaceStore.casRevision(workspace)).thenReturn(true);
    }

    // === bets ===

    @Test
    void aBacklogTaskIsABetWhenItCarriesTheFields() {
        var response = workspaceRest.addBacklogTask(GROUP,
                new BacklogTaskRequest("Post it", "d", 2, "founders reply", "replies", ">= 5", "2026-12-01"));

        assertEquals(201, response.getStatus());
        Bet bet = workspace.getBacklog().getTasks().get(0).bet();
        assertEquals("founders reply", bet.hypothesis());
        assertEquals("replies", bet.metric());
        assertEquals(Instant.parse("2026-12-01T00:00:00Z"), bet.reviewDate());
    }

    @Test
    void anOrdinaryRequestStaysOrdinary() {
        assertEquals(201, workspaceRest.addBacklogTask(GROUP, new BacklogTaskRequest("Plain", "d", 1)).getStatus());
        assertNull(workspace.getBacklog().getTasks().get(0).bet());
    }

    @Test
    void aHalfSpecifiedBetIs400AndStoresNothing() {
        var response = workspaceRest.addBacklogTask(GROUP, new BacklogTaskRequest("Half", "d", 1, "only a hypothesis", null, null, null));

        assertEquals(400, response.getStatus());
        assertTrue(response.getEntity().toString().contains("reviewDate"));
        assertEquals(0, workspace.getBacklog().size());
    }

    private TaskItem bet(String subject, String reviewDate) {
        return workspace.getBacklog().addTask(new TaskItem(subject, "d", 0, Bet.of("h", "m", null, reviewDate)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void dueReviewsListsOnlyBetsWhoseDateHasArrived() {
        bet("due", "2020-01-01");
        bet("future", "2999-01-01");
        workspace.getBacklog().addTask(new TaskItem("plain", "d", 0));

        var response = workspaceRest.readDueBetReviews(GROUP, null);

        assertEquals(200, response.getStatus());
        assertEquals(List.of("due"), ((List<TaskItem>) response.getEntity()).stream().map(TaskItem::subject).toList());
        var later = workspaceRest.readDueBetReviews(GROUP, "2999-06-01");
        assertEquals(2, ((List<TaskItem>) later.getEntity()).size(), "asOf asks what will be due by then");
        assertEquals(400, workspaceRest.readDueBetReviews(GROUP, "soon").getStatus());
    }

    @Test
    void reviewingABetClosesItAndReschedulingReopensIt() {
        TaskItem t = bet("due", "2020-01-01");

        var closed = workspaceRest.reviewBet(GROUP, t.id(), new BetReviewRequest(null));
        assertEquals(200, closed.getStatus());
        assertNotNull(workspace.getBacklog().findById(t.id()).bet().reviewedAt());
        assertTrue(workspace.getBacklog().findDueReviews(Instant.now()).isEmpty());

        var reopened = workspaceRest.reviewBet(GROUP, t.id(), new BetReviewRequest("2999-01-01"));
        assertEquals(200, reopened.getStatus());
        assertNull(workspace.getBacklog().findById(t.id()).bet().reviewedAt());
        assertEquals(Instant.parse("2999-01-01T00:00:00Z"), workspace.getBacklog().findById(t.id()).bet().reviewDate());
    }

    @Test
    void reviewingRefusesWhatIsNotABetOrNotThere() {
        TaskItem plain = workspace.getBacklog().addTask(new TaskItem("plain", "d", 0));
        assertEquals(409, workspaceRest.reviewBet(GROUP, plain.id(), new BetReviewRequest(null)).getStatus());
        assertEquals(404, workspaceRest.reviewBet(GROUP, "nope", new BetReviewRequest(null)).getStatus());
        assertEquals(400, workspaceRest.reviewBet(GROUP, plain.id(), new BetReviewRequest("whenever")).getStatus());
    }

    @Test
    @DisplayName("the new bet endpoints let the access guard's denial through as 403")
    void betEndpointsPropagateForbidden() throws Exception {
        doThrow(new ForbiddenException("denied")).when(guard).requireAccess(anyString(), any(), anyString());
        assertThrows(ForbiddenException.class, () -> workspaceRest.readDueBetReviews(GROUP, null));
        assertThrows(ForbiddenException.class, () -> workspaceRest.reviewBet(GROUP, "t", new BetReviewRequest(null)));
    }

    // === outcomes ===

    private static OutcomeRequest req() {
        return new OutcomeRequest("TASK", "t1", "reply_received", null, null, null, null, "crm", null, "evt-1");
    }

    private ArgumentCaptor<OutcomeEventService.Request> captureRecord(Status status, OutcomeEvent event) {
        when(outcomes.record(anyString(), any(), anyString())).thenReturn(new Result(status, "msg", event));
        var captor = ArgumentCaptor.forClass(OutcomeEventService.Request.class);
        return captor;
    }

    private static OutcomeEvent event() {
        return new OutcomeEvent("id", GROUP, OutcomeEvent.SubjectType.TASK, "t1", "reply_received", null, null, null, null, "crm",
                "hooks@example.com",
                Instant.now(), Instant.now(), "evt-1");
    }

    @Test
    void theStatusesMapToTheirHttpCodes() {
        Map<Status, Integer> expected = Map.of(Status.RECORDED, 201, Status.DUPLICATE, 200, Status.DISABLED, 409, Status.SUBJECT_FULL, 409,
                Status.NOT_FOUND, 404, Status.GROUP_NOT_FOUND, 404, Status.INVALID, 400);
        for (var e : expected.entrySet()) {
            captureRecord(e.getKey(), event());
            assertEquals(e.getValue(), outcomesRest.record(GROUP, req()).getStatus(), e.getKey().name());
        }
    }

    @Test
    void theRecordedByIsTheAuthenticatedPrincipalAndTheBodyIsPassedThrough() {
        var captor = captureRecord(Status.RECORDED, event());

        outcomesRest.record(GROUP, req());

        ArgumentCaptor<String> by = ArgumentCaptor.forClass(String.class);
        verify(outcomes).record(anyString(), captor.capture(), by.capture());
        assertEquals("hooks@example.com", by.getValue());
        assertEquals("evt-1", captor.getValue().idempotencyKey());
        assertEquals("t1", captor.getValue().subjectId());
    }

    @Test
    void writingNeedsEditAccessAndAnAbsentBodyIs400() throws Exception {
        assertEquals(400, outcomesRest.record(GROUP, null).getStatus());
        verify(guard).requireAccess(GROUP, AccessLevel.EDIT, "group");
        verifyNoInteractions(outcomes);
    }

    @Test
    void anUnknownGroupIs404BeforeAnyOutcomeWork() throws Exception {
        when(groupStore.getCurrentResourceId("missing")).thenReturn(null);
        assertEquals(404, outcomesRest.record("missing", req()).getStatus());
        assertEquals(404, outcomesRest.list("missing", null, null).getStatus());
        verifyNoInteractions(outcomes);
    }

    @Test
    void forbiddenIsNotA500() throws Exception {
        doThrow(new ForbiddenException("denied")).when(guard).requireAccess(anyString(), any(), anyString());
        assertThrows(ForbiddenException.class, () -> outcomesRest.record(GROUP, req()));
        assertThrows(ForbiddenException.class, () -> outcomesRest.list(GROUP, null, null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void listReadsOneSubjectOrTheRecentOnesWithAClampedLimit() {
        when(outcomes.forSubject(GROUP, "t1")).thenReturn(List.of(event()));
        when(outcomes.recent(anyString(), org.mockito.ArgumentMatchers.anyInt())).thenReturn(List.of(event(), event()));

        assertEquals(1, ((List<OutcomeEvent>) outcomesRest.list(GROUP, " t1 ", null).getEntity()).size());
        assertEquals(1, ((List<OutcomeEvent>) outcomesRest.list(GROUP, null, 1).getEntity()).size(), "limit trims");
        outcomesRest.list(GROUP, null, 99_999);
        verify(outcomes).recent(GROUP, RestGroupOutcomes.MAX_LIMIT);
        Response viewer = outcomesRest.list(GROUP, null, null);
        assertEquals(200, viewer.getStatus());
        verify(guard, org.mockito.Mockito.atLeastOnce()).requireAccess(GROUP, AccessLevel.VIEW, "group");
    }
}
