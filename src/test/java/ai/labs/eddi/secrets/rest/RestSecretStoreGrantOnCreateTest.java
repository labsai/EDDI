/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.rest;

import ai.labs.eddi.secrets.ISecretProvider;
import ai.labs.eddi.secrets.SecretResolver;
import ai.labs.eddi.secrets.VaultGrantGate;
import ai.labs.eddi.secrets.VaultGrantImpactAnalyzer;
import ai.labs.eddi.secrets.model.SecretMetadata;
import ai.labs.eddi.secrets.model.SecretReference;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * M1 of the 2026-10-15 security review: a secret stored without a grant was
 * granted to every agent by a default three layers down. Under
 * {@code eddi.vault.grant-enforcement=enforce} the store endpoint now refuses a
 * create without an explicit list, and carries the new per-secret
 * {@code allowedHosts}.
 */
class RestSecretStoreGrantOnCreateTest {

    private ISecretProvider secretProvider;
    private SecretResolver secretResolver;
    private VaultGrantImpactAnalyzer grantImpactAnalyzer;

    @BeforeEach
    void setUp() throws Exception {
        secretProvider = mock(ISecretProvider.class);
        secretResolver = mock(SecretResolver.class);
        grantImpactAnalyzer = mock(VaultGrantImpactAnalyzer.class);
        when(secretProvider.isAvailable()).thenReturn(true);
        when(grantImpactAnalyzer.agentsLosingAccess(any(), any())).thenReturn(new VaultGrantImpactAnalyzer.GrantImpact(List.of(), true));
    }

    private RestSecretStore rest(VaultGrantGate.Mode mode) {
        return new RestSecretStore(secretProvider, secretResolver, grantImpactAnalyzer, mode);
    }

    private void noSuchSecret() throws Exception {
        when(secretProvider.getMetadata(any())).thenThrow(new ISecretProvider.SecretNotFoundException("not found"));
    }

    private SecretMetadata existing(List<String> hosts) throws Exception {
        var metadata = new SecretMetadata("default", "k", Instant.EPOCH, null, null, "cs", "desc", List.of("agentOne"), hosts);
        when(secretProvider.getMetadata(any())).thenReturn(metadata);
        return metadata;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> entityOf(Response response) {
        return (Map<String, Object>) response.getEntity();
    }

    @Nested
    @DisplayName("under enforce")
    class Enforce {

        @Test
        @DisplayName("a create without allowedAgents is a 400 that tells the operator to pass a list, and writes nothing")
        void createWithoutGrantRefused() throws Exception {
            noSuchSecret();

            Response resp = rest(VaultGrantGate.Mode.ENFORCE).storeSecret("default", "k", new IRestSecretStore.SecretRequest("v", null, null));

            assertEquals(400, resp.getStatus());
            String error = String.valueOf(entityOf(resp).get("error"));
            assertTrue(error.contains("allowedAgents"), error);
            assertTrue(error.contains("[\"*\"]"), "the wildcard must be offered as the explicit alternative: " + error);
            verify(secretProvider, never()).store(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("an empty list is refused too — it means every agent everywhere else")
        void createWithEmptyGrantRefused() throws Exception {
            noSuchSecret();

            Response resp = rest(VaultGrantGate.Mode.ENFORCE).storeSecret("default", "k",
                    new IRestSecretStore.SecretRequest("v", null, List.of()));

            assertEquals(400, resp.getStatus());
            verify(secretProvider, never()).store(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("an explicit wildcard is accepted: the decision is made out loud")
        void explicitWildcardAccepted() throws Exception {
            noSuchSecret();

            Response resp = rest(VaultGrantGate.Mode.ENFORCE).storeSecret("default", "k",
                    new IRestSecretStore.SecretRequest("v", null, List.of("*")));

            assertEquals(201, resp.getStatus());
            verify(secretProvider).store(any(SecretReference.class), eq("v"), isNull(), eq(List.of("*")), isNull());
        }

        @Test
        @DisplayName("a rotation may omit the list: the stored grant is kept, never widened")
        void rotationKeepsTheStoredGrant() throws Exception {
            existing(null);

            Response resp = rest(VaultGrantGate.Mode.ENFORCE).storeSecret("default", "k", new IRestSecretStore.SecretRequest("v2", null, null));

            assertEquals(200, resp.getStatus());
            verify(secretProvider).store(any(SecretReference.class), eq("v2"), isNull(), isNull(), isNull());
        }
    }

    @Test
    @DisplayName("under warn an omitted list is the wildcard, written out rather than left to a persistence default")
    void warnDefaultsToTheExplicitWildcard() throws Exception {
        noSuchSecret();

        Response resp = rest(VaultGrantGate.Mode.WARN).storeSecret("default", "k", new IRestSecretStore.SecretRequest("v", null, null));

        assertEquals(201, resp.getStatus());
        verify(secretProvider).store(any(SecretReference.class), eq("v"), isNull(), eq(List.of("*")), isNull());
    }

    @Test
    @DisplayName("a malformed grant is refused in every mode")
    void malformedGrantRefusedUnderWarn() throws Exception {
        noSuchSecret();

        Response resp = rest(VaultGrantGate.Mode.OFF).storeSecret("default", "k",
                new IRestSecretStore.SecretRequest("v", null, List.of("not a valid id!")));

        assertEquals(400, resp.getStatus());
        verify(secretProvider, never()).store(any(), any(), any(), any(), any());
    }

    @Nested
    @DisplayName("allowedHosts")
    class AllowedHosts {

        @Test
        @DisplayName("stored with the secret, normalised")
        void storedNormalised() throws Exception {
            noSuchSecret();

            Response resp = rest(VaultGrantGate.Mode.ENFORCE).storeSecret("default", "k",
                    new IRestSecretStore.SecretRequest("v", null, List.of("*"), List.of(" API.OpenAI.com ", "api.openai.com")));

            assertEquals(201, resp.getStatus());
            verify(secretProvider).store(any(SecretReference.class), eq("v"), isNull(), eq(List.of("*")), eq(List.of("api.openai.com")));
        }

        @Test
        @DisplayName("a URL instead of a host is a 400 that names the field")
        void urlRefused() throws Exception {
            noSuchSecret();

            Response resp = rest(VaultGrantGate.Mode.ENFORCE).storeSecret("default", "k",
                    new IRestSecretStore.SecretRequest("v", null, List.of("*"), List.of("https://api.openai.com/v1")));

            assertEquals(400, resp.getStatus());
            assertTrue(String.valueOf(entityOf(resp).get("error")).contains("allowedHosts"));
            verify(secretProvider, never()).store(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("the grant endpoint changes it without the value, and echoes it")
        void grantEndpointUpdatesHosts() throws Exception {
            SecretMetadata before = existing(null);
            var withHosts = new SecretMetadata("default", "k", before.createdAt(), null, null, "cs", "desc", List.of("agentOne"),
                    List.of("api.openai.com:443"));
            // The grant update, which runs second, reads the row the host write left.
            when(secretProvider.updateGrant(any(SecretReference.class), any(), any())).thenReturn(withHosts);
            when(secretProvider.updateAllowedHosts(any(SecretReference.class), eq(List.of("api.openai.com:443")))).thenReturn(withHosts);

            Response resp = rest(VaultGrantGate.Mode.ENFORCE).updateGrant("default", "k", false,
                    new IRestSecretStore.GrantRequest(List.of("agentOne"), null, null, List.of("API.openai.com:443")));

            assertEquals(200, resp.getStatus());
            // Hosts first, then the grant: a failure in between leaves the tighter state.
            var order = inOrder(secretProvider);
            order.verify(secretProvider).updateAllowedHosts(any(SecretReference.class), eq(List.of("api.openai.com:443")));
            order.verify(secretProvider).updateGrant(any(SecretReference.class), eq(List.of("agentOne")), isNull());
            verify(secretProvider, never()).store(any(), any(), any(), any(), any());
            assertEquals(List.of("api.openai.com:443"), entityOf(resp).get("allowedHosts"));
            // Models, embedding stores and MCP clients check the list when they are
            // built and are cached: the eviction is what makes a narrowed list bite.
            verify(secretResolver).invalidateCache(new SecretReference("default", "k"));
        }

        @Test
        @DisplayName("a grant edit that does not mention the hosts leaves them alone, and a dry run writes nothing")
        void hostsKeptWhenOmitted() throws Exception {
            SecretMetadata before = existing(List.of("api.openai.com"));
            when(secretProvider.updateGrant(any(SecretReference.class), any(), any())).thenReturn(before);

            Response resp = rest(VaultGrantGate.Mode.ENFORCE).updateGrant("default", "k", false,
                    new IRestSecretStore.GrantRequest(List.of("agentOne"), null));
            assertEquals(200, resp.getStatus());
            verify(secretProvider, never()).updateAllowedHosts(any(), any());
            verify(secretResolver, never()).invalidateCache(any());
            assertEquals(List.of("api.openai.com"), entityOf(resp).get("allowedHosts"));

            Response dryRun = rest(VaultGrantGate.Mode.ENFORCE).updateGrant("default", "k", true,
                    new IRestSecretStore.GrantRequest(List.of("agentOne"), null, null, List.of("other.example")));
            assertEquals(200, dryRun.getStatus());
            assertEquals(List.of("other.example"), entityOf(dryRun).get("allowedHosts"));
            verify(secretProvider, never()).updateAllowedHosts(any(), any());
        }
    }
}
