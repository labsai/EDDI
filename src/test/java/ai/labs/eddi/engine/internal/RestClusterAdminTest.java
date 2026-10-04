/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.engine.cluster.ClusterUnavailableException;
import ai.labs.eddi.engine.cluster.admin.ClusterActivityLog;
import ai.labs.eddi.engine.cluster.admin.ClusterAdminService;
import ai.labs.eddi.engine.model.ClusterAdminModels.ActionResult;
import ai.labs.eddi.engine.model.ClusterAdminModels.BulkRequest;
import ai.labs.eddi.engine.model.ClusterAdminModels.ReleaseRequest;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.Principal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("RestClusterAdmin")
class RestClusterAdminTest {

    private ClusterAdminService service;
    private RestClusterAdmin rest;

    @BeforeEach
    void setUp() {
        service = mock(ClusterAdminService.class);
        SecurityIdentity identity = mock(SecurityIdentity.class);
        Principal principal = () -> "ops-alice";
        when(identity.getPrincipal()).thenReturn(principal);
        rest = new RestClusterAdmin(service, mock(ClusterActivityLog.class), identity);
    }

    @Test
    @DisplayName("every action carries the caller's name for the audit entry")
    void actorIsTheCaller() {
        when(service.forceRelease("conv1", 7L, "ops-alice")).thenReturn(new ActionResult("lease.release", "RELEASED", "", Map.of()));
        assertEquals("RELEASED", rest.releaseLease("conv1", new ReleaseRequest(7L)).outcome());
        rest.discardDeadLetters(new BulkRequest(List.of("1")));
        verify(service).discard(List.of("1"), "ops-alice");
        rest.drainNode("n2");
        verify(service).drain("n2", true, "ops-alice");
        rest.undrainNode("n2");
        verify(service).drain("n2", false, "ops-alice");
    }

    @Test
    @DisplayName("a refusal answers its status with a code; NATS unreachable is 503")
    void refusals() {
        when(service.drain(anyString(), anyBoolean(), anyString()))
                .thenThrow(new ClusterAdminService.ActionRefusedException(409, "LAST_NODE", "last node"));
        WebApplicationException refused = assertThrows(WebApplicationException.class, () -> rest.drainNode("n1"));
        assertEquals(409, refused.getResponse().getStatus());
        assertEquals("LAST_NODE", ((Map<?, ?>) refused.getResponse().getEntity()).get("code"));

        when(service.leases(any(), anyBoolean(), anyInt())).thenThrow(new ClusterUnavailableException("down"));
        assertEquals(503, assertThrows(WebApplicationException.class, () -> rest.getLeases(null, false, 10)).getResponse().getStatus());
    }

    @Test
    @DisplayName("blank or oversized ids are rejected before the service is called")
    void ids() {
        assertThrows(IllegalArgumentException.class, () -> rest.diagnose(" "));
        assertThrows(IllegalArgumentException.class, () -> rest.releaseLease("x".repeat(201), null));
        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("the dead-letter listing asks for the captured input; it is the admin-only endpoint")
    void listingIncludesInput() {
        rest.getDeadLetters(50, null, "fenced", null, null, null, null, null);
        verify(service).deadLetters(eq(50), isNull(), any(), eq(true));
    }
}
