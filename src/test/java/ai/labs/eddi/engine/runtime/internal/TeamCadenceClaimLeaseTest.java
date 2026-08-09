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
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.internal.GroupConversationService;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The cadence claim protocol had no expiry.
 * <p>
 * Its Javadoc promised that "a pod crash mid-discussion loses nothing, because
 * the next fire finds the terminal state and reconciles it" — but on a pod
 * crash nothing moves the discussion to a terminal state, so {@code reconcile}
 * answered "still running" forever and the cadence was wedged until a human
 * cancelled the discussion by hand.
 */
@DisplayName("TeamCadenceService — claim lease")
class TeamCadenceClaimLeaseTest {

    private IGroupWorkspaceStore workspaceStore;
    private IGroupConversationStore conversationStore;
    private GroupConversationService groupConversationService;
    private TeamCadenceService service;

    @BeforeEach
    void setUp() {
        workspaceStore = mock(IGroupWorkspaceStore.class);
        conversationStore = mock(IGroupConversationStore.class);
        groupConversationService = mock(GroupConversationService.class);
        service = new TeamCadenceService(workspaceStore, conversationStore, groupConversationService, mock(ITemplatingEngine.class),
                new SimpleMeterRegistry(), "PT6H");
        service.initMetrics();
    }

    private GroupWorkspace claimedWorkspace() {
        var workspace = new GroupWorkspace();
        workspace.setGroupId("group-1");
        workspace.setId("ws-1");
        workspace.setRunningDiscussionId("gc-1");
        workspace.setPulledTaskIds(List.of());
        return workspace;
    }

    private GroupConversation discussion(GroupConversationState state, Instant lastModified) {
        var gc = new GroupConversation();
        gc.setId("gc-1");
        gc.setGroupId("group-1");
        gc.setState(state);
        gc.setLastModified(lastModified);
        return gc;
    }

    @Nested
    @DisplayName("an abandoned run")
    class AbandonedRun {

        @Test
        @DisplayName("an IN_PROGRESS discussion that stopped advancing past the lease is reclaimed")
        void staleInProgressIsReclaimed() throws Exception {
            when(conversationStore.read("gc-1")).thenReturn(discussion(GroupConversationState.IN_PROGRESS, Instant.now().minus(7, ChronoUnit.HOURS)));
            when(workspaceStore.casRunningDiscussion(any(), anyString())).thenReturn(true);

            var workspace = claimedWorkspace();
            assertTrue(service.reconcile(workspace), "a wedged cadence must be reclaimable without human intervention");
            assertEquals(GroupWorkspace.NO_RUNNING_DISCUSSION, workspace.getRunningDiscussionId());
        }

        /**
         * A zombie loop that somehow survives must not keep spending the cadence's
         * budget on work whose outcomes nobody will collect.
         */
        @Test
        @DisplayName("the abandoned discussion is cancelled before the claim is released")
        void abandonedDiscussionIsCancelled() throws Exception {
            when(conversationStore.read("gc-1")).thenReturn(discussion(GroupConversationState.IN_PROGRESS, Instant.now().minus(7, ChronoUnit.HOURS)));
            when(workspaceStore.casRunningDiscussion(any(), anyString())).thenReturn(true);

            service.reconcile(claimedWorkspace());

            verify(groupConversationService).cancelDiscussion("gc-1", null);
        }

        /**
         * A missing progress stamp is not evidence of activity. Reading it as "active"
         * would reinstate the deadlock for any record whose lastModified was never
         * written.
         */
        @Test
        @DisplayName("a discussion with no progress timestamp at all is reclaimed, not treated as active")
        void nullTimestampsAreReclaimed() throws Exception {
            var gc = discussion(GroupConversationState.IN_PROGRESS, null);
            gc.setCreated(null);
            when(conversationStore.read("gc-1")).thenReturn(gc);
            when(workspaceStore.casRunningDiscussion(any(), anyString())).thenReturn(true);

            assertTrue(service.reconcile(claimedWorkspace()), "a null progress stamp must not wedge the cadence forever");
        }

        @Test
        @DisplayName("a null lastModified falls back to the creation stamp before expiring")
        void nullLastModifiedFallsBackToCreated() throws Exception {
            var recentlyCreated = discussion(GroupConversationState.IN_PROGRESS, null);
            recentlyCreated.setCreated(Instant.now().minus(3, ChronoUnit.MINUTES));
            when(conversationStore.read("gc-1")).thenReturn(recentlyCreated);

            assertFalse(service.reconcile(claimedWorkspace()),
                    "a discussion created minutes ago has simply not persisted progress yet");
            verify(groupConversationService, never()).cancelDiscussion(anyString(), any());
        }

        @Test
        @DisplayName("CREATED counts too — a crash between the claim and the first turn")
        void staleCreatedIsReclaimed() throws Exception {
            when(conversationStore.read("gc-1")).thenReturn(discussion(GroupConversationState.CREATED, Instant.now().minus(7, ChronoUnit.HOURS)));
            when(workspaceStore.casRunningDiscussion(any(), anyString())).thenReturn(true);

            assertTrue(service.reconcile(claimedWorkspace()));
        }
    }

    @Nested
    @DisplayName("a run that is still alive")
    class StillAlive {

        /**
         * The lease is measured on the discussion's own progress heartbeat, not on
         * claim age. A claim-age lease could not tell a dead pod from a healthy
         * long-running discussion, and reclaiming a live one would orphan its outcomes
         * AND double-schedule its tasks.
         */
        @Test
        @DisplayName("a recently-advanced discussion is left alone however old the claim is")
        void recentlyAdvancedIsNotReclaimed() throws Exception {
            when(conversationStore.read("gc-1"))
                    .thenReturn(discussion(GroupConversationState.IN_PROGRESS, Instant.now().minus(2, ChronoUnit.MINUTES)));

            assertFalse(service.reconcile(claimedWorkspace()), "a discussion that is still advancing must not be reclaimed");
            verify(groupConversationService, never()).cancelDiscussion(anyString(), any());
            verify(workspaceStore, never()).casRunningDiscussion(any(), anyString());
        }

        /**
         * A discussion may legitimately wait on a human for days, and every surface
         * that resolves one works cross-pod, so a paused discussion on a dead pod still
         * progresses. Expiring it would destroy a live pending approval.
         */
        @Test
        @DisplayName("AWAITING_APPROVAL never expires, however stale")
        void awaitingApprovalNeverExpires() throws Exception {
            when(conversationStore.read("gc-1"))
                    .thenReturn(discussion(GroupConversationState.AWAITING_APPROVAL, Instant.now().minus(30, ChronoUnit.DAYS)));

            assertFalse(service.reconcile(claimedWorkspace()));
            verify(groupConversationService, never()).cancelDiscussion(anyString(), any());
        }

        @Test
        @DisplayName("AWAITING_HUMAN_INPUT never expires either")
        void awaitingHumanInputNeverExpires() throws Exception {
            when(conversationStore.read("gc-1"))
                    .thenReturn(discussion(GroupConversationState.AWAITING_HUMAN_INPUT, Instant.now().minus(30, ChronoUnit.DAYS)));

            assertFalse(service.reconcile(claimedWorkspace()));
            verify(groupConversationService, never()).cancelDiscussion(anyString(), any());
        }
    }

    @Nested
    @DisplayName("read failures")
    class ReadFailures {

        /**
         * A transient read failure is not proof of absence. This used to catch every
         * exception and release the claim, so a network blip while the discussion was
         * genuinely running returned its tasks to PENDING and let the next fire start a
         * SECOND discussion on the same backlog.
         */
        @Test
        @DisplayName("a transient read error keeps the claim rather than double-scheduling the work")
        void transientErrorKeepsClaim() throws Exception {
            when(conversationStore.read("gc-1")).thenThrow(new IResourceStore.ResourceStoreException("connection reset", null));

            var workspace = claimedWorkspace();
            assertFalse(service.reconcile(workspace));
            assertEquals("gc-1", workspace.getRunningDiscussionId(), "the claim must survive a read failure");
            verify(workspaceStore, never()).casRunningDiscussion(any(), anyString());
        }

        /** A provably deleted discussion is a different matter: release the claim. */
        @Test
        @DisplayName("a provably deleted discussion releases the claim")
        void deletedReleasesClaim() throws Exception {
            when(conversationStore.read("gc-1")).thenThrow(new IResourceStore.ResourceNotFoundException("gone"));
            when(workspaceStore.casRunningDiscussion(any(), anyString())).thenReturn(true);

            var workspace = claimedWorkspace();
            assertTrue(service.reconcile(workspace));
            assertEquals(GroupWorkspace.NO_RUNNING_DISCUSSION, workspace.getRunningDiscussionId());
        }
    }

    @Nested
    @DisplayName("lease configuration")
    class LeaseConfiguration {

        @Test
        @DisplayName("an unparseable or non-positive lease falls back to the default instead of failing startup")
        void invalidLeaseFallsBack() throws Exception {
            for (String invalid : new String[]{"not-a-duration", "PT0S", "-PT1H"}) {
                var fallback = new TeamCadenceService(workspaceStore, conversationStore, groupConversationService,
                        mock(ITemplatingEngine.class), new SimpleMeterRegistry(), invalid);
                fallback.initMetrics();

                when(conversationStore.read("gc-1"))
                        .thenReturn(discussion(GroupConversationState.IN_PROGRESS, Instant.now().minus(2, ChronoUnit.MINUTES)));

                assertFalse(fallback.reconcile(claimedWorkspace()),
                        "lease '" + invalid + "' must fall back to the 6h default, not to an instant expiry");
            }
        }
    }
}
