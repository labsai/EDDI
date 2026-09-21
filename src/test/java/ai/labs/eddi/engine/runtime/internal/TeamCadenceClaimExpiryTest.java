/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.configs.groups.IGroupConversationStore;
import ai.labs.eddi.configs.groups.IGroupWorkspaceStore;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.GroupConversationState;
import ai.labs.eddi.configs.groups.model.GroupWorkspace;
import ai.labs.eddi.engine.internal.GroupConversationService;
import ai.labs.eddi.engine.lifecycle.model.ControlSignal;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Expiry of a standing team's cadence claim.
 * <p>
 * A claim is released by writeback when its discussion reaches a terminal
 * state, and {@code AWAITING_APPROVAL} / {@code AWAITING_HUMAN_INPUT} is not
 * one. The default group HITL timeout policy is {@code WAIT_INDEFINITELY}, so a
 * single unapproved cadence discussion used to hold the claim forever: every
 * later fire for that group was skipped as "still running", and the tasks that
 * run pulled stayed IN_PROGRESS on the backlog. Nothing anywhere reaped it —
 * out of character for a subsystem whose task-force half carries an explicit
 * no-progress guard precisely to guarantee termination.
 *
 * @author ginccc
 */
@DisplayName("TeamCadenceService — cadence claim expiry")
class TeamCadenceClaimExpiryTest {

    private static final String GROUP_ID = "group-1";
    private static final String GC_ID = "gc-1";

    private IGroupWorkspaceStore workspaceStore;
    private IGroupConversationStore conversationStore;
    private GroupConversationService groupConversationService;

    @BeforeEach
    void setUp() {
        workspaceStore = mock(IGroupWorkspaceStore.class);
        conversationStore = mock(IGroupConversationStore.class);
        groupConversationService = mock(GroupConversationService.class);
    }

    private TeamCadenceService serviceWithTtl(Duration ttl) {
        var service = new TeamCadenceService(workspaceStore, conversationStore, groupConversationService,
                mock(ITemplatingEngine.class), new SimpleMeterRegistry(), ttl);
        service.initMetrics();
        return service;
    }

    private GroupWorkspace claimedWorkspace(Instant claimedAt) {
        var workspace = new GroupWorkspace();
        workspace.setId("ws-1");
        workspace.setGroupId(GROUP_ID);
        workspace.setRunningDiscussionId(GC_ID);
        workspace.setClaimedAt(claimedAt);
        return workspace;
    }

    private void discussionInState(GroupConversationState state) throws Exception {
        var gc = new GroupConversation();
        gc.setId(GC_ID);
        gc.setGroupId(GROUP_ID);
        gc.setState(state);
        when(conversationStore.read(GC_ID)).thenReturn(gc);
        when(workspaceStore.casRunningDiscussion(any(), eq(GC_ID))).thenReturn(true);
    }

    @Test
    @DisplayName("an approval pause held past the TTL is reclaimed, not held forever")
    void stalePauseIsReclaimed() throws Exception {
        var workspace = claimedWorkspace(Instant.now().minus(Duration.ofHours(48)));
        discussionInState(GroupConversationState.AWAITING_APPROVAL);

        boolean idle = serviceWithTtl(Duration.ofHours(24)).reconcile(workspace);

        assertTrue(idle, "the workspace must be idle again so the team's cadences can fire");
        assertEquals(GroupWorkspace.NO_RUNNING_DISCUSSION, workspace.getRunningDiscussionId());
        verify(groupConversationService).cancelDiscussion(GC_ID, ControlSignal.CANCEL_GRACEFUL);
    }

    @Test
    @DisplayName("a human-input pause held past the TTL is reclaimed too")
    void staleHumanInputPauseIsReclaimed() throws Exception {
        var workspace = claimedWorkspace(Instant.now().minus(Duration.ofHours(48)));
        discussionInState(GroupConversationState.AWAITING_HUMAN_INPUT);

        assertTrue(serviceWithTtl(Duration.ofHours(24)).reconcile(workspace));
        verify(groupConversationService).cancelDiscussion(GC_ID, ControlSignal.CANCEL_GRACEFUL);
    }

    @Test
    @DisplayName("a pause still inside the TTL keeps its claim — an approval must land on the discussion it belongs to")
    void freshPauseKeepsItsClaim() throws Exception {
        var workspace = claimedWorkspace(Instant.now().minus(Duration.ofHours(1)));
        discussionInState(GroupConversationState.AWAITING_APPROVAL);

        boolean idle = serviceWithTtl(Duration.ofHours(24)).reconcile(workspace);

        assertFalse(idle, "an approval that arrives next business morning must not find a reclaimed corpse");
        assertEquals(GC_ID, workspace.getRunningDiscussionId());
        verify(groupConversationService, never()).cancelDiscussion(anyString(), any());
    }

    @Test
    @DisplayName("a genuinely running discussion keeps its claim even past the TTL window's start")
    void runningDiscussionInsideTtlKeepsItsClaim() throws Exception {
        var workspace = claimedWorkspace(Instant.now().minus(Duration.ofMinutes(5)));
        discussionInState(GroupConversationState.IN_PROGRESS);

        assertFalse(serviceWithTtl(Duration.ofHours(24)).reconcile(workspace));
        verify(groupConversationService, never()).cancelDiscussion(anyString(), any());
    }

    @Test
    @DisplayName("a workspace with no claim stamp is never reclaimed on a guess")
    void missingStampIsNotReclaimed() throws Exception {
        var workspace = claimedWorkspace(null);
        discussionInState(GroupConversationState.AWAITING_APPROVAL);

        assertFalse(serviceWithTtl(Duration.ofHours(24)).reconcile(workspace),
                "documents written before the stamp existed get one on their next claim, not a guessed expiry");
        verify(groupConversationService, never()).cancelDiscussion(anyString(), any());
    }

    @Test
    @DisplayName("a non-positive TTL disables reclaiming entirely")
    void nonPositiveTtlDisablesReclaiming() throws Exception {
        var workspace = claimedWorkspace(Instant.now().minus(Duration.ofDays(30)));
        discussionInState(GroupConversationState.AWAITING_APPROVAL);

        assertFalse(serviceWithTtl(Duration.ZERO).reconcile(workspace));
        verify(groupConversationService, never()).cancelDiscussion(anyString(), any());
    }

    @Test
    @DisplayName("an idle workspace is idle regardless of any stale stamp")
    void idleWorkspaceStaysIdle() {
        var workspace = new GroupWorkspace();
        workspace.setGroupId(GROUP_ID);
        workspace.setRunningDiscussionId(GroupWorkspace.NO_RUNNING_DISCUSSION);
        workspace.setClaimedAt(Instant.now().minus(Duration.ofDays(30)));

        assertTrue(serviceWithTtl(Duration.ofHours(24)).reconcile(workspace));
    }

    @Test
    @DisplayName("a completed discussion still settles normally — expiry does not shadow the ordinary path")
    void completedStillSettlesNormally() throws Exception {
        var workspace = claimedWorkspace(Instant.now().minus(Duration.ofHours(48)));
        discussionInState(GroupConversationState.COMPLETED);

        assertTrue(serviceWithTtl(Duration.ofHours(24)).reconcile(workspace));
        verify(groupConversationService, never()).cancelDiscussion(anyString(), any());
    }

    @Test
    @DisplayName("the claim stamp is cleared when the claim is released")
    void releasingTheClaimClearsTheStamp() throws Exception {
        var workspace = claimedWorkspace(Instant.now().minus(Duration.ofHours(48)));
        discussionInState(GroupConversationState.AWAITING_APPROVAL);

        serviceWithTtl(Duration.ofHours(24)).reconcile(workspace);

        assertEquals(null, workspace.getClaimedAt(),
                "a stale stamp on an idle workspace would make the next claim look instantly expired");
    }
}
