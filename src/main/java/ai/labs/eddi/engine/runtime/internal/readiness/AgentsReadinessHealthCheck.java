package ai.labs.eddi.engine.runtime.internal.readiness;

import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
@Readiness
public class AgentsReadinessHealthCheck implements HealthCheck {
    private final IAgentsReadiness agentsReadiness;

    @Inject
    public AgentsReadinessHealthCheck(IAgentsReadiness agentsReadiness) {
        this.agentsReadiness = agentsReadiness;
    }

    @Override
    public HealthCheckResponse call() {
        var responseBuilder = HealthCheckResponse.named("Agents are ready health check");
        return agentsReadiness.isAgentsReady() ? responseBuilder.up().build() : responseBuilder.down().build();
    }
}
