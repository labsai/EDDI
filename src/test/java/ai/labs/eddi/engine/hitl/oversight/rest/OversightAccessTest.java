/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.hitl.oversight.rest;

import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.engine.security.ConversationAccessGuard;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("oversight REST scoping")
class OversightAccessTest {

    private ResourceAccessGuard guard;
    private SecurityIdentity identity;
    private OversightAccess access;

    @BeforeEach
    void setUp() {
        guard = mock(ResourceAccessGuard.class);
        identity = mock(SecurityIdentity.class);
        access = new OversightAccess(guard, mock(ConversationAccessGuard.class), identity);
    }

    private static String code(WebApplicationException e) {
        return String.valueOf(((Map<?, ?>) e.getResponse().getEntity()).get("error"));
    }

    @Test
    @DisplayName("an editor must name an agent (400 AGENT_ID_REQUIRED) and hold EDIT on it (403)")
    void editorScoped() {
        assertEquals("AGENT_ID_REQUIRED", code(assertThrows(WebApplicationException.class, () -> access.requireReadScope(" "))));
        doThrow(new ForbiddenException()).when(guard).requireAccess(eq("other"), eq(AccessLevel.EDIT), any());
        assertThrows(ForbiddenException.class, () -> access.requireReadScope("other"));
        assertEquals("mine", access.requireReadScope("mine"));
    }

    @Test
    @DisplayName("an approver reads and reviews any agent, but may not change autonomy without EDIT")
    void approver() {
        when(identity.hasRole("eddi-approver")).thenReturn(true);
        assertNull(access.requireReadScope(null));
        assertDoesNotThrow(() -> access.requireReviewer("any"));
        verify(guard, never()).requireAccess(any(), any(), any());
        doThrow(new ForbiddenException()).when(guard).requireAccess(eq("any"), eq(AccessLevel.EDIT), any());
        assertThrows(ForbiddenException.class, () -> access.requireEditor("any"));
    }

    @Test
    @DisplayName("an administrator sees and changes everything")
    void admin() {
        when(guard.seesEverything()).thenReturn(true);
        assertNull(access.requireReadScope(null));
        assertDoesNotThrow(() -> access.requireEditor("any"));
    }

    @Test
    @DisplayName("windows default to the last 30 days and may span at most 365")
    void windows() {
        Instant now = Instant.parse("2026-11-02T00:00:00Z");
        OversightWindow w = OversightWindow.of(null, null, now);
        assertEquals(now.minusSeconds(30L * 86_400), w.from());
        assertEquals(Instant.parse("2026-11-02T00:00:00Z"), OversightWindow.of("2026-10-01", "2026-11-01", now).to());
        assertEquals("WINDOW_TOO_LONG", code(assertThrows(WebApplicationException.class, () -> OversightWindow.of("2025-01-01", "2026-11-01", now))));
        assertEquals("INVALID_PARAMETER", code(assertThrows(WebApplicationException.class, () -> OversightWindow.of("yesterday", null, now))));
    }
}
