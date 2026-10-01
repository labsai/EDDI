/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the one line that decides whether an authenticated administrator can do
 * anything at all.
 * <p>
 * {@code quarkus.oidc.roles.role-claim-path} is a single property with no
 * symptom when it is missing. Unset, quarkus-oidc reads roles from its own
 * default {@code groups} claim — which is also
 * {@code eddi.workspaces.groups-claim}'s default — so any account belonging to
 * a Keycloak group has its EDDI roles <em>replaced</em> by its group paths and
 * every {@code @RolesAllowed} endpoint answers <b>403 with an empty body and no
 * log line</b>. Accounts in no group fall through to {@code realm_access} and
 * behave normally, so the breakage presents as "this one account is broken"
 * rather than as a configuration gap.
 * <p>
 * The 6.4.0 image shipped without it, which cost hours of a pilot rehearsal to
 * diagnose. {@link ai.labs.eddi.engine.security.AuthStartupGuard} now says so
 * loudly at runtime; this test is the half that stops it being dropped from the
 * source in the first place, so the runtime warning stays a warning about an
 * operator override rather than about us.
 *
 * @since 6.4.1
 */
@DisplayName("OIDC roles claim configuration")
class OidcRolesClaimConfigTest {

    private static final String ROLE_CLAIM_PATH = "quarkus.oidc.roles.role-claim-path";
    private static final String WORKSPACES_GROUPS_CLAIM = "eddi.workspaces.groups-claim";

    /**
     * Read from the source tree, not the classpath:
     * {@code src/test/resources/application.properties} shadows the main file for
     * tests. The file below is the artefact these assertions exist to protect.
     */
    private static Properties applicationProperties() throws Exception {
        var path = Path.of(System.getProperty("basedir", "."))
                .resolve("src/main/resources/application.properties");
        assertTrue(Files.isRegularFile(path), "Expected the application config at " + path);

        var properties = new Properties();
        try (Reader in = Files.newBufferedReader(path)) {
            properties.load(in);
        }
        return properties;
    }

    @Test
    @DisplayName("the roles claim path is configured")
    void roleClaimPathIsConfigured() throws Exception {
        String value = applicationProperties().getProperty(ROLE_CLAIM_PATH);

        assertNotNull(value,
                ROLE_CLAIM_PATH + " is missing. Without it quarkus-oidc reads roles from the 'groups' claim and every "
                        + "@RolesAllowed endpoint answers an empty 403 for any user who belongs to a Keycloak group.");
        assertFalse(value.isBlank(), ROLE_CLAIM_PATH + " is blank, which is the same as absent");
    }

    /**
     * The shipped realm is Keycloak, whose roles live at
     * {@code realm_access/roles}. Pinning the value (not merely its presence) is
     * deliberate: a well-meaning edit to {@code groups} or {@code roles} would
     * reintroduce exactly the failure this property exists to prevent, and would
     * still pass a presence-only check.
     */
    @Test
    @DisplayName("the roles claim path points at Keycloak's realm roles")
    void roleClaimPathPointsAtRealmAccess() throws Exception {
        assertEquals("realm_access/roles", applicationProperties().getProperty(ROLE_CLAIM_PATH),
                "the realms this project ships (helm/eddi/files/eddi-realm.json and k8s/overlays/auth/) put roles under "
                        + "realm_access/roles");
    }

    /**
     * The collision, stated as an assertion. EDDI resolves {@code team:<group>}
     * workspaces from {@code eddi.workspaces.groups-claim} (default
     * {@code groups}), and quarkus-oidc would read roles from the same list if the
     * two ever named it.
     */
    @Test
    @DisplayName("roles and workspace groups are read from different claims")
    void rolesAndWorkspaceGroupsDoNotShareAClaim() throws Exception {
        var properties = applicationProperties();
        String rolePath = properties.getProperty(ROLE_CLAIM_PATH);
        String groupsClaim = properties.getProperty(WORKSPACES_GROUPS_CLAIM, "groups");

        assertNotEquals(groupsClaim, rolePath,
                "roles and workspace groups would be read from one list: group membership becomes roles, and every "
                        + "@RolesAllowed endpoint answers 403");
    }
}
