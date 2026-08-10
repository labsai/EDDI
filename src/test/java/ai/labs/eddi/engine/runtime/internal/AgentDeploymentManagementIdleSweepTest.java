/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

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
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ConversationStepSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.ResultSnapshot;
import ai.labs.eddi.engine.memory.model.ConversationMemorySnapshot.WorkflowRunSnapshot;
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

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    private IAgentStore agentStore;
    private IConversationMemoryStore conversationMemoryStore;
    private IDocumentDescriptorStore documentDescriptorStore;
    private AgentDeploymentManagement management;

    @BeforeEach
    void setUp() {
        deploymentStore = mock(IDeploymentStore.class);
        var agentFactory = mock(IAgentFactory.class);
        agentStore = mock(IAgentStore.class);
        var agentsReadiness = mock(IAgentsReadiness.class);
        conversationMemoryStore = mock(IConversationMemoryStore.class);
        documentDescriptorStore = mock(IDocumentDescriptorStore.class);
        var migrationManager = mock(IMigrationManager.class);
        var runtime = mock(IRuntime.class);
        when(runtime.getScheduledExecutorService()).thenReturn(mock(ScheduledExecutorService.class));

        management = new AgentDeploymentManagement(deploymentStore, agentFactory, agentStore, agentsReadiness, conversationMemoryStore,
                documentDescriptorStore, migrationManager, mock(V6RenameMigration.class), mock(V6QuteMigration.class),
                mock(ChannelConnectorMigration.class), runtime, mock(IWorkflowStore.class), mock(IRuleSetStore.class), MAX_IDLE_DAYS);
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
            assertTrue(AgentDeploymentManagement.isOlderThanDays(LocalDate.now().minusDays(35), 30));
        }

        @Test
        @DisplayName("boundaries: exactly N days is old, N-1 is not")
        void boundaries() {
            assertTrue(AgentDeploymentManagement.isOlderThanDays(LocalDate.now().minusDays(30), 30));
            assertFalse(AgentDeploymentManagement.isOlderThanDays(LocalDate.now().minusDays(29), 30));
        }

        /**
         * Every offset from 30 to 400 days must be reported as old — the old
         * implementation was correct only where the month boundary happened to fall.
         */
        @Test
        @DisplayName("every age past the limit is old, with no gaps")
        void noGapsAcrossMonthBoundaries() {
            for (int daysAgo = 30; daysAgo <= 400; daysAgo++) {
                assertTrue(AgentDeploymentManagement.isOlderThanDays(LocalDate.now().minusDays(daysAgo), 30),
                        daysAgo + " days ago must count as older than 30 days");
            }
        }

        @Test
        @DisplayName("no age below the limit is old")
        void noFalsePositives() {
            for (int daysAgo = 0; daysAgo < 30; daysAgo++) {
                assertFalse(AgentDeploymentManagement.isOlderThanDays(LocalDate.now().minusDays(daysAgo), 30),
                        daysAgo + " days ago must not count as older than 30 days");
            }
        }
    }

    @Nested
    @DisplayName("lastInteractionOf")
    class LastInteraction {

        @Test
        @DisplayName("returns the newest timestamp across every step")
        void newestAcrossSteps() {
            Instant oldest = Instant.now().minus(10, ChronoUnit.DAYS);
            Instant newest = Instant.now().minus(1, ChronoUnit.HOURS);

            var snapshot = snapshotWithTimestamps(oldest, newest, Instant.now().minus(5, ChronoUnit.DAYS));

            assertEquals(newest.toEpochMilli(), AgentDeploymentManagement.lastInteractionOf(snapshot).toEpochMilli());
        }

        @Test
        @DisplayName("null when nothing is timestamped")
        void nullWhenUntimestamped() {
            assertNull(AgentDeploymentManagement.lastInteractionOf(new ConversationMemorySnapshot()));
            assertNull(AgentDeploymentManagement.lastInteractionOf(snapshotWithTimestamps()));
            assertNull(AgentDeploymentManagement.lastInteractionOf(null));
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

            verify(conversationMemoryStore, never()).setConversationState(any(), eq(ConversationState.ENDED));
        }

        @Test
        @DisplayName("a genuinely idle conversation IS ended, even on a freshly-edited agent")
        void idleConversationOnFreshAgentIsEnded() throws Exception {
            givenOldAgentVersionWithConversation(snapshotWithTimestamps(Instant.now().minus(90, ChronoUnit.DAYS)), Instant.now());

            management.manageAgentDeployments();

            verify(conversationMemoryStore).setConversationState("conv-1", ConversationState.ENDED);
        }

        @Test
        @DisplayName("a paused (AWAITING_HUMAN) conversation is still spared regardless of age")
        void pausedConversationSpared() throws Exception {
            var snapshot = snapshotWithTimestamps(Instant.now().minus(400, ChronoUnit.DAYS));
            snapshot.setConversationState(ConversationState.AWAITING_HUMAN);
            givenOldAgentVersionWithConversation(snapshot, Instant.now().minus(400, ChronoUnit.DAYS));

            management.manageAgentDeployments();

            verify(conversationMemoryStore, never()).setConversationState(any(), eq(ConversationState.ENDED));
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

            verify(conversationMemoryStore).setConversationState("conv-1", ConversationState.ENDED);
        }

        @Test
        @DisplayName("an untimestamped conversation on a recently-edited agent is NOT ended")
        void untimestampedConversationWithRecentDescriptorSurvives() throws Exception {
            givenOldAgentVersionWithConversation(snapshotWithTimestamps(), Instant.now());

            management.manageAgentDeployments();

            verify(conversationMemoryStore, never()).setConversationState(any(), eq(ConversationState.ENDED));
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

            verify(conversationMemoryStore, never()).setConversationState(any(), eq(ConversationState.ENDED));
        }

        private void givenOldAgentVersionWithConversation(ConversationMemorySnapshot snapshot, Instant agentLastModified) throws Exception {
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
            when(conversationMemoryStore.loadActiveConversationMemorySnapshot("agent-1", 1)).thenReturn(List.of(snapshot));

            var descriptor = new DocumentDescriptor();
            descriptor.setName("Test Agent");
            descriptor.setLastModifiedOn(agentLastModified != null ? Date.from(agentLastModified) : null);
            when(documentDescriptorStore.readDescriptor("agent-1", 1)).thenReturn(descriptor);
        }
    }

    /** A snapshot whose steps carry exactly the given data timestamps. */
    private static ConversationMemorySnapshot snapshotWithTimestamps(Instant... timestamps) {
        var snapshot = new ConversationMemorySnapshot();
        snapshot.setId("conv-1");
        snapshot.setAgentId("agent-1");
        snapshot.setAgentVersion(1);

        for (Instant timestamp : timestamps) {
            var result = new ResultSnapshot();
            result.setKey("input");
            result.setTimestamp(Date.from(timestamp));

            var workflow = new WorkflowRunSnapshot();
            workflow.setLifecycleTasks(List.of(result));

            var step = new ConversationStepSnapshot();
            step.setWorkflows(List.of(workflow));

            snapshot.getConversationSteps().add(step);
        }
        return snapshot;
    }
}
