/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.agenttests;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agenttests.IAgentTestSuiteStore;
import ai.labs.eddi.configs.agenttests.model.AgentTestSuite;
import ai.labs.eddi.configs.agenttests.model.AgentTestSuite.TestCase;
import ai.labs.eddi.configs.agenttests.model.AgentTestSuite.Turn;
import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.agenttests.model.AgentTestRun;
import ai.labs.eddi.engine.api.IRestAgentAdministration;
import ai.labs.eddi.engine.cluster.ClusterConfig;
import ai.labs.eddi.engine.cluster.NodeIdentity;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.IAgentFactory;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@DisplayName("AgentTestService")
class AgentTestServiceTest {

    private static final String SUITE_ID = "0123456789abcdef01234567";
    private static final String AGENT_ID = "abcdefabcdefabcdefabcdef";
    private static final String RUN_ID = "aaaaaaaaaaaaaaaaaaaaaaaa";

    private IAgentTestSuiteStore suiteStore;
    private IAgentTestRunStore runStore;
    private AgentTestRunner runner;
    private IRestAgentAdministration administration;
    private IAgentFactory agentFactory;
    private ResourceAccessGuard accessGuard;
    private ClusterConfig clusterConfig;
    private Instance<SecurityIdentity> identity;
    private AgentTestService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        suiteStore = mock(IAgentTestSuiteStore.class);
        runStore = mock(IAgentTestRunStore.class);
        runner = mock(AgentTestRunner.class);
        administration = mock(IRestAgentAdministration.class);
        agentFactory = mock(IAgentFactory.class);
        accessGuard = mock(ResourceAccessGuard.class);
        identity = mock(Instance.class);
        when(identity.isResolvable()).thenReturn(false);
        clusterConfig = mock(ClusterConfig.class);
        service = serviceWithSlots(2);

        var suite = new AgentTestSuite("wizard", null, null, null, null,
                List.of(new TestCase("c", null, null, null, List.of(new Turn("hi", null, null)))));
        when(suiteStore.read(SUITE_ID, 3)).thenReturn(suite);
        when(runStore.create(any())).thenReturn(RUN_ID);
    }

    private AgentTestService serviceWithSlots(int slots) {
        return new AgentTestService(suiteStore, runStore, runner, administration, agentFactory, mock(IAgentStore.class), accessGuard,
                identity, clusterConfig, new NodeIdentity("node-a", "boot0001"), slots);
    }

    private static AgentTestRun runIn(AgentTestRun.Status status) {
        return new AgentTestRun(RUN_ID, SUITE_ID, 3, "s", AGENT_ID, 7, status, "alice", 1L, null, null, null, 1.0, null, null, null, null,
                null, null, false, null, "node-b/boot0001", 1L);
    }

    private void deployAnswers(String status, Integer servedVersion) throws Exception {
        when(administration.deployAgent(eq(Environment.test), eq(AGENT_ID), anyInt(), anyBoolean(), anyBoolean()))
                .thenReturn(Response.ok(Map.of("status", status)).build());
        IAgent served = mock(IAgent.class);
        when(served.getAgentVersion()).thenReturn(servedVersion);
        when(agentFactory.getLatestReadyAgent(Environment.test, AGENT_ID)).thenReturn(served);
    }

    @Test
    @DisplayName("deploys to test only (unrecorded, waited for) and returns the RUNNING record")
    void startsARun() throws Exception {
        deployAnswers("READY", 7);

        AgentTestRun run = service.startRun(SUITE_ID, 3, AGENT_ID, 7, false);

        assertEquals(RUN_ID, run.id());
        assertEquals(AgentTestRun.Status.RUNNING, run.status());
        assertEquals(7, run.agentVersion());
        verify(administration).deployAgent(Environment.test, AGENT_ID, 7, false, true);
        verify(administration, never()).deployAgent(eq(Environment.production), anyString(), anyInt(), anyBoolean(), anyBoolean());
        verify(accessGuard).requireAccess(SUITE_ID, AccessLevel.VIEW, "agent test suite");
        verify(runner, timeout(5_000)).run(any(AgentTestRun.class), any(AgentTestSuite.class));
    }

    @Test
    @DisplayName("the run record names this node as its owner and starts with a heartbeat")
    void recordsItsOwner() throws Exception {
        deployAnswers("READY", 7);

        AgentTestRun run = service.startRun(SUITE_ID, 3, AGENT_ID, 7, false);

        assertEquals("node-a/boot0001", run.ownerNode());
        assertNotNull(run.heartbeatAt());
    }

    /**
     * A request that is going to be refused for capacity must not first deploy a
     * version to {@code test} — that changes what every other client of the
     * environment talks to.
     */
    @Test
    @DisplayName("no free run slot: 429 before anything is deployed")
    void capacityIsCheckedBeforeTheDeploy() throws Exception {
        deployAnswers("READY", 7);
        var release = new CountDownLatch(1);
        doAnswer(i -> {
            release.await(10, TimeUnit.SECONDS);
            return null;
        }).when(runner).run(any(AgentTestRun.class), any(AgentTestSuite.class));
        var oneSlot = serviceWithSlots(1);
        try {
            oneSlot.startRun(SUITE_ID, 3, AGENT_ID, 7, false);

            var e = assertThrows(ClientErrorException.class, () -> oneSlot.startRun(SUITE_ID, 3, AGENT_ID, 7, false));

            assertEquals(429, e.getResponse().getStatus());
            verify(administration, times(1)).deployAgent(eq(Environment.test), eq(AGENT_ID), anyInt(), anyBoolean(), anyBoolean());
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("a refused deploy gives its run slot back")
    void failedDeployReleasesTheSlot() throws Exception {
        deployAnswers("ERROR", null);
        var oneSlot = serviceWithSlots(1);

        for (int i = 0; i < 3; i++) {
            var e = assertThrows(ClientErrorException.class, () -> oneSlot.startRun(SUITE_ID, 3, AGENT_ID, 7, false));
            assertEquals(409, e.getResponse().getStatus(), "a leaked slot would turn this into 429");
        }
    }

    @Test
    @DisplayName("cancelling a running run takes EDIT on its agent and asks the runner to stop it")
    void cancelsARunningRun() throws Exception {
        when(runStore.read(RUN_ID)).thenReturn(runIn(AgentTestRun.Status.RUNNING));
        when(runner.cancel(eq(RUN_ID), anyString())).thenReturn(true);

        AgentTestRun run = service.cancelRun(RUN_ID);

        assertEquals(RUN_ID, run.id());
        verify(accessGuard).requireAccess(AGENT_ID, AccessLevel.EDIT, "agent");
        verify(runner).cancel(RUN_ID, "anonymous");
    }

    @Test
    @DisplayName("cancel is refused without EDIT on the agent, before the runner is asked")
    void cancelNeedsEdit() throws Exception {
        when(runStore.read(RUN_ID)).thenReturn(runIn(AgentTestRun.Status.RUNNING));
        doThrow(new ForbiddenException("no")).when(accessGuard).requireAccess(AGENT_ID, AccessLevel.EDIT, "agent");

        assertThrows(ForbiddenException.class, () -> service.cancelRun(RUN_ID));
        verify(runner, never()).cancel(anyString(), anyString());
    }

    @Test
    @DisplayName("a finished run, or one executing on another node, cannot be cancelled here — 409")
    void cancelConflicts() throws Exception {
        when(runStore.read(RUN_ID)).thenReturn(runIn(AgentTestRun.Status.PASSED));
        assertEquals(409, assertThrows(ClientErrorException.class, () -> service.cancelRun(RUN_ID)).getResponse().getStatus());
        verify(runner, never()).cancel(anyString(), anyString());

        when(runStore.read(RUN_ID)).thenReturn(runIn(AgentTestRun.Status.RUNNING));
        when(runner.cancel(eq(RUN_ID), anyString())).thenReturn(false);
        var e = assertThrows(ClientErrorException.class, () -> service.cancelRun(RUN_ID));
        assertEquals(409, e.getResponse().getStatus());
        assertTrue(e.getMessage().contains("node-b/boot0001"), e.getMessage());
    }

    @Test
    void cancellingAnUnknownRunIsNotFound() throws Exception {
        when(runStore.read(RUN_ID)).thenThrow(new IResourceStore.ResourceNotFoundException("nope"));

        assertThrows(NotFoundException.class, () -> service.cancelRun(RUN_ID));
    }

    @Test
    @DisplayName("a newer version deployed to test would be the one tested — refused with 409")
    void newerVersionInTestIsRefused() throws Exception {
        deployAnswers("READY", 9);

        var e = assertThrows(ClientErrorException.class, () -> service.startRun(SUITE_ID, 3, AGENT_ID, 7, false));

        assertEquals(409, e.getResponse().getStatus());
        assertTrue(e.getMessage().contains("Version 9"), e.getMessage());
        verifyNoInteractions(runStore, runner);
    }

    @Test
    void failedDeployIsRefused() throws Exception {
        deployAnswers("ERROR", null);

        var e = assertThrows(ClientErrorException.class, () -> service.startRun(SUITE_ID, 3, AGENT_ID, 7, false));

        assertEquals(409, e.getResponse().getStatus());
        verifyNoInteractions(runner);
    }

    @Test
    @DisplayName("no deploy happens for a caller who may not read the suite")
    void suiteAccessIsCheckedFirst() {
        doThrow(new ForbiddenException("no")).when(accessGuard).requireAccess(SUITE_ID, AccessLevel.VIEW, "agent test suite");

        assertThrows(ForbiddenException.class, () -> service.startRun(SUITE_ID, 3, AGENT_ID, 7, false));
        verifyNoInteractions(administration, runner);
    }

    @Test
    void emptySuiteIsABadRequest() throws Exception {
        when(suiteStore.read(SUITE_ID, 3)).thenReturn(new AgentTestSuite("draft", null, null, null, null, List.of()));

        assertThrows(BadRequestException.class, () -> service.startRun(SUITE_ID, 3, AGENT_ID, 7, false));
        verifyNoInteractions(administration);
    }

    @Test
    void readingARunNeedsViewOnItsAgent() throws Exception {
        var run = new AgentTestRun(RUN_ID, SUITE_ID, 3, "s", AGENT_ID, 7, AgentTestRun.Status.PASSED, "alice", 1L, 2L, 1L, 0.0, 1.0, false,
                List.of(), 1, 1, 0, List.of(), false, null, null, null);
        when(runStore.read(RUN_ID)).thenReturn(run);

        assertSame(run, service.readRun(RUN_ID));
        verify(accessGuard).requireAccess(AGENT_ID, AccessLevel.VIEW, "agent");
    }

    @Test
    void unknownRunIsNotFound() throws Exception {
        when(runStore.read(RUN_ID)).thenThrow(new IResourceStore.ResourceNotFoundException("nope"));

        assertThrows(NotFoundException.class, () -> service.readRun(RUN_ID));
    }

    @Test
    @DisplayName("cluster mode refuses the run up front: mocks live in one JVM, turns may run on another node")
    void clusterModeIsRefused() throws Exception {
        when(clusterConfig.isNats()).thenReturn(true);

        var e = assertThrows(ClientErrorException.class, () -> service.startRun(SUITE_ID, 3, AGENT_ID, 7, false));

        assertEquals(409, e.getResponse().getStatus());
        assertTrue(e.getMessage().contains("cluster mode"), e.getMessage());
        verifyNoInteractions(administration, runStore, runner);
    }
}
