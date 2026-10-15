/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.setup;

import ai.labs.eddi.engine.api.IRestAgentAdministration;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.model.DeploymentFailure;
import ai.labs.eddi.engine.runtime.client.factory.IRestInterfaceFactory;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.engine.setup.AgentSetupService.AgentSetupForbiddenException;
import ai.labs.eddi.secrets.ISecretProvider;
import ai.labs.eddi.secrets.VaultGrantChecker;
import ai.labs.eddi.secrets.VaultGrantGate;
import ai.labs.eddi.secrets.VaultGrantService;
import ai.labs.eddi.secrets.model.SecretMetadata;
import ai.labs.eddi.secrets.model.SecretReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@code grantReferencedSecrets} — the admin opt-in that settles a new agent's
 * grants between create and deploy — and the structured failure a setup without
 * it now returns.
 */
@DisplayName("AgentSetupService — grantReferencedSecrets and the deploy failure")
class AgentSetupGrantReferencedSecretsTest {

    private static final String AGENT_ID = "0123456789abcdef01234567";

    private IRestAgentAdministration agentAdmin;
    private ResourceAccessGuard guard;
    private VaultGrantChecker checker;
    private VaultGrantService grantService;
    private AgentSetupService service;

    @BeforeEach
    void setUp() {
        agentAdmin = mock(IRestAgentAdministration.class);
        service = new AgentSetupService(mock(IRestInterfaceFactory.class), agentAdmin, mock(ISecretProvider.class), "http://localhost:11434");
        guard = mock(ResourceAccessGuard.class);
        checker = mock(VaultGrantChecker.class);
        grantService = mock(VaultGrantService.class);
        service.resourceAccessGuard = guard;
        service.vaultGrantGate = new VaultGrantGate(checker, "enforce");
        service.vaultGrantService = grantService;
    }

    @Test
    @DisplayName("a non-admin asking for it is refused before anything is created")
    void nonAdminRefused() {
        when(guard.isAdmin()).thenReturn(false);
        var request = new SetupAgentRequest("A", "p", "anthropic", null, "k", null, null, null, null, null, null, null, false, null, null, null,
                true);

        assertThrows(AgentSetupForbiddenException.class, () -> service.setupAgent(request));
        verifyNoInteractions(agentAdmin);
    }

    @Test
    @DisplayName("the api-agent path refuses a non-admin too")
    void nonAdminRefusedApiAgent() {
        when(guard.isAdmin()).thenReturn(false);
        var request = new CreateApiAgentRequest("A", "p", "{}", "anthropic", null, "k", null, null, null, null, null, false, null, null, null, null,
                null, null, null, true);

        assertThrows(AgentSetupForbiddenException.class, () -> service.createApiAgent(request));
    }

    @Test
    @DisplayName("without the flag nobody is checked; with it an admin passes")
    void adminPasses() {
        assertDoesNotThrow(() -> service.requireAdminForGrants(null));
        assertDoesNotThrow(() -> service.requireAdminForGrants(false));
        when(guard.isAdmin()).thenReturn(true);
        assertDoesNotThrow(() -> service.requireAdminForGrants(true));
    }

    @Test
    @DisplayName("the flag is a JSON field of both requests, and off unless sent")
    void jsonField() throws Exception {
        var mapper = new ObjectMapper();
        assertEquals(Boolean.TRUE, mapper.readValue("{\"agentName\":\"a\",\"systemPrompt\":\"p\",\"grantReferencedSecrets\":true}",
                SetupAgentRequest.class).grantReferencedSecrets());
        assertEquals(null, mapper.readValue("{\"agentName\":\"a\",\"systemPrompt\":\"p\"}", SetupAgentRequest.class).grantReferencedSecrets());
        assertEquals(Boolean.TRUE,
                mapper.readValue("{\"agentName\":\"a\",\"systemPrompt\":\"p\",\"openApiSpec\":\"{}\",\"grantReferencedSecrets\":true}",
                        CreateApiAgentRequest.class).grantReferencedSecrets());
    }

    @Test
    @DisplayName("grants exactly the ungranted, restricted, referenced secrets — and reports what changed")
    void grantsOnlyUngranted() throws Exception {
        when(guard.currentPrincipal()).thenReturn("alice");
        // The checker reports only real violations: a "*" secret is never among them.
        when(checker.findUngrantedReferences(AGENT_ID, 1)).thenReturn(List.of("${vault:gemini-api-key}", "${vault:team-a/search-key}"));
        var meta = new SecretMetadata("default", "k", null, null, null, null, null, List.of("x"));
        when(grantService.grantAgent(new SecretReference("default", "gemini-api-key"), AGENT_ID, "alice", "setup", false))
                .thenReturn(new VaultGrantService.AppendResult(meta, meta, true));
        when(grantService.grantAgent(new SecretReference("team-a", "search-key"), AGENT_ID, "alice", "setup", false))
                .thenReturn(new VaultGrantService.AppendResult(meta, meta, false));
        Map<String, Object> resources = new HashMap<>();

        List<String> granted = service.grantReferencedSecrets(AGENT_ID, 1, resources);

        assertEquals(List.of("${vault:gemini-api-key}"), granted);
        assertFalse(resources.containsKey("grantWarning"));
    }

    @Test
    @DisplayName("nothing ungranted means no grant call at all")
    void nothingToGrant() throws Exception {
        when(checker.findUngrantedReferences(AGENT_ID, 1)).thenReturn(List.of());

        assertTrue(service.grantReferencedSecrets(AGENT_ID, 1, new HashMap<>()).isEmpty());
        verify(grantService, never()).grantAgent(any(), anyString(), any(), anyString(), anyBoolean());
    }

    @Test
    @DisplayName("a grant that fails is reported, not thrown — the deploy names what is still missing")
    void failedGrantReported() throws Exception {
        when(checker.findUngrantedReferences(AGENT_ID, 1)).thenReturn(List.of("${vault:gemini-api-key}", "unreadable connection crm"));
        when(grantService.grantAgent(any(), eq(AGENT_ID), any(), eq("setup"), eq(false)))
                .thenThrow(new ISecretProvider.SecretProviderException("db down"));
        Map<String, Object> resources = new HashMap<>();

        assertTrue(service.grantReferencedSecrets(AGENT_ID, 1, resources).isEmpty());
        String warning = (String) resources.get("grantWarning");
        assertTrue(warning.contains("${vault:gemini-api-key}"), warning);
        assertTrue(warning.contains("unreadable connection crm"), warning);
    }

    @Test
    @DisplayName("without the flag, a refused deploy returns the failure and points at THIS agent, not a new setup")
    void refusedDeployCarriesFailure() {
        var failure = new VaultGrantGate.GrantCheck(VaultGrantGate.Mode.ENFORCE, List.of("${vault:gemini-api-key}"), true).toFailure(AGENT_ID, 1);
        var body = new LinkedHashMap<String, Object>();
        body.put("status", "ERROR");
        body.put("error", failure.message());
        body.put("failure", failure);
        when(agentAdmin.deployAgent(Deployment.Environment.production, AGENT_ID, 1, true, true)).thenReturn(Response.ok(body).build());

        Map<String, Object> result = service.deployAndWait(Deployment.Environment.production, AGENT_ID, 1);

        assertEquals(false, result.get("deployed"));
        assertSame(failure, result.get("deploymentFailure"));
        String warning = (String) result.get("deployWarning");
        assertTrue(warning.contains(AGENT_ID), warning);
        assertTrue(warning.contains("do not run the setup again"), warning);
        assertTrue(((DeploymentFailure) result.get("deploymentFailure")).isGrantMissing());
    }
}
