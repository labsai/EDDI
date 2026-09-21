/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Reports readiness DOWN as soon as {@link GracefulShutdownService} observes a
 * shutdown signal (B3), so the load balancer takes this pod out of rotation
 * BEFORE the drain starts.
 * <p>
 * MicroProfile Health aggregates every {@code @Readiness} check, so this simply
 * joins the existing readiness set (see {@code AgentsReadinessHealthCheck})
 * rather than introducing a parallel readiness mechanism: one DOWN check makes
 * {@code /q/health/ready} DOWN.
 */
@ApplicationScoped
@Readiness
public class ShutdownReadinessHealthCheck implements HealthCheck {

    private final GracefulShutdownService gracefulShutdownService;

    @Inject
    public ShutdownReadinessHealthCheck(GracefulShutdownService gracefulShutdownService) {
        this.gracefulShutdownService = gracefulShutdownService;
    }

    @Override
    public HealthCheckResponse call() {
        var responseBuilder = HealthCheckResponse.named("Graceful shutdown readiness check");
        return gracefulShutdownService.isShuttingDown() ? responseBuilder.down().build() : responseBuilder.up().build();
    }
}
