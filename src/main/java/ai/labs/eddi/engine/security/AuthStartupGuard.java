package ai.labs.eddi.engine.security;

import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Startup guard that prevents accidental unauthenticated production
 * deployments.
 * <p>
 * In non-development launch modes, if OIDC is disabled and the operator has not
 * explicitly opted out via {@code eddi.security.allow-unauthenticated=true},
 * startup fails with a clear error message. This ensures operators consciously
 * choose to run without authentication.
 * <p>
 * When the escape hatch IS used, an ERROR is logged once per minute for the
 * lifetime of the process so it remains visible in monitoring.
 *
 * @since 6.0.2
 */
@ApplicationScoped
public class AuthStartupGuard {

    private static final Logger LOGGER = Logger.getLogger(AuthStartupGuard.class);

    @ConfigProperty(name = "quarkus.oidc.tenant-enabled", defaultValue = "false")
    boolean oidcEnabled;

    @ConfigProperty(name = "eddi.security.allow-unauthenticated", defaultValue = "false")
    boolean allowUnauthenticated;

    private volatile boolean warnMode = false;

    void onStart(@Observes StartupEvent event) {
        if (LaunchMode.current() == LaunchMode.DEVELOPMENT) {
            if (!oidcEnabled) {
                LOGGER.info("[SECURITY] Dev mode — OIDC disabled. " + "Set QUARKUS_OIDC_TENANT_ENABLED=true to test with authentication.");
            }
            return;
        }

        // Non-development mode (prod, test profile with prod mode)
        if (!oidcEnabled) {
            if (!allowUnauthenticated) {
                throw new IllegalStateException(
                        "OIDC must be enabled in production. " + "Set QUARKUS_OIDC_TENANT_ENABLED=true and configure your Keycloak realm, "
                                + "or explicitly opt out with EDDI_SECURITY_ALLOW_UNAUTHENTICATED=true. "
                                + "Running without authentication in production is a security risk.");
            }

            // Escape hatch used — log loudly
            warnMode = true;
            LOGGER.error("[SECURITY] ⚠️  OIDC is DISABLED in production mode! "
                    + "All API endpoints are accessible without authentication. "
                    + "This is a security risk. Set QUARKUS_OIDC_TENANT_ENABLED=true for production deployments.");
        }
    }

    /**
     * Periodic warning when running unauthenticated in production. Scheduled via
     * Quarkus @Scheduled to emit an ERROR every 60 seconds.
     */
    @io.quarkus.scheduler.Scheduled(every = "60s")
    void periodicAuthWarning() {
        if (warnMode) {
            LOGGER.error("[SECURITY] ⚠️  REMINDER: OIDC is DISABLED in production. "
                    + "All API endpoints are unauthenticated. Set QUARKUS_OIDC_TENANT_ENABLED=true.");
        }
    }
}
