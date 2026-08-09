/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.runtime.IAgent;
import ai.labs.eddi.engine.runtime.client.agents.IAgentStoreClientLibrary;
import ai.labs.eddi.engine.runtime.model.DeploymentEvent;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static ai.labs.eddi.engine.model.Deployment.Environment.test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * Regression pins for the agent deploy/undeploy lifecycle.
 * <p>
 * Every test here fails if its fix is reverted — they assert behaviour, not
 * structure.
 */
@DisplayName("AgentFactory — deploy/undeploy lifecycle")
class AgentFactoryLifecycleTest {

    @Mock
    private IAgentStoreClientLibrary agentStoreClientLibrary;
    @Mock
    private IDeploymentListener deploymentListener;

    private SimpleMeterRegistry meterRegistry;
    private AgentFactory factory;
    private AutoCloseable mocks;

    @BeforeEach
    void setUp() {
        mocks = openMocks(this);
        meterRegistry = new SimpleMeterRegistry();
        factory = new AgentFactory(agentStoreClientLibrary, deploymentListener, meterRegistry);
    }

    @AfterEach
    void tearDown() throws Exception {
        mocks.close();
    }

    private Agent deploy(String agentId, int version) throws Exception {
        var agent = new Agent(agentId, version);
        when(agentStoreClientLibrary.getAgent(agentId, version)).thenReturn(agent);
        factory.deployAgent(test, agentId, version, null);
        return agent;
    }

    private double deployedGauge() {
        Gauge gauge = meterRegistry.find("eddi_agents_deployed").gauge();
        assertNotNull(gauge, "eddi_agents_deployed gauge should be registered");
        return gauge.value();
    }

    @Nested
    @DisplayName("undeployAgent with a null version")
    class NullVersionUndeploy {

        /**
         * The bug: {@code AgentId} equality compares the version, so
         * {@code remove(new AgentId(id, null))} matched no entry and the agent stayed
         * deployed. Both production callers that tear down a dynamically created agent
         * (TeardownAgentTool, GroupLifecycleOps#cleanupEphemeralAgents) pass null,
         * because they know the agent only by id.
         */
        @Test
        @DisplayName("removes EVERY deployed version of the agent")
        void removesAllVersions() throws Exception {
            deploy("dynamic-agent", 1);
            deploy("dynamic-agent", 2);
            deploy("other-agent", 1);

            int removed = factory.undeployAgent(test, "dynamic-agent", null);

            assertEquals(2, removed);
            assertNull(factory.getAgent(test, "dynamic-agent", 1));
            assertNull(factory.getAgent(test, "dynamic-agent", 2));
            assertNotNull(factory.getAgent(test, "other-agent", 1), "an unrelated agent must be untouched");
        }

        /**
         * The consequence that made this a security problem rather than a leak: a "torn
         * down" — and, with delete=true, config-deleted — agent stayed reachable
         * through the lookup that actually serves conversations.
         */
        @Test
        @DisplayName("the agent is no longer servable through getLatestReadyAgent")
        void noLongerServable() throws Exception {
            deploy("dynamic-agent", 3);
            assertNotNull(factory.getLatestReadyAgent(test, "dynamic-agent"));

            factory.undeployAgent(test, "dynamic-agent", null);

            assertNull(factory.getLatestReadyAgent(test, "dynamic-agent"),
                    "a torn-down agent must not still be servable — this is what kept deleted agents conversable");
        }

        @Test
        @DisplayName("decrements the deployed-agents gauge")
        void decrementsGauge() throws Exception {
            deploy("dynamic-agent", 1);
            deploy("dynamic-agent", 2);
            assertEquals(2.0, deployedGauge());

            factory.undeployAgent(test, "dynamic-agent", null);

            assertEquals(0.0, deployedGauge(), "the gauge leaked monotonically while removal silently matched nothing");
        }

        @Test
        @DisplayName("returns 0 when nothing was deployed under that id")
        void returnsZeroWhenAbsent() {
            assertEquals(0, factory.undeployAgent(test, "never-deployed", null),
                    "callers report success to an LLM based on this count");
        }
    }

    @Nested
    @DisplayName("undeployAgent with an explicit version")
    class ExplicitVersionUndeploy {

        @Test
        @DisplayName("removes only that version")
        void removesOnlyThatVersion() throws Exception {
            deploy("agent", 1);
            deploy("agent", 2);

            assertEquals(1, factory.undeployAgent(test, "agent", 1));

            assertNull(factory.getAgent(test, "agent", 1));
            assertNotNull(factory.getAgent(test, "agent", 2));
            assertEquals(1.0, deployedGauge());
        }
    }

    @Nested
    @DisplayName("deployment claim")
    class DeploymentClaim {

        /**
         * The IN_PROGRESS marker used to be a local variable that was only ever
         * returned after being flipped to ERROR, so no IN_PROGRESS value ever rested in
         * the map: {@code getAgent}'s IN_PROGRESS branch and
         * {@code waitForDeploymentCompletion} were both unreachable, and a lookup
         * arriving mid-deployment got a bare null.
         */
        @Test
        @DisplayName("a lookup during a deployment observes IN_PROGRESS and waits for the result")
        void inProgressIsObservable() throws Exception {
            var loadEntered = new CountDownLatch(1);
            var releaseLoad = new CountDownLatch(1);
            var loaded = new Agent("slow-agent", 1);
            var deploymentFuture = new CompletableFuture<Void>();

            when(deploymentListener.registerAgentDeployment("slow-agent", 1)).thenReturn(deploymentFuture);
            when(deploymentListener.getRegisteredDeploymentEvent("slow-agent", 1)).thenReturn(deploymentFuture);
            // Complete the future when the factory publishes its READY event, exactly
            // as the real DeploymentListener does.
            doAnswer(invocation -> {
                DeploymentEvent event = invocation.getArgument(0);
                if (event.status() == Deployment.Status.READY) {
                    deploymentFuture.complete(null);
                }
                return null;
            }).when(deploymentListener).onDeploymentEvent(any(DeploymentEvent.class));

            when(agentStoreClientLibrary.getAgent("slow-agent", 1)).thenAnswer(invocation -> {
                loadEntered.countDown();
                assertTrue(releaseLoad.await(5, TimeUnit.SECONDS), "load was never released");
                return loaded;
            });

            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                pool.submit(() -> factory.deployAgent(test, "slow-agent", 1, null));
                assertTrue(loadEntered.await(5, TimeUnit.SECONDS), "deployment never started loading");

                // While the load is in flight the marker must be visible…
                assertEquals(Deployment.Status.IN_PROGRESS,
                        factory.getAllLatestAgents(test).stream().filter(a -> "slow-agent".equals(a.getAgentId())).findFirst().orElseThrow()
                                .getDeploymentStatus(),
                        "the IN_PROGRESS marker must be published before the blocking load");

                // …and a concurrent getAgent must block on it rather than return null.
                var lookup = new AtomicReference<IAgent>();
                var lookupDone = new CountDownLatch(1);
                pool.submit(() -> {
                    lookup.set(factory.getAgent(test, "slow-agent", 1));
                    lookupDone.countDown();
                });

                releaseLoad.countDown();
                assertTrue(lookupDone.await(5, TimeUnit.SECONDS), "the waiting lookup never completed");
                assertSame(loaded, lookup.get(), "the waiter must receive the deployed agent, not null");
            } finally {
                pool.shutdownNow();
            }
        }

        /**
         * The store load runs outside the map's mapping function now, so an undeploy
         * can land between the claim and the publish. Publishing unconditionally would
         * resurrect an agent that was deliberately torn down.
         */
        @Test
        @DisplayName("an undeploy racing the load is not overwritten by the late publish")
        void undeployDuringLoadIsNotResurrected() throws Exception {
            var loadEntered = new CountDownLatch(1);
            var releaseLoad = new CountDownLatch(1);

            when(agentStoreClientLibrary.getAgent("racy-agent", 1)).thenAnswer(invocation -> {
                loadEntered.countDown();
                assertTrue(releaseLoad.await(5, TimeUnit.SECONDS));
                return new Agent("racy-agent", 1);
            });

            var reported = new AtomicReference<Deployment.Status>();
            ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                var deployment = pool.submit(() -> factory.deployAgent(test, "racy-agent", 1, reported::set));
                assertTrue(loadEntered.await(5, TimeUnit.SECONDS));

                factory.undeployAgent(test, "racy-agent", null);
                releaseLoad.countDown();
                deployment.get(5, TimeUnit.SECONDS);

                assertNull(factory.getAgent(test, "racy-agent", 1), "the torn-down agent must not be resurrected by the in-flight deployment");
                assertEquals(0.0, deployedGauge());
                // Reporting READY here would be a lie with consequences: the deploy
                // callback persists a 'deployed' record, which the redeploy sweep would
                // use to resurrect the agent the teardown deliberately removed.
                assertNotEquals(Deployment.Status.READY, reported.get(),
                        "a deployment whose agent was undeployed mid-flight must not report READY");
            } finally {
                pool.shutdownNow();
            }
        }

        @Test
        @DisplayName("a failed load leaves an ERROR entry and releases waiters")
        void failedLoadPublishesError() throws Exception {
            when(agentStoreClientLibrary.getAgent("broken", 1)).thenThrow(new ServiceException("boom"));

            var reported = new AtomicReference<Deployment.Status>();
            factory.deployAgent(test, "broken", 1, reported::set);

            assertEquals(Deployment.Status.ERROR, reported.get());
            IAgent agent = factory.getAgent(test, "broken", 1);
            assertNotNull(agent);
            assertEquals(Deployment.Status.ERROR, agent.getDeploymentStatus());
            verify(deploymentListener).onDeploymentEvent(any(DeploymentEvent.class));
        }

        @Test
        @DisplayName("an already-READY agent is not redeployed but still reports READY")
        void alreadyDeployed() throws Exception {
            deploy("agent", 1);

            var reported = new AtomicReference<Deployment.Status>();
            factory.deployAgent(test, "agent", 1, reported::set);

            assertEquals(Deployment.Status.READY, reported.get());
            verify(agentStoreClientLibrary).getAgent("agent", 1);
        }

        @Test
        @DisplayName("a RuntimeException from the store clears the claim instead of stranding it IN_PROGRESS")
        void runtimeExceptionClearsClaim() throws Exception {
            when(agentStoreClientLibrary.getAgent("exploding", 1)).thenThrow(new IllegalStateException("kaboom"));

            try {
                factory.deployAgent(test, "exploding", 1, null);
            } catch (IllegalStateException expected) {
                // propagated, as before
            }

            IAgent agent = factory.getAgent(test, "exploding", 1);
            assertNotNull(agent, "the claim must resolve to ERROR, not stay IN_PROGRESS");
            assertEquals(Deployment.Status.ERROR, agent.getDeploymentStatus());
            verify(deploymentListener, never()).getRegisteredDeploymentEvent("exploding", 1);
        }
    }
}
