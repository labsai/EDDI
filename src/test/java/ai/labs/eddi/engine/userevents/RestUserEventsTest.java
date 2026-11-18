/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.userevents;

import ai.labs.eddi.engine.security.OwnershipValidator;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.inject.Instance;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.sse.Sse;
import jakarta.ws.rs.sse.SseEventSink;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.security.Principal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("RestUserEvents")
class RestUserEventsTest {

    private UserEventHub hub;
    private Instance<ResourceAccessGuard> guardInstance;
    private ResourceAccessGuard guard;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        hub = mock(UserEventHub.class);
        when(hub.isEnabled()).thenReturn(true);
        guard = mock(ResourceAccessGuard.class);
        guardInstance = mock(Instance.class);
        when(guardInstance.isResolvable()).thenReturn(true);
        when(guardInstance.get()).thenReturn(guard);
    }

    private static SecurityIdentity anonymous() {
        var identity = mock(SecurityIdentity.class);
        when(identity.isAnonymous()).thenReturn(true);
        return identity;
    }

    private static SecurityIdentity signedIn(String name, String... roles) {
        var identity = mock(SecurityIdentity.class);
        Principal principal = () -> name;
        when(identity.isAnonymous()).thenReturn(false);
        when(identity.getPrincipal()).thenReturn(principal);
        for (String role : roles) {
            when(identity.hasRole(role)).thenReturn(true);
        }
        return identity;
    }

    @Test
    @DisplayName("an anonymous caller gets 401 when authorization is on, and no stream is opened")
    void anonymousIs401() throws Exception {
        var rest = new RestUserEvents(anonymous(), new OwnershipValidator(true), guardInstance, hub);

        var refused = assertThrows(NotAuthorizedException.class, () -> rest.stream(mock(SseEventSink.class), mock(Sse.class)));

        assertEquals(401, refused.getResponse().getStatus());
        verify(hub, never()).open(any(), any());
    }

    @Test
    @DisplayName("a signed-in caller is scoped by their own principal, roles and team subjects")
    void subscriberFromIdentity() throws Exception {
        when(guard.currentTeamSubjects()).thenReturn(List.of("team:ops"));
        var rest = new RestUserEvents(signedIn("alice", "eddi-approver"), new OwnershipValidator(true), guardInstance, hub);

        rest.stream(mock(SseEventSink.class), mock(Sse.class));

        var captor = ArgumentCaptor.forClass(UserEventHub.Subscriber.class);
        verify(hub).open(captor.capture(), any());
        var subscriber = captor.getValue();
        assertEquals("alice", subscriber.principal());
        assertTrue(subscriber.approver());
        assertFalse(subscriber.admin());
        assertEquals(List.of("team:ops"), subscriber.teamSubjects());
    }

    @Test
    @DisplayName("with authorization off the caller joins the anonymous bucket")
    void authorizationOff() throws Exception {
        var rest = new RestUserEvents(anonymous(), new OwnershipValidator(false), guardInstance, hub);

        rest.stream(mock(SseEventSink.class), mock(Sse.class));

        var captor = ArgumentCaptor.forClass(UserEventHub.Subscriber.class);
        verify(hub).open(captor.capture(), any());
        assertNull(captor.getValue().principal());
        assertTrue(captor.getValue().admin(), "as on every endpoint, everybody is an administrator with authorization off");
    }

    @Test
    @DisplayName("a caller at the connection cap gets 429 with Retry-After")
    void capIs429() throws Exception {
        when(hub.open(any(), any())).thenThrow(new UserEventHub.Refused(UserEventHub.REJECT_USER_LIMIT, "too many"));
        var rest = new RestUserEvents(signedIn("alice"), new OwnershipValidator(true), guardInstance, hub);

        var refused = assertThrows(WebApplicationException.class, () -> rest.stream(mock(SseEventSink.class), mock(Sse.class)));

        assertEquals(429, refused.getResponse().getStatus());
        assertEquals(String.valueOf(RestUserEvents.RETRY_AFTER_SECONDS), refused.getResponse().getHeaderString("Retry-After"));
    }

    @Test
    @DisplayName("a switched-off stream answers 404")
    void disabledIs404() throws Exception {
        when(hub.isEnabled()).thenReturn(false);
        var rest = new RestUserEvents(signedIn("alice"), new OwnershipValidator(true), guardInstance, hub);

        assertThrows(NotFoundException.class, () -> rest.stream(mock(SseEventSink.class), mock(Sse.class)));
        verify(hub, never()).open(any(), any());
    }
}
