/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Refuses to start when EDDI's two highest-value surfaces — the MCP server at
 * {@code /mcp} and the secrets vault at {@code /secretstore} — would be
 * reachable without any authentication.
 * <p>
 * {@link AuthStartupGuard} already refuses an unauthenticated production boot,
 * but its escape hatch ({@code eddi.security.allow-unauthenticated=true}) is
 * set by every shipped compose file and k8s manifest, so in practice it never
 * fires. That is tolerable for the conversation API and intolerable for these
 * two: the MCP endpoint exposes agent CRUD, conversation history, user memories
 * and audit trails as tools, and {@code /secretstore} writes the vault, rotates
 * the DEK and offers a reset. Both are declaratively protected
 * ({@code @RolesAllowed}) and both of those checks are no-ops when
 * {@link DisabledAuthController#isAuthorizationEnabled()} returns false.
 * <p>
 * So each gets its own, deliberately narrower opt-out. An operator who wants an
 * unauthenticated demo still gets one; an operator who inherited
 * {@code EDDI_SECURITY_ALLOW_UNAUTHENTICATED=true} from a compose file does not
 * silently ship an open credential store with it.
 * <p>
 * {@link LaunchMode#DEVELOPMENT} and {@link LaunchMode#TEST} are exempt,
 * matching {@link AuthStartupGuard}: OIDC is normally off there and a dev loop
 * that cannot boot is its own kind of security problem.
 *
 * @since 6.3.0
 */
@ApplicationScoped
public class HighValueSurfaceGuard {

    private static final Logger LOGGER = Logger.getLogger(HighValueSurfaceGuard.class);

    /** One unprotected surface: what it is, and the property that permits it. */
    private record Surface(String path, String description, String optOutProperty, boolean optedOut) {
    }

    private final boolean authorizationEnabled;
    private final boolean mcpAllowUnauthenticated;
    private final boolean secretStoreAllowUnauthenticated;

    @Inject
    public HighValueSurfaceGuard(
            @ConfigProperty(name = "authorization.enabled", defaultValue = "false") boolean authorizationEnabled,
            @ConfigProperty(name = "eddi.mcp.allow-unauthenticated", defaultValue = "false") boolean mcpAllowUnauthenticated,
            @ConfigProperty(name = "eddi.secretstore.allow-unauthenticated", defaultValue = "false") boolean secretStoreAllowUnauthenticated) {
        this.authorizationEnabled = authorizationEnabled;
        this.mcpAllowUnauthenticated = mcpAllowUnauthenticated;
        this.secretStoreAllowUnauthenticated = secretStoreAllowUnauthenticated;
    }

    // CDI requires the @Observes parameter for event discovery; not read directly
    void onStart(@Observes StartupEvent event) {
        LaunchMode mode = getLaunchMode();
        if (mode == LaunchMode.DEVELOPMENT || mode == LaunchMode.TEST) {
            return;
        }
        if (authorizationEnabled) {
            return;
        }

        List<Surface> unprotected = unprotectedSurfaces();
        if (!unprotected.isEmpty()) {
            throw new IllegalStateException(buildFailureMessage(unprotected));
        }

        for (Surface surface : optedOutSurfaces()) {
            LOGGER.errorf("[SECURITY] ⚠️  %s is reachable WITHOUT authentication (%s). %s",
                    surface.path(), surface.optOutProperty() + "=true", surface.description());
        }
    }

    /** Surfaces that are open and have not been explicitly opted out. */
    private List<Surface> unprotectedSurfaces() {
        List<Surface> unprotected = new ArrayList<>();
        for (Surface surface : allSurfaces()) {
            if (!surface.optedOut()) {
                unprotected.add(surface);
            }
        }
        return unprotected;
    }

    /** Surfaces that are open because the operator said so. */
    private List<Surface> optedOutSurfaces() {
        List<Surface> optedOut = new ArrayList<>();
        for (Surface surface : allSurfaces()) {
            if (surface.optedOut()) {
                optedOut.add(surface);
            }
        }
        return optedOut;
    }

    private List<Surface> allSurfaces() {
        return List.of(
                new Surface("/mcp",
                        "Every MCP tool — agent CRUD, conversations, user memories, audit trails — is callable by anyone who can reach the port.",
                        "eddi.mcp.allow-unauthenticated", mcpAllowUnauthenticated),
                new Surface("/secretstore",
                        "Vault writes, data-encryption-key rotation and vault reset are callable by anyone who can reach the port.",
                        "eddi.secretstore.allow-unauthenticated", secretStoreAllowUnauthenticated));
    }

    private static String buildFailureMessage(List<Surface> unprotected) {
        var message = new StringBuilder("authorization.enabled=false, so @RolesAllowed is not enforced and the following "
                + "high-value surfaces are open to anyone who can reach the port:\n");
        for (Surface surface : unprotected) {
            message.append("  * ").append(surface.path()).append(" — ").append(surface.description()).append('\n');
        }
        message.append("Fix by one of:\n")
                .append("  * set QUARKUS_OIDC_TENANT_ENABLED=true and configure your Keycloak realm (recommended), or\n");
        for (Surface surface : unprotected) {
            message.append("  * set ").append(surface.optOutProperty().toUpperCase().replace('.', '_').replace('-', '_')).append("=true")
                    .append(" to knowingly expose ").append(surface.path()).append('\n');
        }
        return message.toString();
    }

    /**
     * Returns the current launch mode. Package-private to allow test overrides
     * (LaunchMode.current() is static and not mockable without a wrapper).
     */
    LaunchMode getLaunchMode() {
        return LaunchMode.current();
    }
}
