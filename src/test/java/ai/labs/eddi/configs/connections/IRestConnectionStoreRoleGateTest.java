/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.connections;

import jakarta.annotation.security.RolesAllowed;
import jakarta.ws.rs.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Who may do what on the connection store, pinned exactly.
 * <p>
 * The split is the point: writes stay admin-only because a connection is an
 * egress channel plus a credential, while the two reads admit an editor because
 * an httpcall author cannot write {@code ${connection:jira}} without knowing
 * that "jira" exists — and a document carries references only. A test that
 * checked the class-level gate alone would pass just as well with the reads
 * closed again, and one that checked the reads alone would pass with the writes
 * opened.
 */
@DisplayName("IRestConnectionStore role gate")
class IRestConnectionStoreRoleGateTest {

    private static final List<String> ADMIN_ONLY = List.of("eddi-admin");
    private static final List<String> ADMIN_OR_EDITOR = List.of("eddi-admin", "eddi-editor");

    @Test
    @DisplayName("the store as a whole is gated to eddi-admin")
    void classLevelGateIsAdminOnly() {
        Path path = IRestConnectionStore.class.getAnnotation(Path.class);
        assertNotNull(path, "IRestConnectionStore is expected to be an addressable JAX-RS resource");
        assertEquals("/connectionstore/connections", path.value());

        RolesAllowed roles = IRestConnectionStore.class.getAnnotation(RolesAllowed.class);
        assertNotNull(roles, "a connection is an egress channel plus a credential; the store must not be open to every authenticated account");
        assertEquals(ADMIN_ONLY, List.of(roles.value()));
    }

    @ParameterizedTest(name = "{0} admits eddi-editor")
    @ValueSource(strings = {"readConnectionDescriptors", "readConnection"})
    @DisplayName("the descriptor listing and a single read admit an editor")
    void readsAdmitEditors(String methodName) {
        RolesAllowed roles = methodNamed(methodName).getAnnotation(RolesAllowed.class);

        assertNotNull(roles, methodName + " must carry its own gate: an editor authoring an httpcall header has to be able to see which "
                + "connections exist, and the Manager's picker needs the same list");
        assertEquals(ADMIN_OR_EDITOR, List.of(roles.value()), methodName);
    }

    @ParameterizedTest(name = "{0} stays admin-only")
    @ValueSource(strings = {"createConnection", "updateConnection", "duplicateConnection", "deleteConnection", "readJsonSchema"})
    @DisplayName("every write, and the schema, inherit the admin-only class gate")
    void writesStayAdminOnly(String methodName) {
        // No method-level annotation at all: a method-level @RolesAllowed REPLACES the
        // class-level one, so the only way to keep these admin-only is to leave them
        // to the class.
        assertNull(methodNamed(methodName).getAnnotation(RolesAllowed.class),
                methodName + " must not override the class-level admin-only gate");
    }

    private static Method methodNamed(String name) {
        return Arrays.stream(IRestConnectionStore.class.getDeclaredMethods()).filter(method -> method.getName().equals(name)).findFirst()
                .orElseThrow(() -> new AssertionError("IRestConnectionStore has no method " + name));
    }
}
