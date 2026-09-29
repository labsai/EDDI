/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal.readiness;

import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/**
 * Whether the startup deployment has run, plus which deployed agents are in
 * ERROR.
 *
 * <p>
 * An agent in ERROR is reported as data ({@code agentsInError},
 * {@code agentsInErrorCount}) and does <b>not</b> take the instance out of
 * readiness. Readiness decides whether the load balancer sends traffic here at
 * all, and one broken agent is a reason to fix that agent, not to stop serving
 * every other one — in a multi-agent deployment, DOWN on any ERROR would let a
 * single misconfigured agent (a missing vault secret, a bad workflow) take
 * every replica out of rotation at once. The failed deployments are retried by
 * {@code AgentDeploymentManagement} with a backoff, so a transient failure
 * heals without a restart. Before this, readiness said UP while every agent was
 * in ERROR and nothing anywhere said so.
 * </p>
 */
@ApplicationScoped
@Readiness
public class AgentsReadinessHealthCheck implements HealthCheck {
    /**
     * At most this many deployments are named in the response; the count is always
     * complete.
     */
    static final int MAX_LISTED = 20;

    private final IAgentsReadiness agentsReadiness;

    @Inject
    public AgentsReadinessHealthCheck(IAgentsReadiness agentsReadiness) {
        this.agentsReadiness = agentsReadiness;
    }

    @Override
    public HealthCheckResponse call() {
        var responseBuilder = HealthCheckResponse.named("Agents are ready health check");
        List<String> inError = agentsReadiness.getAgentsInError();
        responseBuilder.withData("agentsInErrorCount", inError.size());
        if (!inError.isEmpty()) {
            responseBuilder.withData("agentsInError", String.join(", ", inError.subList(0, Math.min(MAX_LISTED, inError.size()))));
        }
        return agentsReadiness.isAgentsReady() ? responseBuilder.up().build() : responseBuilder.down().build();
    }
}
