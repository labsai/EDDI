/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import ai.labs.eddi.engine.a2a.A2AModels.JsonRpcRequest;
import ai.labs.eddi.engine.a2a.RestA2AEndpoint;
import ai.labs.eddi.engine.memory.rest.IRestConversationStore;
import ai.labs.eddi.modules.nlp.IRestSemanticParser;
import jakarta.annotation.security.RolesAllowed;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the declarative role gates that these findings added. REST has no role
 * hierarchy, so each surface must enumerate the exact roles it admits — a
 * missing annotation is a silent authorization hole, which is what these
 * assertions pin.
 */
@DisplayName("Security access-control annotations")
class SecurityAccessControlAnnotationsTest {

    private static Set<String> rolesOf(RolesAllowed annotation) {
        assertNotNull(annotation, "expected @RolesAllowed to be present");
        return Set.of(annotation.value());
    }

    @Test
    @DisplayName("Finding 1: active-conversation endpoints are role-gated")
    void activeConversationEndpointsRoleGated() throws Exception {
        var get = IRestConversationStore.class.getMethod("getActiveConversations", String.class, Integer.class);
        var end = IRestConversationStore.class.getMethod("endActiveConversations", List.class, String.class);

        // Exactly the operator tier — the same contract RestConversationStoreTest
        // pins; agent EDIT (under workspace enforcement) is checked in the resource.
        var operatorTier = Set.of("eddi-admin", "eddi-editor");
        assertEquals(operatorTier, rolesOf(get.getAnnotation(RolesAllowed.class)));
        assertEquals(operatorTier, rolesOf(end.getAnnotation(RolesAllowed.class)));
    }

    @Test
    @DisplayName("Finding 7: the semantic parser endpoint is role-gated")
    void semanticParserRoleGated() {
        var roles = rolesOf(IRestSemanticParser.class.getAnnotation(RolesAllowed.class));
        assertTrue(roles.contains("eddi-admin"));
        assertTrue(roles.contains("eddi-editor"));
    }

    @Test
    @DisplayName("Finding 9: the A2A JSON-RPC endpoint requires a real role")
    void a2aJsonRpcRoleGated() throws Exception {
        var handleJsonRpc = RestA2AEndpoint.class.getMethod("handleJsonRpc", String.class, JsonRpcRequest.class);
        var roles = rolesOf(handleJsonRpc.getAnnotation(RolesAllowed.class));
        assertTrue(roles.contains("eddi-user"), "a role-less realm user must not reach A2A tasks/send");
    }
}
