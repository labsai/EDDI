/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.validation;

import ai.labs.eddi.secrets.ISecretProvider;
import ai.labs.eddi.secrets.ISecretProvider.SecretNotFoundException;
import ai.labs.eddi.secrets.ISecretProvider.SecretProviderException;
import ai.labs.eddi.secrets.model.SecretMetadata;
import ai.labs.eddi.secrets.model.SecretReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("SERVICE judge vault grants")
class ServiceJudgeVaultGrantsTest {

    private ISecretProvider provider;
    private ServiceJudgeVaultGrants grants;

    @BeforeEach
    void setUp() {
        provider = mock(ISecretProvider.class);
        grants = new ServiceJudgeVaultGrants(provider);
    }

    private void secret(String key, List<String> allowedAgents) throws Exception {
        when(provider.getMetadata(new SecretReference("default", key)))
                .thenReturn(new SecretMetadata("default", key, Instant.EPOCH, null, null, "sum", "d", allowedAgents));
    }

    @Test
    @DisplayName("a secret granted to all agents is accepted")
    void wildcardAccepted() throws Exception {
        secret("judge-key", List.of("*"));

        assertTrue(grants.problems("p", Map.of("apiKey", "${vault:judge-key}")).isEmpty());
        assertTrue(grants.firstProblem(Map.of("apiKey", "${vault:judge-key}")).isEmpty());
    }

    @Test
    @DisplayName("a secret granted to specific agents is refused, naming the parameter and the fix")
    void specificAgentsRefused() throws Exception {
        secret("judge-key", List.of("agent-1"));

        List<String> problems = grants.problems("p.parameters", Map.of("apiKey", "${vault:judge-key}"));

        assertEquals(1, problems.size());
        assertTrue(problems.getFirst().startsWith("p.parameters.apiKey "), problems.getFirst());
        assertTrue(problems.getFirst().contains("grant it to all agents"), problems.getFirst());
        assertFalse(problems.getFirst().contains("agent-1"), "the grant list is not echoed");
    }

    @Test
    @DisplayName("a missing secret is refused")
    void missingRefused() throws Exception {
        when(provider.getMetadata(any())).thenThrow(new SecretNotFoundException("nope"));

        List<String> problems = grants.problems("p", Map.of("apiKey", "${vault:gone}"));

        assertEquals(1, problems.size());
        assertTrue(problems.getFirst().contains("does not exist"), problems.getFirst());
    }

    @Test
    @DisplayName("a secret whose grant cannot be read is refused rather than guessed")
    void unreadableRefused() throws Exception {
        when(provider.getMetadata(any())).thenThrow(new SecretProviderException("down"));

        assertEquals(1, grants.problems("p", Map.of("apiKey", "${vault:k}")).size());
    }

    @Test
    @DisplayName("parameters without a vault reference are unchanged and cost no lookup")
    void noReferences() throws Exception {
        assertTrue(grants.problems("p", Map.of("baseUrl", "https://llm.example.com", "temperature", "0", "apiKey", "${vars:judge-key}"))
                .isEmpty());
        assertTrue(grants.problems("p", null).isEmpty());

        verify(provider, never()).getMetadata(any());
    }

    @Test
    @DisplayName("a reference embedded in other text is still checked")
    void embeddedReferenceChecked() throws Exception {
        secret("judge-key", List.of("agent-1"));

        assertEquals(1, grants.problems("p", Map.of("header", "Bearer ${vault:judge-key}")).size());
    }
}
