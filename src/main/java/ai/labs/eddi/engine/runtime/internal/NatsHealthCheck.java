package ai.labs.eddi.engine.runtime.internal;

import io.quarkus.arc.profile.IfBuildProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.HealthCheckResponseBuilder;
import org.eclipse.microprofile.health.Readiness;

/**
 * Quarkus health check for NATS JetStream connectivity. Reports at
 * {@code /q/health/ready} when messaging type is NATS.
 *
 * @author ginccc
 * @since 6.0.0
 */
@Readiness
@ApplicationScoped
@IfBuildProfile("nats")
public class NatsHealthCheck implements HealthCheck {

    private final NatsConversationCoordinator natsCoordinator;

    @Inject
    public NatsHealthCheck(NatsConversationCoordinator natsCoordinator) {
        this.natsCoordinator = natsCoordinator;
    }

    @Override
    public HealthCheckResponse call() {
        HealthCheckResponseBuilder builder = HealthCheckResponse.named("NATS JetStream");

        if (natsCoordinator.isConnected()) {
            return builder.up().withData("status", natsCoordinator.getConnectionStatus()).build();
        } else {
            return builder.down().withData("status", natsCoordinator.getConnectionStatus()).build();
        }
    }
}
