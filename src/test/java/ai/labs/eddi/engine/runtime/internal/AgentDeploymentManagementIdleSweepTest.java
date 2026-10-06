/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.configs.migration.WorkspaceAccessIndexMigration;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.deployment.IDeploymentStore;
import ai.labs.eddi.configs.deployment.model.DeploymentInfo;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.migration.ChannelConnectorMigration;
import ai.labs.eddi.configs.migration.IMigrationManager;
import ai.labs.eddi.configs.migration.V6QuteMigration;
import ai.labs.eddi.configs.migration.V6RenameMigration;
import ai.labs.eddi.configs.rules.IRuleSetStore;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.datastore.IResourceStore.IResourceId;
import ai.labs.eddi.engine.memory.IConversationMemoryStore;
import ai.labs.eddi.engine.memory.model.ConversationActivitySummary;
import ai.labs.eddi.engine.memory.model.ConversationState;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.runtime.internal.readiness.IAgentsReadiness;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Regression pins for the idle-conversation sweep.
 * <p>
 * Two defects had to be fixed together. The age arithmetic ignored the
 * {@code Period} months component, so whole bands of ages were never reaped —
 * and that bug was masking a wrong age <em>signal</em>: the age came from the
 * AGENT document's {@code lastModifiedOn}, which is shared by every
 * conversation on that agent version. Correcting only the arithmetic would have
 * turned a mostly-inert sweep into an eager one that ENDs conversations a user
 * is actively talking in.
 */
@DisplayName("AgentDeploymentManagement — idle conversation sweep")
class AgentDeploymentManagementIdleSweepTest {

    private static final int MAX_IDLE_DAYS = 30;

    private IDeploymentStore deploymentStore;
    private IAgentFactory agentFactory;
    private IAgentStore agentStore;
    private IConversationMemoryStore conversationMemoryStore;
    private IDocumentDescriptorStore documentDescriptorStore;
    private AgentDeploymentManagement management;

    @BeforeEach
    void setUp() {
        deploymentStore = mock(IDeploymentStore.class);
        agentFactory = mock(IAgentFactory.class);
        agentStore = mock(IAgentStore.class);
        conversationMemoryStore = mock(IConversationMemoryStore.class);
        documentDescriptorStore = mock(IDocumentDescriptorStore.class);
        management = managementWithIdleLimit(MAX_IDLE_DAYS);
    }

    /** A sweep over this test's mocks, with the given idle limit. */
    private AgentDeploymentManagement managementWithIdleLimit(int maxIdleDays) {
        var runtime = mock(IRuntime.class);
        when(runtime.getScheduledExecutorService()).thenReturn(mock(ScheduledExecutorService.class));
        return new AgentDeploymentManagement(deploymentStore, agentFactory, agentStore, mock(IAgentsReadiness.class),
                conversationMemoryStore, documentDescriptorStore, mock(IMigrationManager.class), mock(V6RenameMigration.class),
                mock(V6QuteMigration.class), mock(ChannelConnectorMigration.class), mock(WorkspaceAccessIndexMigration.class), runtime,
                mock(IWorkflowStore.class), mock(IRuleSetStore.class), maxIdleDays);
    }

    @Nested
    @DisplayName("isOlderThanDays")
    class IsOlderThanDays {

        /**
         * The exact case the old {@code Period}-based implementation got wrong:
         * {@code Period.between(now, 35 days ago)} normalizes to {@code P-1M-4D}, so a
         * check that only read the days component saw {@code -4 <= -30} and answered
         * "not old".
         */
        @Test
        @DisplayName("35 days old with a 30-day limit is old (the months-component bug)")
        void thirtyFiveDaysIsOlderThanThirty() {
            LocalDate today = LocalDate.of(2026, 8, 10);
            assertTrue(AgentDeploymentManagement.isOlderThanDays(today.minusDays(35), 30, today));
        }

        @Test
        @DisplayName("boundaries: exactly N days is old, N-1 is not")
        void boundaries() {
            LocalDate today = LocalDate.of(2026, 8, 10);
            assertTrue(AgentDeploymentManagement.isOlderThanDays(today.minusDays(30), 30, today));
            assertFalse(AgentDeploymentManagement.isOlderThanDays(today.minusDays(29), 30, today));
        }

        /**
         * Every offset from 30 to 400 days must be reported as old — the old
         * implementation was correct only where the month boundary happened to fall.
         */
        @Test
        @DisplayName("every age past the limit is old, with no gaps")
        void noGapsAcrossMonthBoundaries() {
            LocalDate today = LocalDate.of(2026, 8, 10);
            for (int daysAgo = 30; daysAgo <= 400; daysAgo++) {
                assertTrue(AgentDeploymentManagement.isOlderThanDays(today.minusDays(daysAgo), 30, today),
                        daysAgo + " days ago must count as older than 30 days");
            }
        }

        @Test
        @DisplayName("no age below the limit is old")
        void noFalsePositives() {
            LocalDate today = LocalDate.of(2026, 8, 10);
            for (int daysAgo = 0; daysAgo < 30; daysAgo++) {
                assertFalse(AgentDeploymentManagement.isOlderThanDays(today.minusDays(daysAgo), 30, today),
                        daysAgo + " days ago must not count as older than 30 days");
            }
        }
    }

    @Nested
    @DisplayName("the sweep uses the conversation's own age")
    class SweepUsesConversationAge {

        /**
         * The property that makes fixing the arithmetic safe. The agent config is two
         * years stale — under the old age signal every conversation on it was "idle" —
         * but this conversation was touched an hour ago and must survive.
         */
        @Test
        @DisplayName("a recently-active conversation on a long-untouched agent is NOT ended")
        void recentConversationOnStaleAgentSurvives() throws Exception {
            givenOldAgentVersionWithConversation(snapshotWithTimestamps(Instant.now().minus(1, ChronoUnit.HOURS)),
                    Instant.now().minus(730, ChronoUnit.DAYS));

            management.manageAgentDeployments();

            verify(conversationMemoryStore, never()).compareAndSetState(any(), any(), eq(ConversationState.ENDED));
        }

        @Test
        @DisplayName("a genuinely idle conversation IS ended, even on a freshly-edited agent")
        void idleConversationOnFreshAgentIsEnded() throws Exception {
            givenOldAgentVersionWithConversation(snapshotWithTimestamps(Instant.now().minus(90, ChronoUnit.DAYS)), Instant.now());

            management.manageAgentDeployments();

            verify(conversationMemoryStore).compareAndSetState(eq("conv-1"), any(), eq(ConversationState.ENDED));
        }

        @Test
        @DisplayName("a paused (AWAITING_HUMAN) conversation is still spared regardless of age")
        void pausedConversationSpared() throws Exception {
            var paused = withState(snapshotWithTimestamps(Instant.now().minus(400, ChronoUnit.DAYS)), ConversationState.AWAITING_HUMAN);
            givenOldAgentVersionWithConversation(paused, Instant.now().minus(400, ChronoUnit.DAYS));

            management.manageAgentDeployments();

            verify(conversationMemoryStore, never()).compareAndSetState(any(), any(), eq(ConversationState.ENDED));
        }

        /**
         * The descriptor fallback, which only fires for a conversation whose steps
         * carry no timestamp at all. Untested until now: the fixture always supplied a
         * descriptor timestamp AND a step timestamp, so neither new branch was reached.
         */
        @Test
        @DisplayName("an untimestamped conversation falls back to the descriptor and is ended when that is old")
        void untimestampedConversationUsesDescriptorFallback() throws Exception {
            givenOldAgentVersionWithConversation(snapshotWithTimestamps(), Instant.now().minus(400, ChronoUnit.DAYS));

            management.manageAgentDeployments();

            verify(conversationMemoryStore).compareAndSetState(eq("conv-1"), any(), eq(ConversationState.ENDED));
        }

        @Test
        @DisplayName("an untimestamped conversation on a recently-edited agent is NOT ended")
        void untimestampedConversationWithRecentDescriptorSurvives() throws Exception {
            givenOldAgentVersionWithConversation(snapshotWithTimestamps(), Instant.now());

            management.manageAgentDeployments();

            verify(conversationMemoryStore, never()).compareAndSetState(any(), any(), eq(ConversationState.ENDED));
        }

        /**
         * With neither an age signal on the conversation nor one on the descriptor,
         * there is nothing to age against — and "cannot prove it is idle" must never
         * end a conversation. Dereferencing the absent descriptor stamp would also
         * throw an NPE the enclosing UndeploymentExecutor does not catch, aborting
         * every remaining undeploy in the pass.
         */
        @Test
        @DisplayName("no age signal anywhere — the conversation is preserved, not ended")
        void noAgeSignalAtAllPreservesTheConversation() throws Exception {
            givenOldAgentVersionWithConversation(snapshotWithTimestamps(), null);

            management.manageAgentDeployments();

            verify(conversationMemoryStore, never()).compareAndSetState(any(), any(), eq(ConversationState.ENDED));
        }

        /**
         * readDescriptor throws for a deleted agent, and manageAgentDeployments
         * deliberately routes deleted agents down this path. Reading it eagerly meant
         * one deleted agent aborted the entire sweep — including conversations that
         * carry a perfectly good timestamp and never needed the descriptor.
         */
        @Test
        @DisplayName("a conversation with its own timestamp is still swept when the agent descriptor is gone")
        void deletedDescriptorDoesNotAbortTheSweep() throws Exception {
            givenOldAgentVersionWithConversation(snapshotWithTimestamps(Instant.now().minus(400, ChronoUnit.DAYS)), null);
            when(documentDescriptorStore.readDescriptor(any(), any())).thenThrow(new RuntimeException("descriptor deleted"));

            management.manageAgentDeployments();

            verify(conversationMemoryStore).compareAndSetState(eq("conv-1"), any(), eq(ConversationState.ENDED));
        }

        /**
         * The snapshots were read at the top of the sweep. Between that read and the
         * write, a user can send a turn or the conversation can pause at an HITL gate —
         * an unconditional ENDED would destroy a live turn or a pending approval.
         */
        @Test
        @DisplayName("a conversation whose state changed since the snapshot is not ended")
        void stateChangedSinceSnapshotIsNotEnded() throws Exception {
            givenOldAgentVersionWithConversation(snapshotWithTimestamps(Instant.now().minus(400, ChronoUnit.DAYS)),
                    Instant.now().minus(400, ChronoUnit.DAYS));
            // The CAS loses: something else moved the conversation on.
            when(conversationMemoryStore.compareAndSetState(any(), any(), any())).thenReturn(false);

            management.manageAgentDeployments();

            verify(conversationMemoryStore).compareAndSetState(eq("conv-1"), any(), eq(ConversationState.ENDED));
            verify(conversationMemoryStore, never()).setConversationState(any(), any());
        }

        /**
         * The ABA case the state CAS alone cannot see: a turn that starts AND completes
         * inside the window returns the state to the value the snapshot carried, so the
         * CAS would succeed against a demonstrably active conversation. The age is the
         * ABA-resistant signal — a completed turn moves the newest step timestamp.
         */
        @Test
        @DisplayName("a conversation that became active during the sweep is not ended")
        void conversationActiveSinceSnapshotIsNotEnded() throws Exception {
            var stale = snapshotWithTimestamps(Instant.now().minus(400, ChronoUnit.DAYS));
            givenOldAgentVersionWithConversation(stale, Instant.now().minus(400, ChronoUnit.DAYS));
            // The re-read sees a turn that landed after the sweep loaded its snapshots.
            when(conversationMemoryStore.loadConversationActivity("conv-1"))
                    .thenReturn(snapshotWithTimestamps(Instant.now()));

            management.manageAgentDeployments();

            verify(conversationMemoryStore, never()).compareAndSetState(any(), any(), eq(ConversationState.ENDED));
        }

        @Test
        @DisplayName("a re-read failure leaves the conversation alone rather than ending it")
        void reReadFailurePreservesTheConversation() throws Exception {
            givenOldAgentVersionWithConversation(snapshotWithTimestamps(Instant.now().minus(400, ChronoUnit.DAYS)),
                    Instant.now().minus(400, ChronoUnit.DAYS));
            when(conversationMemoryStore.loadConversationActivity("conv-1")).thenThrow(new RuntimeException("store down"));

            management.manageAgentDeployments();

            verify(conversationMemoryStore, never()).compareAndSetState(any(), any(), eq(ConversationState.ENDED));
        }
    }

    /**
     * A production 5.x deployment held 34,000 open conversations averaging 630 KB;
     * the sweep loaded every one of them in full and ran a 2 GB JVM out of heap six
     * minutes after boot. The sweep must read the projection only.
     */
    @Nested
    @DisplayName("the sweep never materialises a conversation")
    class NeverLoadsConversations {

        @Test
        @DisplayName("neither the full-snapshot loader nor a single-conversation load is ever called")
        void noFullSnapshotIsLoaded() throws Exception {
            givenOldAgentVersionWithConversation(snapshotWithTimestamps(Instant.now().minus(90, ChronoUnit.DAYS)), Instant.now());

            management.manageAgentDeployments();

            // The conversation WAS ended, so the whole path ran: read, re-check, CAS.
            verify(conversationMemoryStore).compareAndSetState(eq("conv-1"), any(), eq(ConversationState.ENDED));
            verify(conversationMemoryStore, never()).loadActiveConversationMemorySnapshot(any(), any());
            verify(conversationMemoryStore, never()).loadConversationMemorySnapshot(any());
        }

        @Test
        @DisplayName("every batch is read, keyset-paged on the last id of the previous one")
        void pagesThroughEveryBatch() throws Exception {
            givenOldAgentVersionWithConversation(snapshotWithTimestamps(Instant.now().minus(90, ChronoUnit.DAYS)), Instant.now());
            var second = new ConversationActivitySummary("conv-2", ConversationState.READY, "agent-1", 1,
                    Instant.now().minus(90, ChronoUnit.DAYS));
            when(conversationMemoryStore.loadOpenConversationActivity(eq("agent-1"), eq(1), eq("conv-1"), anyInt())).thenReturn(List.of(second));
            when(conversationMemoryStore.loadConversationActivity("conv-2")).thenReturn(second);

            management.manageAgentDeployments();

            verify(conversationMemoryStore).compareAndSetState(eq("conv-1"), any(), eq(ConversationState.ENDED));
            verify(conversationMemoryStore).compareAndSetState(eq("conv-2"), any(), eq(ConversationState.ENDED));
        }

        @Test
        @DisplayName("an ended conversation records the end reason 'idle'")
        void recordsIdleEndReason() throws Exception {
            givenOldAgentVersionWithConversation(snapshotWithTimestamps(Instant.now().minus(90, ChronoUnit.DAYS)), Instant.now());

            management.manageAgentDeployments();

            verify(conversationMemoryStore).setConversationEndReason("conv-1", "idle");
        }

        @Test
        @DisplayName("a conversation left alone records no end reason")
        void noEndReasonWhenNotEnded() throws Exception {
            givenOldAgentVersionWithConversation(snapshotWithTimestamps(Instant.now().minus(1, ChronoUnit.HOURS)), Instant.now());

            management.manageAgentDeployments();

            verify(conversationMemoryStore, never()).setConversationEndReason(any(), any());
        }

        @Test
        @DisplayName("a disabled sweep reads nothing at all")
        void disabledSweepReadsNothing() throws Exception {
            management = managementWithIdleLimit(-1);
            givenOldAgentVersionWithConversation(snapshotWithTimestamps(Instant.now().minus(400, ChronoUnit.DAYS)), Instant.now());

            management.manageAgentDeployments();

            verify(conversationMemoryStore, never()).loadOpenConversationActivity(any(), any(), any(), anyInt());
            verify(conversationMemoryStore, never()).loadActiveConversationMemorySnapshot(any(), any());
        }
    }

    /**
     * A limit below one day switches the ending off. Taken literally, {@code -1}
     * and {@code 0} made every conversation "idle" ({@code DAYS.between(..) >= -1}
     * is always true) and the sweep ENDED all of them, and {@code -1} is how the
     * retention settings next to this one say "never".
     */
    @Nested
    @DisplayName("a limit below one day disables ending")
    class DisabledLimit {

        @ParameterizedTest(name = "limit {0}")
        @ValueSource(ints = {-1, 0})
        @DisplayName("no conversation is ended, however old")
        void nothingIsEnded(int limit) throws Exception {
            management = managementWithIdleLimit(limit);
            givenOldAgentVersionWithConversation(snapshotWithTimestamps(Instant.now().minus(400, ChronoUnit.DAYS)),
                    Instant.now().minus(400, ChronoUnit.DAYS));

            management.manageAgentDeployments();

            verify(conversationMemoryStore, never()).compareAndSetState(any(), any(), eq(ConversationState.ENDED));
            verify(conversationMemoryStore, never()).setConversationState(any(), any());
        }

        /**
         * The idle conversation is still open, so it still counts as active and keeps
         * its version deployed: the same answer a positive limit gives while the
         * conversation is younger than the limit.
         */
        @ParameterizedTest(name = "limit {0}")
        @ValueSource(ints = {-1, 0})
        @DisplayName("an old version with an open conversation stays deployed")
        void versionWithOpenConversationStaysDeployed(int limit) throws Exception {
            management = managementWithIdleLimit(limit);
            givenOldAgentVersionWithConversation(snapshotWithTimestamps(Instant.now().minus(400, ChronoUnit.DAYS)),
                    Instant.now().minus(400, ChronoUnit.DAYS));
            // The store as it behaves: an ENDED conversation stops counting as active,
            // so ending it would free the version for the undeploy check after it.
            var ended = new AtomicBoolean();
            when(conversationMemoryStore.compareAndSetState(any(), any(), eq(ConversationState.ENDED))).thenAnswer(inv -> {
                ended.set(true);
                return true;
            });
            when(conversationMemoryStore.getActiveConversationCount("agent-1", 1)).thenAnswer(inv -> ended.get() ? 0L : 1L);

            management.manageAgentDeployments();

            verify(agentFactory, never()).undeployAgent(any(), any(), any());
            verify(deploymentStore, never()).setDeploymentInfo(any(), any(), any(), eq(DeploymentInfo.DeploymentStatus.undeployed));
        }

        /**
         * Undeploying a version nobody is talking to ends nothing, so it is not part of
         * what "disabled" switches off.
         */
        @ParameterizedTest(name = "limit {0}")
        @ValueSource(ints = {-1, 0})
        @DisplayName("an old version with no active conversation is still undeployed")
        void versionWithNoActiveConversationIsUndeployed(int limit) throws Exception {
            management = managementWithIdleLimit(limit);
            givenOldAgentVersionWithConversation(snapshotWithTimestamps(Instant.now().minus(400, ChronoUnit.DAYS)),
                    Instant.now().minus(400, ChronoUnit.DAYS));
            when(conversationMemoryStore.getActiveConversationCount("agent-1", 1)).thenReturn(0L);

            management.manageAgentDeployments();

            // atLeastOnce: the sweep checks an old version twice, before and after the
            // ending pass, and a version with nothing active is undeployed by the first.
            verify(agentFactory, atLeastOnce()).undeployAgent(Environment.production, "agent-1", 1);
            verify(conversationMemoryStore, never()).compareAndSetState(any(), any(), eq(ConversationState.ENDED));
        }

        @Test
        @DisplayName("a positive limit is enabled, a limit below one day is not")
        void enabledFlag() {
            assertTrue(managementWithIdleLimit(1).idleEndingEnabled());
            assertTrue(managementWithIdleLimit(90).idleEndingEnabled());
            assertFalse(managementWithIdleLimit(0).idleEndingEnabled());
            assertFalse(managementWithIdleLimit(-1).idleEndingEnabled());
        }
    }

    /** The shipped default still ends what it always ended, and only that. */
    @Nested
    @DisplayName("the default limit of 90 days")
    class DefaultLimit {

        @BeforeEach
        void defaultLimit() {
            management = managementWithIdleLimit(90);
        }

        @Test
        @DisplayName("a conversation idle for 91 days is ended")
        void ninetyOneDaysIsEnded() throws Exception {
            givenOldAgentVersionWithConversation(snapshotWithTimestamps(Instant.now().minus(91, ChronoUnit.DAYS)), Instant.now());

            management.manageAgentDeployments();

            verify(conversationMemoryStore).compareAndSetState(eq("conv-1"), any(), eq(ConversationState.ENDED));
        }

        @Test
        @DisplayName("a conversation idle for 89 days is not")
        void eightyNineDaysIsKept() throws Exception {
            givenOldAgentVersionWithConversation(snapshotWithTimestamps(Instant.now().minus(89, ChronoUnit.DAYS)), Instant.now());

            management.manageAgentDeployments();

            verify(conversationMemoryStore, never()).compareAndSetState(any(), any(), eq(ConversationState.ENDED));
        }
    }

    private void givenOldAgentVersionWithConversation(ConversationActivitySummary activity, Instant agentLastModified) throws Exception {
        var info = new DeploymentInfo();
        info.setEnvironment(Environment.production);
        info.setAgentId("agent-1");
        info.setAgentVersion(1);
        when(deploymentStore.readDeploymentInfos(DeploymentInfo.DeploymentStatus.deployed)).thenReturn(List.of(info));

        // Version 1 is not the latest, so the undeploy path (and the sweep) runs.
        var latest = mock(IResourceId.class);
        when(latest.getId()).thenReturn("agent-1");
        when(latest.getVersion()).thenReturn(2);
        when(agentStore.getCurrentResourceId("agent-1")).thenReturn(latest);

        when(conversationMemoryStore.getActiveConversationCount("agent-1", 1)).thenReturn(1L);
        when(conversationMemoryStore.compareAndSetState(any(), any(), any())).thenReturn(true);
        // One batch; the next page (after its last id) is empty, which ends the paging.
        when(conversationMemoryStore.loadOpenConversationActivity(eq("agent-1"), eq(1), isNull(), anyInt())).thenReturn(List.of(activity));
        // The sweep re-reads the conversation's activity immediately before ending it.
        when(conversationMemoryStore.loadConversationActivity("conv-1")).thenReturn(activity);

        var descriptor = new DocumentDescriptor();
        descriptor.setName("Test Agent");
        descriptor.setLastModifiedOn(agentLastModified != null ? Date.from(agentLastModified) : null);
        when(documentDescriptorStore.readDescriptor("agent-1", 1)).thenReturn(descriptor);
    }

    /**
     * What the store projects for a conversation whose data carries exactly the
     * given timestamps: the newest of them, or none.
     */
    private static ConversationActivitySummary snapshotWithTimestamps(Instant... timestamps) {
        Instant newest = null;
        for (Instant timestamp : timestamps) {
            if (newest == null || timestamp.isAfter(newest)) {
                newest = timestamp;
            }
        }
        return new ConversationActivitySummary("conv-1", ConversationState.READY, "agent-1", 1, newest);
    }

    private static ConversationActivitySummary withState(ConversationActivitySummary activity, ConversationState state) {
        return new ConversationActivitySummary(activity.conversationId(), state, activity.agentId(), activity.agentVersion(),
                activity.lastInteraction());
    }
}
