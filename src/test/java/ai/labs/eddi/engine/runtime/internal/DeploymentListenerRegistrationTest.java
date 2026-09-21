/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.runtime.model.DeploymentEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Registration lifecycle in {@link DeploymentListener}.
 * <p>
 * Two properties matter here. A registration must be <em>discoverable</em>
 * while the deployment is in flight — that is what makes
 * {@code AgentFactory#waitForDeploymentCompletion} able to wait at all — and it
 * must not outlive the deployment it describes: the map was only ever pruned by
 * an arriving {@link DeploymentEvent}, so a registration whose event never came
 * (a rejected callable, a process that died mid-deploy) stayed for the lifetime
 * of the JVM.
 *
 * @author ginccc
 */
@DisplayName("DeploymentListener — registration lifecycle")
class DeploymentListenerRegistrationTest {

    private DeploymentListener listener;

    @BeforeEach
    void setUp() {
        listener = new DeploymentListener();
    }

    private static DeploymentEvent event(String agentId, Integer version, Deployment.Status status) {
        return new DeploymentEvent(agentId, version, Deployment.Environment.production, status);
    }

    @Test
    @DisplayName("a registered deployment is discoverable while it is in flight")
    void registeredDeploymentIsDiscoverable() {
        CompletableFuture<Void> registered = listener.registerAgentDeployment("agent-1", 1);

        assertNotNull(registered);
        assertSame(registered, listener.getRegisteredDeploymentEvent("agent-1", 1),
                "a waiter must find the same future the deployer registered");
    }

    @Test
    @DisplayName("an unregistered deployment is not discoverable")
    void unregisteredDeploymentIsNotDiscoverable() {
        assertNull(listener.getRegisteredDeploymentEvent("never-registered", 1));
    }

    @Test
    @DisplayName("registering twice for the same agent+version hands back the same future")
    void repeatedRegistrationIsIdempotent() {
        var first = listener.registerAgentDeployment("agent-1", 1);
        var second = listener.registerAgentDeployment("agent-1", 1);

        assertSame(first, second);
    }

    @Test
    @DisplayName("different versions register independently")
    void versionsAreIndependent() {
        var v1 = listener.registerAgentDeployment("agent-1", 1);
        var v2 = listener.registerAgentDeployment("agent-1", 2);

        assertNotSame(v1, v2);
    }

    @Test
    @DisplayName("a READY event completes the registration and removes it")
    void readyEventCompletesAndRemoves() {
        var registered = listener.registerAgentDeployment("agent-1", 1);

        listener.onDeploymentEvent(event("agent-1", 1, Deployment.Status.READY));

        assertTrue(registered.isDone());
        assertNull(listener.getRegisteredDeploymentEvent("agent-1", 1), "a settled registration must not linger");
    }

    @Test
    @DisplayName("an ERROR event completes it exceptionally and removes it")
    void errorEventCompletesExceptionallyAndRemoves() {
        var registered = listener.registerAgentDeployment("agent-1", 1);

        listener.onDeploymentEvent(event("agent-1", 1, Deployment.Status.ERROR));

        assertTrue(registered.isCompletedExceptionally());
        assertNull(listener.getRegisteredDeploymentEvent("agent-1", 1));
    }

    @Test
    @DisplayName("a registration whose event never arrives expires instead of leaking")
    void unclaimedRegistrationExpires() {
        var registered = listener.registerAgentDeployment("agent-1", 1);

        // Stand in for the TTL firing: any exceptional completion must evict the
        // entry. Asserting the real 5-minute timeout would mean a 5-minute test.
        registered.completeExceptionally(new TimeoutException("simulated TTL"));

        assertNull(listener.getRegisteredDeploymentEvent("agent-1", 1),
                "the map was only ever pruned by an arriving event, so an unclaimed registration stayed for the "
                        + "lifetime of the JVM");
    }

    @Test
    @DisplayName("the registration carries a timeout, so it cannot wait forever")
    void registrationCarriesATimeout() {
        assertTrue(DeploymentListener.REGISTRATION_TTL.toSeconds() > 0,
                "an unbounded registration is exactly the leak this bounds");
    }

    @Test
    @DisplayName("an expired registration does not evict a later one for the same agent")
    void expiryDoesNotEvictALaterRegistration() {
        var first = listener.registerAgentDeployment("agent-1", 1);
        listener.onDeploymentEvent(event("agent-1", 1, Deployment.Status.READY));

        var second = listener.registerAgentDeployment("agent-1", 1);
        assertNotSame(first, second, "precondition: the first was evicted, so this is a fresh registration");

        // The first future's completion callback must not remove the second entry.
        first.complete(null);

        assertSame(second, listener.getRegisteredDeploymentEvent("agent-1", 1),
                "remove(key, future) — a stale completion must not evict a live registration");
    }

    @Test
    @DisplayName("an event for an unregistered deployment is a no-op, not an error")
    void eventForUnregisteredDeploymentIsANoOp() {
        listener.onDeploymentEvent(event("ghost", 7, Deployment.Status.READY));
        listener.onDeploymentEvent(event("ghost", 7, Deployment.Status.ERROR));

        assertNull(listener.getRegisteredDeploymentEvent("ghost", 7));
    }

    @Test
    @DisplayName("a completed registration is awaitable by the waiter that holds it")
    void completedRegistrationIsAwaitable() throws Exception {
        var registered = listener.registerAgentDeployment("agent-1", 1);
        listener.onDeploymentEvent(event("agent-1", 1, Deployment.Status.READY));

        registered.get(5, TimeUnit.SECONDS);
    }

    @Test
    @DisplayName("a failed registration surfaces the failure to its waiter")
    void failedRegistrationSurfacesToWaiter() {
        var registered = listener.registerAgentDeployment("agent-1", 1);
        listener.onDeploymentEvent(event("agent-1", 1, Deployment.Status.ERROR));

        assertThrows(Exception.class, () -> registered.get(5, TimeUnit.SECONDS));
    }
}
