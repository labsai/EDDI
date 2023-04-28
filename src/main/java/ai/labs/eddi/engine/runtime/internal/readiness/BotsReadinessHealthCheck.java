package ai.labs.eddi.engine.runtime.internal.readiness;

import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
@Readiness
public class BotsReadinessHealthCheck implements HealthCheck {
    private final IBotsReadiness botsReadiness;

    @Inject
    public BotsReadinessHealthCheck(IBotsReadiness botsReadiness) {
        this.botsReadiness = botsReadiness;
    }

    @Override
    public HealthCheckResponse call() {
        var responseBuilder = HealthCheckResponse.named("Bots are ready health check");
        return botsReadiness.isBotsReady() ? responseBuilder.up().build() : responseBuilder.down().build();
    }
}
