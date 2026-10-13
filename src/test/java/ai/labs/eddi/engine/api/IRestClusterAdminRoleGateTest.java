/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.api;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.GET;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The cluster console: operational metadata may be read by the read-only
 * {@code eddi-viewer}, nothing else may. EDDI has no role hierarchy, so a
 * method-level {@code @RolesAllowed} REPLACES the class gate; this pins the
 * exact set of endpoints that open to the viewer so a new one defaults to
 * admin-only and widening one fails here.
 */
@DisplayName("IRestClusterAdmin role gate")
class IRestClusterAdminRoleGateTest {

    private static final Set<String> VIEWER_READABLE = Set.of("getOverview", "getLeases", "getActivity", "streamActivity", "diagnose",
            "getDeadLetterSummary");

    @Test
    @DisplayName("the class gate is eddi-admin")
    void classGate() {
        RolesAllowed roles = IRestClusterAdmin.class.getAnnotation(RolesAllowed.class);
        assertNotNull(roles);
        assertEquals(Set.of("eddi-admin"), Set.of(roles.value()));
    }

    @Test
    @DisplayName("exactly the operational reads open to eddi-viewer; actions and the dead-letter listing (captured user input) stay admin-only")
    void viewerReadsAreAnExactSet() {
        Set<String> openToViewer = new TreeSet<>();
        for (Method m : IRestClusterAdmin.class.getDeclaredMethods()) {
            RolesAllowed r = m.getAnnotation(RolesAllowed.class);
            if (r == null) {
                continue; // inherits the admin-only class gate
            }
            Set<String> roles = Set.of(r.value());
            if (roles.contains("eddi-viewer")) {
                openToViewer.add(m.getName());
                assertEquals(Set.of("eddi-admin", "eddi-viewer"), roles, m.getName());
                // only reads may open to the viewer
                assertNotNull(m.getAnnotation(GET.class), m.getName() + " is not a GET but is open to the read-only role");
            } else {
                assertEquals(Set.of("eddi-admin"), roles, m.getName());
            }
        }
        assertEquals(new TreeSet<>(VIEWER_READABLE), openToViewer);
    }
}
