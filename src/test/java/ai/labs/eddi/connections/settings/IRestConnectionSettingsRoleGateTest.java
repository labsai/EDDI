/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections.settings;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The settings decide where a client secret may be sent and whether a
 * credential may travel in the clear, so both the read and the write stay with
 * the role that already writes connections and the vault — and no method may
 * widen that.
 */
@DisplayName("IRestConnectionSettings role gate")
class IRestConnectionSettingsRoleGateTest {

    @Test
    @DisplayName("the resource is addressable and gated to eddi-admin")
    void classLevelGateIsAdminOnly() {
        Path path = IRestConnectionSettings.class.getAnnotation(Path.class);
        assertNotNull(path);
        assertEquals("/connectionstore/settings", path.value());

        RolesAllowed roles = IRestConnectionSettings.class.getAnnotation(RolesAllowed.class);
        assertNotNull(roles, "these settings widen where credentials may go; they must not be open to every authenticated account");
        assertEquals(List.of("eddi-admin"), List.of(roles.value()));
    }

    @Test
    @DisplayName("no method overrides the class gate")
    void noMethodOverridesTheGate() {
        // A method-level @RolesAllowed REPLACES the class-level one.
        for (Method method : IRestConnectionSettings.class.getDeclaredMethods()) {
            assertNull(method.getAnnotation(RolesAllowed.class), method.getName() + " must not override the admin-only gate");
        }
    }
}
