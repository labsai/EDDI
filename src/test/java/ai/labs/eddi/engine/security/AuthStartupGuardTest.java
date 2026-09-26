/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link AuthStartupGuard}. Plain unit test (not @QuarkusTest).
 * <p>
 * Uses reflection to set @ConfigProperty fields and a Mockito spy to override
 * {@code getLaunchMode()} since {@code LaunchMode.current()} is static.
 *
 * @since 6.0.2
 */
class AuthStartupGuardTest {

    private AuthStartupGuard createGuard(boolean oidcEnabled, boolean allowUnauthenticated,
                                         LaunchMode launchMode)
            throws Exception {
        return createGuard(oidcEnabled, allowUnauthenticated, launchMode, Optional.of("realm_access/roles"), "groups");
    }

    private AuthStartupGuard createGuard(boolean oidcEnabled, boolean allowUnauthenticated,
                                         LaunchMode launchMode, Optional<String> rolesClaimPath,
                                         String workspacesGroupsClaim)
            throws Exception {
        AuthStartupGuard guard = spy(new AuthStartupGuard());

        // Set @ConfigProperty fields via reflection
        setField(guard, "oidcEnabled", oidcEnabled);
        setField(guard, "allowUnauthenticated", allowUnauthenticated);
        setField(guard, "rolesClaimPath", rolesClaimPath);
        setField(guard, "workspacesGroupsClaim", workspacesGroupsClaim);

        // Override getLaunchMode() to control the static LaunchMode.current()
        doReturn(launchMode).when(guard).getLaunchMode();

        return guard;
    }

    private void setField(Object target, String fieldName, Object value) throws Exception {
        // Walk up class hierarchy to find the field (spy creates a subclass)
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }

    @Test
    @DisplayName("Dev mode + OIDC disabled → no exception")
    void devModeOidcDisabled_shouldNotThrow() throws Exception {
        AuthStartupGuard guard = createGuard(false, false, LaunchMode.DEVELOPMENT);

        assertDoesNotThrow(() -> guard.onStart(new StartupEvent()));
    }

    @Test
    @DisplayName("Test mode + OIDC disabled → no exception (tests must not be blocked)")
    void testModeOidcDisabled_shouldNotThrow() throws Exception {
        AuthStartupGuard guard = createGuard(false, false, LaunchMode.TEST);

        assertDoesNotThrow(() -> guard.onStart(new StartupEvent()));
    }

    @Test
    @DisplayName("Prod mode + OIDC disabled + no escape hatch → throws IllegalStateException")
    void prodModeOidcDisabled_shouldThrow() throws Exception {
        AuthStartupGuard guard = createGuard(false, false, LaunchMode.NORMAL);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> guard.onStart(new StartupEvent()));
        assertTrue(ex.getMessage().contains("OIDC must be enabled"), ex.getMessage());
    }

    @Test
    @DisplayName("Prod mode + OIDC disabled + escape hatch → does not throw, sets warnMode")
    void prodModeOidcDisabledWithEscapeHatch_shouldWarnNotThrow() throws Exception {
        AuthStartupGuard guard = createGuard(false, true, LaunchMode.NORMAL);

        assertDoesNotThrow(() -> guard.onStart(new StartupEvent()));

        // Verify warnMode is set (periodic warning would fire)
        Field warnModeField = AuthStartupGuard.class.getDeclaredField("warnMode");
        warnModeField.setAccessible(true);
        assertTrue((boolean) warnModeField.get(guard),
                "warnMode should be true when escape hatch is used");
    }

    @Test
    @DisplayName("Prod mode + OIDC enabled → no exception, no warning")
    void prodModeOidcEnabled_shouldNotThrow() throws Exception {
        AuthStartupGuard guard = createGuard(true, false, LaunchMode.NORMAL);

        assertDoesNotThrow(() -> guard.onStart(new StartupEvent()));

        // warnMode should remain false
        Field warnModeField = AuthStartupGuard.class.getDeclaredField("warnMode");
        warnModeField.setAccessible(true);
        assertFalse((boolean) warnModeField.get(guard),
                "warnMode should be false when OIDC is enabled");
    }

    // ─── role-claim diagnostics (A1) ─────────────────────────────

    /**
     * The 6.4.0 image shipped without {@code quarkus.oidc.roles.role-claim-path}.
     * quarkus-oidc then read roles from its default {@code groups} claim, the
     * seeded {@code eddi} administrator's roles resolved to its group paths, and
     * every {@code @RolesAllowed} endpoint answered 403 with an empty body and
     * <b>no log line at all</b>. Accounts in no group worked, so it looked like one
     * broken account. These cases are the log line that was missing.
     */
    @Test
    @DisplayName("OIDC on + roles claim path unset → names the env var to set")
    void rolesClaimUnset_isReported() throws Exception {
        AuthStartupGuard guard = createGuard(true, false, LaunchMode.NORMAL, Optional.empty(), "groups");

        Optional<String> diagnostic = guard.rolesClaimDiagnostic();

        assertTrue(diagnostic.isPresent(), "an unset roles claim path must not be silent");
        assertTrue(diagnostic.get().contains("QUARKUS_OIDC_ROLES_ROLE_CLAIM_PATH"),
                "the message must name the setting to change: " + diagnostic.get());
        assertTrue(diagnostic.get().contains("403"), diagnostic.get());
    }

    /**
     * The unset-path message named the WORKSPACES claim as quarkus-oidc's default.
     * With {@code eddi.workspaces.groups-claim=teams} it read "quarkus-oidc will
     * read roles from its default 'teams' claim" — a claim quarkus-oidc never reads
     * — and claimed a collision that did not exist, sending the operator after the
     * wrong setting.
     */
    @Test
    @DisplayName("the unset-path message names quarkus-oidc's real default claim, not the workspaces claim")
    void rolesClaimUnset_namesTheRealDefaultClaim() throws Exception {
        AuthStartupGuard guard = createGuard(true, false, LaunchMode.NORMAL, Optional.empty(), "teams");

        String message = guard.rolesClaimDiagnostic().orElseThrow();

        assertTrue(message.contains("default 'groups' claim"), message);
        assertFalse(message.contains("'teams'"), "quarkus-oidc never reads the workspaces claim by default: " + message);
        assertFalse(message.contains("eddi.workspaces.groups-claim"),
                "with the workspaces claim moved off 'groups' there is no collision to report: " + message);

        String colliding = createGuard(true, false, LaunchMode.NORMAL, Optional.empty(), "groups")
                .rolesClaimDiagnostic().orElseThrow();
        assertTrue(colliding.contains("eddi.workspaces.groups-claim"),
                "with both on 'groups' the message must say they collide: " + colliding);
    }

    @Test
    @DisplayName("OIDC on + blank roles claim path → treated as unset")
    void rolesClaimBlank_isReported() throws Exception {
        AuthStartupGuard guard = createGuard(true, false, LaunchMode.NORMAL, Optional.of("   "), "groups");

        assertTrue(guard.rolesClaimDiagnostic().isPresent(), "a blank value is as good as absent");
    }

    @Test
    @DisplayName("OIDC on + roles claim path equal to the workspaces groups claim → reported")
    void rolesClaimCollidesWithWorkspaceGroups_isReported() throws Exception {
        AuthStartupGuard guard = createGuard(true, false, LaunchMode.NORMAL, Optional.of("groups"), "groups");

        Optional<String> diagnostic = guard.rolesClaimDiagnostic();

        assertTrue(diagnostic.isPresent(), "reading roles and workspace groups from one claim is the whole bug");
        assertTrue(diagnostic.get().contains("eddi.workspaces.groups-claim"), diagnostic.get());
    }

    @Test
    @DisplayName("OIDC on + realm_access/roles → silent")
    void rolesClaimConfigured_isSilent() throws Exception {
        AuthStartupGuard guard = createGuard(true, false, LaunchMode.NORMAL, Optional.of("realm_access/roles"), "groups");

        assertTrue(guard.rolesClaimDiagnostic().isEmpty(), "the shipped configuration must not warn");
    }

    @Test
    @DisplayName("OIDC off → silent, whatever the claim path says")
    void oidcDisabled_isSilent() throws Exception {
        AuthStartupGuard guard = createGuard(false, true, LaunchMode.NORMAL, Optional.empty(), "groups");

        assertTrue(guard.rolesClaimDiagnostic().isEmpty(),
                "nothing reads roles when nothing authenticates — a warning here would be noise");
    }

    /**
     * quarkus-oidc accepts a COMMA-SEPARATED list of paths and reads roles from
     * every one of them. "realm_access/roles,groups" therefore collides exactly as
     * badly as a bare "groups" -- while reading, at a glance, like the correct
     * configuration with something harmless appended.
     */
    @Test
    @DisplayName("a comma list that includes the workspaces claim is reported")
    void commaListContainingTheGroupsClaim_isReported() throws Exception {
        AuthStartupGuard guard = createGuard(true, false, LaunchMode.NORMAL,
                Optional.of("realm_access/roles, groups"), "groups");

        Optional<String> diagnostic = guard.rolesClaimDiagnostic();

        assertTrue(diagnostic.isPresent(), "one colliding path in the list is enough to break every role check");
        assertTrue(diagnostic.get().contains("eddi.workspaces.groups-claim"), diagnostic.get());
    }

    @Test
    @DisplayName("a comma list of non-colliding paths is silent")
    void commaListWithoutTheGroupsClaim_isSilent() throws Exception {
        AuthStartupGuard guard = createGuard(true, false, LaunchMode.NORMAL,
                Optional.of("realm_access/roles,resource_access/eddi-backend/roles"), "groups");

        assertTrue(guard.rolesClaimDiagnostic().isEmpty());
    }

    @Test
    @DisplayName("a custom workspaces groups claim moves the collision with it")
    void collisionFollowsACustomWorkspacesClaim() throws Exception {
        // Not "groups": the check must compare the two configured values, not match a
        // hard-coded literal.
        AuthStartupGuard collides = createGuard(true, false, LaunchMode.NORMAL, Optional.of("teams"), "teams");
        AuthStartupGuard clear = createGuard(true, false, LaunchMode.NORMAL, Optional.of("groups"), "teams");

        assertTrue(collides.rolesClaimDiagnostic().isPresent());
        assertTrue(clear.rolesClaimDiagnostic().isEmpty());
    }
}
