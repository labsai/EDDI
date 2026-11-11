/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.health;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.health.HealthPortfolioService.AgentRef;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.net.URI;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("HealthAccess — VIEW for the view, the ratings rule for quality, VIEW-filtered portfolio")
class HealthAccessTest {

    private ResourceAccessGuard guard;
    private IDocumentDescriptorStore descriptors;
    private SecurityIdentity identity;

    @BeforeEach
    void setUp() {
        guard = mock(ResourceAccessGuard.class);
        descriptors = mock(IDocumentDescriptorStore.class);
        identity = mock(SecurityIdentity.class);
    }

    private HealthAccess access(boolean authEnabled, Set<String> roles) {
        when(identity.isAnonymous()).thenReturn(false);
        when(identity.hasRole(anyString())).thenAnswer(inv -> roles.contains(inv.getArgument(0, String.class)));
        return new HealthAccess(guard, descriptors, identity, authEnabled);
    }

    private static DocumentDescriptor descriptor(String id, String name) {
        DocumentDescriptor d = new DocumentDescriptor();
        d.setName(name);
        d.setResource(URI.create("eddi://ai.labs.agent/agentstore/agents/" + id + "?version=1"));
        return d;
    }

    @Test
    @DisplayName("requireView checks VIEW first, then answers the agent's name — or 404 when there is no agent")
    void requireView() throws Exception {
        HealthAccess access = access(true, Set.of("eddi-viewer"));
        when(descriptors.readCurrentDescriptor("a1")).thenReturn(descriptor("a1", "Support"));
        assertEquals("Support", access.requireView("a1"));
        verify(guard).requireAccess("a1", AccessLevel.VIEW, "agent");

        when(descriptors.readCurrentDescriptor("ghost")).thenThrow(new IResourceStore.ResourceNotFoundException("gone"));
        assertThrows(NotFoundException.class, () -> access.requireView("ghost"));

        when(guard.requireAccess("secret", AccessLevel.VIEW, "agent")).thenThrow(new ForbiddenException("Access denied"));
        assertThrows(ForbiddenException.class, () -> access.requireView("secret"));
        verify(descriptors, never()).readCurrentDescriptor("secret");
    }

    @ParameterizedTest(name = "roles {0}, seesEverything {1}, EDIT {2} -> {3}")
    @CsvSource({"eddi-admin, true, false, true", "eddi-editor, false, true, true", "eddi-editor, false, false, false",
            "eddi-viewer, true, true, false", "eddi-user, true, true, false"})
    @DisplayName("quality follows the ratings rule exactly: admin or editor, and EDIT unless seeing everything; viewers never")
    void ratingsRule(String role, boolean seesEverything, boolean edit, boolean expected) {
        HealthAccess access = access(true, Set.of(role));
        when(guard.seesEverything()).thenReturn(seesEverything);
        when(guard.hasAccess("a1", AccessLevel.EDIT)).thenReturn(edit);
        assertEquals(expected, access.mayReadRatings("a1"));
    }

    @Test
    @DisplayName("with authorization off the role part admits everybody, as @RolesAllowed does")
    void authOff() {
        HealthAccess access = access(false, Set.of());
        when(guard.seesEverything()).thenReturn(true);
        assertEquals(true, access.mayReadRatings("a1"));
    }

    @Test
    @DisplayName("the portfolio keeps only agents the caller may VIEW, across pages")
    void viewableAgents() throws Exception {
        HealthAccess access = access(true, Set.of("eddi-viewer"));
        DocumentDescriptor visible = descriptor("6740aaaaaaaaaaaaaaaaaaa1", "Support");
        DocumentDescriptor hidden = descriptor("6740aaaaaaaaaaaaaaaaaaa2", "Secret");
        when(descriptors.readDescriptors(eq("ai.labs.agent"), eq(""), anyInt(), anyInt(), anyBoolean(), any()))
                .thenReturn(List.of(visible, hidden));
        when(guard.canAccess(visible, AccessLevel.VIEW)).thenReturn(true);
        when(guard.canAccess(hidden, AccessLevel.VIEW)).thenReturn(false);
        assertEquals(List.of(new AgentRef("6740aaaaaaaaaaaaaaaaaaa1", "Support")), access.viewableAgents());
    }
}
