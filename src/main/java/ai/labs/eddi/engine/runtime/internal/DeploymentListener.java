/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.runtime.model.DeploymentEvent;
import ai.labs.eddi.utils.LogSanitizer;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static ai.labs.eddi.engine.model.Deployment.Status.ERROR;
import static ai.labs.eddi.engine.model.Deployment.Status.READY;

@ApplicationScoped
public class DeploymentListener implements IDeploymentListener {

    private static final Logger LOGGER = Logger.getLogger(DeploymentListener.class);

    /**
     * How long a registration may sit unresolved before it self-expires.
     * <p>
     * A registration is removed when its {@link DeploymentEvent} arrives, and the
     * deploy path fires one on both the success and the failure branch — but a
     * process that dies in between, or a runtime that rejects the deployment
     * callable outright, leaves an entry no event will ever claim. Nothing swept
     * this map, so those accumulated for the lifetime of the JVM.
     * <p>
     * Generous by design: this bounds a leak, it is not a deployment SLA. A
     * deployment that takes longer than this has other problems, and a waiter that
     * wants a tighter bound arms its own (see
     * {@code AgentFactory#waitForDeploymentCompletion}, which waits 60s).
     */
    static final Duration REGISTRATION_TTL = Duration.ofMinutes(5);

    private final Map<String, CompletableFuture<Void>> deploymentFutures = new ConcurrentHashMap<>();

    @Override
    public CompletableFuture<Void> getRegisteredDeploymentEvent(String agentId, Integer version) {
        return deploymentFutures.get(createKey(agentId, version));
    }

    /**
     * Registers interest in a deployment, so a concurrent
     * {@code AgentFactory#getAgent} that finds the agent IN_PROGRESS has something
     * to await instead of falling straight through.
     * <p>
     * The returned future self-expires after {@link #REGISTRATION_TTL} and removes
     * itself from the map on <em>any</em> completion — normal, exceptional or
     * timed-out — so the map cannot grow without bound when an event never arrives.
     */
    public CompletableFuture<Void> registerAgentDeployment(String agentId, Integer version) {
        return deploymentFutures.computeIfAbsent(createKey(agentId, version), key -> {
            var future = new CompletableFuture<Void>();
            // orTimeout returns THIS future, so the timeout arms the entry itself
            // rather than a derived one nobody holds.
            future.orTimeout(REGISTRATION_TTL.toSeconds(), TimeUnit.SECONDS);
            future.whenComplete((result, error) -> {
                // remove(key, future), not remove(key): a later registration for the
                // same agent+version must not be evicted by this one's completion.
                if (deploymentFutures.remove(key, future) && error instanceof TimeoutException) {
                    LOGGER.warnf("No deployment event arrived for %s within %s — dropping its registration",
                            LogSanitizer.sanitize(key), REGISTRATION_TTL);
                }
            });
            return future;
        });
    }

    public void onDeploymentEvent(DeploymentEvent event) {
        if (event.status() == READY) {
            String key = createKey(event.agentId(), event.version());
            CompletableFuture<Void> future = deploymentFutures.remove(key);
            if (future != null) {
                future.complete(null); // Mark deployment as successful
            }
        } else if (event.status() == ERROR) {
            String key = createKey(event.agentId(), event.version());
            CompletableFuture<Void> future = deploymentFutures.remove(key);
            if (future != null) {
                future.completeExceptionally(
                        new IllegalStateException("Deployment failed for agentId: " + event.agentId() + ", Version: " + event.version()));
            }
        }
    }

    private static String createKey(String agentId, Integer version) {
        return agentId + ":" + version;
    }
}
