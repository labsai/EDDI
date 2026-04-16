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
 * In production launch mode ({@link LaunchMode#NORMAL}), if OIDC is disabled
 * and the operator has not explicitly opted out via
 * {@code eddi.security.allow-unauthenticated=true}, startup fails with a clear
 * error message. This ensures operators consciously choose to run without
 * authentication.
 * <p>
 * {@link LaunchMode#DEVELOPMENT} and {@link LaunchMode#TEST} are exempt — OIDC
 * is typically disabled during development and in integration tests.
 * <p>
 * When the escape hatch IS used, an ERROR is logged at startup and a WARN
 * reminder is emitted hourly for monitoring visibility.
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

    @SuppressWarnings("unused") // CDI requires the @Observes parameter for event discovery
    void onStart(@Observes StartupEvent event) {
        LaunchMode mode = getLaunchMode();

        if (mode == LaunchMode.DEVELOPMENT || mode == LaunchMode.TEST) {
            if (!oidcEnabled) {
                LOGGER.info("[SECURITY] " + mode.name().toLowerCase() + " mode — OIDC disabled. "
                        + "Set QUARKUS_OIDC_TENANT_ENABLED=true to test with authentication.");
            }
            return;
        }

        // Production mode (LaunchMode.NORMAL)
        if (!oidcEnabled) {
            if (!allowUnauthenticated) {
                throw new IllegalStateException(
                        "OIDC must be enabled in production. " + "Set QUARKUS_OIDC_TENANT_ENABLED=true and configure your Keycloak realm, "
                                + "or explicitly opt out with EDDI_SECURITY_ALLOW_UNAUTHENTICATED=true. "
                                + "Running without authentication in production is a security risk.");
            }

            // Escape hatch used — log loudly at startup
            warnMode = true;
            LOGGER.error("[SECURITY] ⚠️  OIDC is DISABLED in production mode! "
                    + "All API endpoints are accessible without authentication. "
                    + "This is a security risk. Set QUARKUS_OIDC_TENANT_ENABLED=true for production deployments.");
        }
    }

    /**
     * Periodic warning when running unauthenticated in production. Logs at WARN
     * level every hour (not every 60 seconds) to avoid polluting alerting/SIEM with
     * 525k identical ERROR lines per year.
     */
    @io.quarkus.scheduler.Scheduled(every = "3600s")
    void periodicAuthWarning() {
        if (warnMode) {
            LOGGER.warn("[SECURITY] ⚠️  REMINDER: OIDC is DISABLED in production. "
                    + "All API endpoints are unauthenticated. Set QUARKUS_OIDC_TENANT_ENABLED=true.");
        }
    }

    /**
     * Returns the current launch mode. Package-private to allow test overrides
     * (LaunchMode.current() is static and not mockable without a wrapper).
     */
    LaunchMode getLaunchMode() {
        return LaunchMode.current();
    }
}
