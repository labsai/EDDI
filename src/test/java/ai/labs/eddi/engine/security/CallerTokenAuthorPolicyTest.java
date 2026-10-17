/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.ForbiddenException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Who may store {@code ${caller:token}}: the confused-deputy guard from the
 * 2026-10-15 security review (H2).
 */
class CallerTokenAuthorPolicyTest {

    private static final Map<String, Object> WITH_TOKEN = Map.of("headers", Map.of("Authorization", "Bearer ${caller:token}"));
    private static final Map<String, Object> WITH_USER_ID = Map.of("headers", Map.of("X-User", "${caller:userId}"));

    private SecurityIdentity identity;
    private IJsonSerialization jsonSerialization;

    @BeforeEach
    void setUp() throws IOException {
        identity = mock(SecurityIdentity.class);
        jsonSerialization = mock(IJsonSerialization.class);
        var mapper = new ObjectMapper();
        when(jsonSerialization.serialize(any())).thenAnswer(i -> mapper.writeValueAsString(i.getArgument(0)));
    }

    private CallerTokenAuthorPolicy policy(boolean authorizationEnabled, List<String> authorRoles, String... roles) {
        for (String role : roles) {
            when(identity.hasRole(role)).thenReturn(true);
        }
        return new CallerTokenAuthorPolicy(identity, jsonSerialization, authorizationEnabled, authorRoles);
    }

    @Test
    @DisplayName("an editor may not save ${caller:token}: 403 naming the property")
    void editorIsRefused() {
        var refused = assertThrows(ForbiddenException.class,
                () -> policy(true, List.of("eddi-admin"), "eddi-editor").requireMayAuthor(WITH_TOKEN, "httpcalls"));

        assertEquals(403, refused.getResponse().getStatus());
        String error = String.valueOf(((Map<?, ?>) refused.getResponse().getEntity()).get("error"));
        assertTrue(error.contains(CallerTokenAuthorPolicy.AUTHOR_ROLES_PROPERTY), error);
        assertTrue(error.contains("httpcalls"), error);
    }

    @Test
    @DisplayName("an admin may save it")
    void adminIsAllowed() {
        assertDoesNotThrow(() -> policy(true, List.of("eddi-admin"), "eddi-admin").requireMayAuthor(WITH_TOKEN, "httpcalls"));
    }

    @Test
    @DisplayName("operators can widen the author roles")
    void authorRolesAreConfigurable() {
        assertDoesNotThrow(() -> policy(true, List.of(" eddi-admin ", "eddi-editor"), "eddi-editor").requireMayAuthor(WITH_TOKEN, "x"));
    }

    @Test
    @DisplayName("${caller:userId} is not a credential and stays open to editors")
    void userIdIsAllowedForEditors() {
        assertDoesNotThrow(() -> policy(true, List.of("eddi-admin"), "eddi-editor").requireMayAuthor(WITH_USER_ID, "httpcalls"));
    }

    @Test
    @DisplayName("the near-miss spelling without '$' is caught too")
    void bareBraceSpellingIsCaught() {
        assertThrows(ForbiddenException.class,
                () -> policy(true, List.of("eddi-admin"), "eddi-editor").requireMayAuthor("{\"k\":\"{caller:token}\"}", "mcpcalls"));
    }

    @Test
    @DisplayName("an anonymous identity holds no author role, whatever hasRole says")
    void anonymousIsRefused() {
        when(identity.isAnonymous()).thenReturn(true);
        assertThrows(ForbiddenException.class, () -> policy(true, List.of("eddi-admin"), "eddi-admin").requireMayAuthor(WITH_TOKEN, "x"));
    }

    @Test
    @DisplayName("outside a request (no identity to ask) the reference is refused")
    void noRequestContextIsRefused() {
        when(identity.hasRole(any())).thenThrow(new IllegalStateException("no request context"));
        assertThrows(ForbiddenException.class, () -> new CallerTokenAuthorPolicy(identity, jsonSerialization, true, List.of("eddi-admin"))
                .requireMayAuthor(WITH_TOKEN, "x"));
    }

    @Test
    @DisplayName("an unserializable config cannot be inspected and is refused for a non-author")
    void unserializableIsRefused() throws IOException {
        when(jsonSerialization.serialize(any())).thenThrow(new IOException("boom"));
        assertThrows(ForbiddenException.class,
                () -> policy(true, List.of("eddi-admin"), "eddi-editor").requireMayAuthor(new Object(), "x"));
    }

    @Test
    @DisplayName("requireMayRestore: a non-author cannot replace a stored ${caller:token} version; an author or a clean version passes")
    void requireMayRestore() {
        var refused = assertThrows(ForbiddenException.class,
                () -> policy(true, List.of("eddi-admin"), "eddi-editor").requireMayRestore(WITH_TOKEN, "resource 'x'"));
        assertEquals(403, refused.getResponse().getStatus());
        assertTrue(refused.getMessage().contains("resource 'x'"), refused.getMessage());

        assertDoesNotThrow(() -> policy(true, List.of("eddi-admin"), "eddi-editor").requireMayRestore(WITH_USER_ID, "x"));
        assertDoesNotThrow(() -> policy(true, List.of("eddi-admin"), "eddi-editor").requireMayRestore(null, "x"));
        assertDoesNotThrow(() -> policy(false, List.of("eddi-admin"), "eddi-editor").requireMayRestore(WITH_TOKEN, "x"));
        assertDoesNotThrow(() -> policy(true, List.of("eddi-admin"), "eddi-admin").requireMayRestore(WITH_TOKEN, "x"));
    }

    @Test
    @DisplayName("inactive with authorization off, and for a null config")
    void inactiveWhenAuthorizationIsOff() {
        assertDoesNotThrow(() -> policy(false, List.of("eddi-admin")).requireMayAuthor(WITH_TOKEN, "x"));
        verifyNoInteractions(identity);
        assertDoesNotThrow(() -> policy(true, List.of("eddi-admin")).requireMayAuthor(null, "x"));
    }
}
