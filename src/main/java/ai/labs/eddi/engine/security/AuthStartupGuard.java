/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import io.quarkus.scheduler.Scheduled;

import java.util.Arrays;
import java.util.Optional;

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

    /**
     * The claim quarkus-oidc reads roles from when
     * {@code quarkus.oidc.roles.role-claim-path} is unset (the MicroProfile JWT
     * {@code groups} claim), independent of any EDDI setting.
     */
    static final String QUARKUS_OIDC_DEFAULT_ROLES_CLAIM = "groups";

    @ConfigProperty(name = "quarkus.oidc.tenant-enabled", defaultValue = "false")
    boolean oidcEnabled;

    @ConfigProperty(name = "eddi.security.allow-unauthenticated", defaultValue = "false")
    boolean allowUnauthenticated;

    /**
     * Where quarkus-oidc reads roles from. Unset means Quarkus falls back to its
     * own default, the {@code groups} claim — see {@link #rolesClaimDiagnostic()}.
     */
    @ConfigProperty(name = "quarkus.oidc.roles.role-claim-path")
    Optional<String> rolesClaimPath;

    /** The claim EDDI resolves {@code team:<group>} workspaces from. */
    @ConfigProperty(name = "eddi.workspaces.groups-claim", defaultValue = "groups")
    String workspacesGroupsClaim;

    private volatile boolean warnMode = false;

    // CDI requires the @Observes parameter for event discovery; not read directly
    void onStart(@Observes StartupEvent event) {
        LaunchMode mode = getLaunchMode();

        // Runs before the launch-mode branch below: this one matters in every mode
        // that authenticates, and the auth E2E tier runs in TEST.
        rolesClaimDiagnostic().ifPresent(LOGGER::error);

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
    @Scheduled(every = "3600s")
    void periodicAuthWarning() {
        if (warnMode) {
            LOGGER.warn("[SECURITY] ⚠️  REMINDER: OIDC is DISABLED in production. "
                    + "All API endpoints are unauthenticated. Set QUARKUS_OIDC_TENANT_ENABLED=true.");
        }
    }

    /**
     * The diagnostic that was missing when the 6.4.0 image shipped without
     * {@code quarkus.oidc.roles.role-claim-path}.
     * <p>
     * With it unset, quarkus-oidc reads roles from its default {@code groups} claim
     * — the same claim EDDI resolves {@code team:<group>} workspaces from. Any
     * account that belongs to a Keycloak group therefore had its EDDI roles
     * <em>replaced</em> by its group paths, and every {@code @RolesAllowed}
     * endpoint answered <b>403 with an empty body and not one log line</b>.
     * Accounts in no group fell through to {@code realm_access} and worked, so it
     * presented as "one broken account" rather than as a configuration gap — which
     * is what made it cost hours to find.
     * <p>
     * Returns the message to log, or empty when the configuration is sound.
     * Package-private and pure so the branch is assertable without a container.
     *
     * @return the ERROR text, or {@link Optional#empty()} when nothing is wrong
     */
    Optional<String> rolesClaimDiagnostic() {
        if (!oidcEnabled) {
            return Optional.empty();
        }
        String configured = rolesClaimPath == null ? null : rolesClaimPath.filter(p -> !p.isBlank()).orElse(null);
        String groupsClaim = workspacesGroupsClaim == null || workspacesGroupsClaim.isBlank()
                ? "groups"
                : workspacesGroupsClaim.trim();
        if (configured == null) {
            // quarkus-oidc's default is the MP-JWT 'groups' claim whatever EDDI's
            // workspaces claim is called. The message used to print the
            // workspaces claim as that default, so with eddi.workspaces.groups-claim
            // set to anything else it told the operator quarkus-oidc read a claim
            // it never reads.
            String collision = QUARKUS_OIDC_DEFAULT_ROLES_CLAIM.equals(groupsClaim)
                    ? ", which is also the claim EDDI resolves workspaces from (eddi.workspaces.groups-claim)"
                    : "";
            return Optional.of("[SECURITY] quarkus.oidc.roles.role-claim-path is NOT set. quarkus-oidc then reads roles from "
                    + "its default '" + QUARKUS_OIDC_DEFAULT_ROLES_CLAIM + "' claim whenever a token carries one" + collision
                    + " — so every user who belongs to a Keycloak group will have their roles replaced by their group paths "
                    + "and every @RolesAllowed endpoint will answer 403 with an empty body. Set "
                    + "QUARKUS_OIDC_ROLES_ROLE_CLAIM_PATH=realm_access/roles (Keycloak) or the equivalent path for your "
                    + "identity provider.");
        }
        // quarkus-oidc accepts a comma-separated list of paths and reads roles from
        // every one of them, so "realm_access/roles,groups" collides just as badly
        // as a bare "groups" -- and reads perfectly reassuring.
        if (Arrays.stream(configured.split(",")).map(String::trim).anyMatch(groupsClaim::equals)) {
            return Optional.of("[SECURITY] quarkus.oidc.roles.role-claim-path is '" + configured.trim() + "', which reads roles from '" + groupsClaim
                    + "', the claim eddi.workspaces.groups-claim names. Roles and workspace groups will be read from one list, so group membership "
                    + "will be interpreted as roles and @RolesAllowed endpoints will answer 403. Point one of the two elsewhere "
                    + "(QUARKUS_OIDC_ROLES_ROLE_CLAIM_PATH=realm_access/roles for Keycloak).");
        }
        return Optional.empty();
    }

    /**
     * Returns the current launch mode. Package-private to allow test overrides
     * (LaunchMode.current() is static and not mockable without a wrapper).
     */
    LaunchMode getLaunchMode() {
        return LaunchMode.current();
    }
}
