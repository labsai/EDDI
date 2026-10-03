/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime;

import ai.labs.eddi.engine.model.Deployment;

import java.time.Duration;
import java.util.function.Supplier;

/**
 * @author ginccc
 */
public interface IAgentDeploymentManagement {
    void autoDeployAgents() throws AutoDeploymentException;

    /**
     * Cluster mode: a request reached this node for an agent another node has just
     * deployed, before this node's own sweep picked the deployment up. When the
     * deployment store says the agent is deployed in {@code environment}, deploys
     * it here now and waits — at most {@code maxWait} — until {@code resolve} finds
     * it ready. Without it the second node of a round-robin pair answered 404 to
     * the first request after a deploy.
     *
     * @return what {@code resolve} returned once it found the agent, or
     *         {@code null}: not cluster mode, not deployed anywhere, or not ready
     *         in time
     */
    default <T> T awaitClusterDeployment(Deployment.Environment environment, String agentId, Supplier<T> resolve, Duration maxWait) {
        return null;
    }

    class AutoDeploymentException extends Exception {
        public AutoDeploymentException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
