/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.Optional;

/**
 * Operator-facing settings for per-user workspaces, resolved once at startup.
 *
 * <h3>Why enforcement is a separate switch from ownership</h3>
 * {@code eddi.workspaces.enabled} gates <em>enforcement</em> only. Ownership is
 * stamped on every new resource regardless, so an operator can run a release
 * with attribution recorded and nothing filtered, confirm the data looks right,
 * and only then turn enforcement on. Turning it on before ownership has been
 * stamped and backfilled is what would hide people's own work from them.
 *
 * @author ginccc
 */
@ApplicationScoped
public class WorkspaceSettings {

    private static final Logger LOGGER = Logger.getLogger(WorkspaceSettings.class);

    /**
     * {@code eddi.workspaces.legacy-visibility} — admit unowned data to everyone.
     */
    public static final String LEGACY_SHARED = "shared";

    /**
     * {@code eddi.workspaces.legacy-visibility} — admit unowned data to admins
     * only.
     */
    public static final String LEGACY_ADMIN_ONLY = "admin-only";

    private final boolean enabled;
    private final boolean authEnabled;
    private final String groupsClaim;
    private final String legacyVisibility;
    private final Optional<String> defaultSpaceTeam;

    private boolean admitLegacy;

    @Inject
    public WorkspaceSettings(
            @ConfigProperty(name = "eddi.workspaces.enabled", defaultValue = "false") boolean enabled,
            @ConfigProperty(name = "authorization.enabled", defaultValue = "false") boolean authEnabled,
            @ConfigProperty(name = "eddi.workspaces.groups-claim", defaultValue = "groups") String groupsClaim,
            @ConfigProperty(name = "eddi.workspaces.legacy-visibility", defaultValue = LEGACY_SHARED) String legacyVisibility,
            @ConfigProperty(name = "eddi.workspaces.default-space") Optional<String> defaultSpaceTeam) {
        this.enabled = enabled;
        this.authEnabled = authEnabled;
        this.groupsClaim = groupsClaim == null || groupsClaim.isBlank() ? "groups" : groupsClaim.trim();
        this.legacyVisibility = legacyVisibility == null || legacyVisibility.isBlank() ? LEGACY_SHARED : legacyVisibility.trim();
        this.defaultSpaceTeam = defaultSpaceTeam.map(String::trim).filter(s -> !s.isEmpty());
    }

    @PostConstruct
    void validate() {
        if (!LEGACY_SHARED.equalsIgnoreCase(legacyVisibility) && !LEGACY_ADMIN_ONLY.equalsIgnoreCase(legacyVisibility)) {
            // Fail loud rather than silently picking a policy: the two options differ on
            // whether every pre-upgrade agent is visible, which is not a difference to
            // resolve by guessing.
            throw new IllegalStateException("eddi.workspaces.legacy-visibility must be '" + LEGACY_SHARED + "' or '" + LEGACY_ADMIN_ONLY
                    + "', but was '" + legacyVisibility + "'");
        }
        this.admitLegacy = LEGACY_SHARED.equalsIgnoreCase(legacyVisibility);

        if (enabled && !authEnabled) {
            // Without authentication every caller is anonymous, so there is no principal to
            // scope anything to. Enforcing in that state would deny everyone everything, so
            // isEnforcing() reports false — say why, once, rather than leaving an operator
            // to wonder why the flag they set did nothing.
            LOGGER.warn("eddi.workspaces.enabled=true has no effect while authorization.enabled=false: "
                    + "there is no authenticated principal to scope resources to. Enable OIDC to enforce workspaces.");
        }

        if (enabled && authEnabled) {
            LOGGER.infov("Workspace isolation is ENFORCED (legacy-visibility={0}, groups-claim={1}, default-space={2})", legacyVisibility,
                    groupsClaim, defaultSpaceTeam.orElse("<personal>"));
        }
    }

    /**
     * Whether listings and reads are actually filtered.
     * <p>
     * Both switches must be on. Enforcing without authentication would scope every
     * request to the anonymous principal, which owns nothing.
     */
    public boolean isEnforcing() {
        return enabled && authEnabled;
    }

    /**
     * Whether ownership is recorded on newly created resources. Deliberately
     * independent of {@link #isEnforcing()} and true whenever authentication is on,
     * so the data is already correct by the time an operator flips enforcement.
     */
    public boolean isStampingOwnership() {
        return authEnabled;
    }

    /** The JWT claim listing the caller's groups. */
    public String getGroupsClaim() {
        return groupsClaim;
    }

    /** Whether resources with no recorded owner are visible to non-admins. */
    public boolean admitsLegacy() {
        return admitLegacy;
    }

    /**
     * The team every new resource is filed under, when the deployment prefers one
     * shared workspace over per-user ones.
     * <p>
     * Empty — the default — files new resources in the creator's personal space.
     * Setting it to a group name gives a team-first deployment: colleagues see each
     * other's work by default and personal spaces are reached by explicitly moving
     * a resource. Both are defensible; which one is right depends on whether the
     * deployment's users are one team or many tenants of a shared installation.
     */
    public Optional<String> getDefaultSpaceTeam() {
        return defaultSpaceTeam;
    }
}
