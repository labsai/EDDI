/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.rest;

import ai.labs.eddi.secrets.ISecretProvider;
import ai.labs.eddi.secrets.SecretResolver;
import ai.labs.eddi.secrets.VaultGrantImpactAnalyzer;
import ai.labs.eddi.secrets.VaultGrantService;
import ai.labs.eddi.secrets.model.SecretMetadata;
import ai.labs.eddi.secrets.model.SecretReference;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.Principal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code POST /secretstore/secrets/{tenant}/{key}/grant/agents/{agentId}} — the
 * REST face of {@link VaultGrantService}.
 */
@DisplayName("RestSecretStore — append one agent to a grant")
class RestSecretStoreGrantAgentTest {

    private static final String AGENT = "bbbbbbbbbbbbbbbbbbbbbbbb";
    private static final SecretReference REF = new SecretReference("default", "gemini-api-key");

    private VaultGrantService grantService;
    private RestSecretStore rest;

    @BeforeEach
    void setUp() {
        ISecretProvider secretProvider = mock(ISecretProvider.class);
        when(secretProvider.isAvailable()).thenReturn(true);
        VaultGrantImpactAnalyzer analyzer = mock(VaultGrantImpactAnalyzer.class);
        when(analyzer.agentsLosingAccess(any(), any())).thenReturn(new VaultGrantImpactAnalyzer.GrantImpact(List.of(), true));
        rest = new RestSecretStore(secretProvider, mock(SecretResolver.class), analyzer);
        grantService = mock(VaultGrantService.class);
        rest.grantService = grantService;
        SecurityIdentity identity = mock(SecurityIdentity.class);
        Principal principal = () -> "alice";
        when(identity.getPrincipal()).thenReturn(principal);
        rest.identity = identity;
    }

    private static SecretMetadata grant(List<String> agents) {
        return new SecretMetadata("default", "gemini-api-key", null, null, null, "0000000000000000", "d", agents);
    }

    @Test
    @DisplayName("200 with the new grant, changed=true, and the caller recorded as actor")
    void appends() throws Exception {
        when(grantService.grantAgent(REF, AGENT, "alice", "rest", false)).thenReturn(
                new VaultGrantService.AppendResult(grant(List.of("aaaaaaaaaaaaaaaaaaaaaaaa")), grant(List.of("aaaaaaaaaaaaaaaaaaaaaaaa", AGENT)),
                        true));

        Response response = rest.grantAgent("default", "gemini-api-key", AGENT, false);

        assertEquals(200, response.getStatus());
        @SuppressWarnings("unchecked")
        var body = (Map<String, Object>) response.getEntity();
        assertEquals(true, body.get("changed"));
        assertEquals(List.of("aaaaaaaaaaaaaaaaaaaaaaaa", AGENT), body.get("allowedAgents"));
        assertEquals(List.of(), body.get("agentsLosingAccess"));
        // The keyed checksum never crosses the API boundary.
        assertFalse(body.toString().contains("0000000000000000"));
    }

    @Test
    @DisplayName("an unchanged grant (already granted, or open to everyone) answers changed=false")
    void unchanged() throws Exception {
        var open = grant(List.of("*"));
        when(grantService.grantAgent(REF, AGENT, "alice", "rest", false)).thenReturn(new VaultGrantService.AppendResult(open, open, false));

        @SuppressWarnings("unchecked")
        var body = (Map<String, Object>) rest.grantAgent("default", "gemini-api-key", AGENT, false).getEntity();

        assertEquals(false, body.get("changed"));
        assertEquals(true, body.get("grantsAllAgents"));
    }

    @Test
    @DisplayName("dryRun is passed through and reported")
    void dryRun() throws Exception {
        when(grantService.grantAgent(REF, AGENT, "alice", "rest", true))
                .thenReturn(new VaultGrantService.AppendResult(grant(List.of("x")), grant(List.of("x", AGENT)), true));

        @SuppressWarnings("unchecked")
        var body = (Map<String, Object>) rest.grantAgent("default", "gemini-api-key", AGENT, true).getEntity();

        assertEquals(true, body.get("dryRun"));
        verify(grantService).grantAgent(REF, AGENT, "alice", "rest", true);
    }

    @Test
    @DisplayName("a malformed agent id is a 400 and reaches nothing")
    void malformedAgentId() throws Exception {
        assertEquals(400, rest.grantAgent("default", "gemini-api-key", "*", false).getStatus());
        assertEquals(400, rest.grantAgent("default", "gemini-api-key", "../x", false).getStatus());
        verify(grantService, never()).grantAgent(any(), anyString(), any(), anyString(), anyBoolean());
    }

    @Test
    @DisplayName("an unknown secret is a 404")
    void unknownSecret() throws Exception {
        when(grantService.grantAgent(REF, AGENT, "alice", "rest", false)).thenThrow(new ISecretProvider.SecretNotFoundException("nope"));
        assertEquals(404, rest.grantAgent("default", "gemini-api-key", AGENT, false).getStatus());
    }

    @Test
    @DisplayName("a full grant is a 400 with the reason")
    void fullGrant() throws Exception {
        when(grantService.grantAgent(REF, AGENT, "alice", "rest", false)).thenThrow(new IllegalArgumentException("already lists 500"));
        Response response = rest.grantAgent("default", "gemini-api-key", AGENT, false);
        assertEquals(400, response.getStatus());
        assertTrue(response.getEntity().toString().contains("500"));
    }

    @Test
    @DisplayName("an anonymous caller is recorded as no actor rather than a made-up one")
    void anonymousActor() throws Exception {
        SecurityIdentity anonymous = mock(SecurityIdentity.class);
        when(anonymous.isAnonymous()).thenReturn(true);
        rest.identity = anonymous;
        when(grantService.grantAgent(REF, AGENT, null, "rest", false))
                .thenReturn(new VaultGrantService.AppendResult(grant(List.of("x")), grant(List.of("x", AGENT)), true));

        assertEquals(200, rest.grantAgent("default", "gemini-api-key", AGENT, false).getStatus());
        verify(grantService).grantAgent(REF, AGENT, null, "rest", false);
    }
}
