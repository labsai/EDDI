/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.apicalls.IApiCallsStore;
import ai.labs.eddi.configs.connections.IConnectionStore;
import ai.labs.eddi.configs.llm.ILlmStore;
import ai.labs.eddi.configs.mcpcalls.IMcpCallsStore;
import ai.labs.eddi.configs.rag.IRagStore;
import ai.labs.eddi.configs.rag.model.RagConfiguration;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.secrets.model.SecretMetadata;
import ai.labs.eddi.secrets.model.SecretReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@link VaultGrantChecker#findAgentRestrictedReferences}: the references a
 * configuration run outside any agent may not resolve, because their secret is
 * granted to particular agents (security review 2026-11, finding 2).
 */
@DisplayName("VaultGrantChecker — agent-restricted references outside any agent")
class VaultGrantCheckerAgentRestrictedTest {

    private ISecretProvider secretProvider;
    private IAgentStore agentStore;
    private GlobalVariableResolver globalVariableResolver;
    private VaultGrantChecker checker;

    @BeforeEach
    void setUp() {
        secretProvider = mock(ISecretProvider.class);
        when(secretProvider.isAvailable()).thenReturn(true);
        globalVariableResolver = mock(GlobalVariableResolver.class);
        when(globalVariableResolver.resolveValue(anyString(), anyString())).thenAnswer(i -> i.getArgument(0));
        agentStore = mock(IAgentStore.class);
        checker = new VaultGrantChecker(secretProvider, agentStore, mock(IWorkflowStore.class), mock(ILlmStore.class),
                mock(IApiCallsStore.class), mock(IMcpCallsStore.class), mock(IRagStore.class), mock(IConnectionStore.class),
                globalVariableResolver);
    }

    private static RagConfiguration knowledgeBase(String apiKey) {
        var config = new RagConfiguration();
        config.setName("kb");
        config.setEmbeddingProvider("openai");
        config.setEmbeddingParameters(Map.of("apiKey", apiKey));
        return config;
    }

    private void grant(String keyName, List<String> allowedAgents) throws Exception {
        when(secretProvider.getMetadata(eq(new SecretReference("default", keyName))))
                .thenReturn(new SecretMetadata("default", keyName, Instant.EPOCH, null, null, null, null, allowedAgents));
    }

    @Test
    @DisplayName("the exploit: a secret granted to agentA only is reported, with the granted agents that exist")
    void agentRestrictedSecretIsReported() throws Exception {
        grant("agent-a-key", List.of("agentA", "deletedAgent"));
        when(agentStore.getCurrentResourceId("agentA")).thenReturn(mock(IResourceStore.IResourceId.class));
        when(agentStore.getCurrentResourceId("deletedAgent")).thenThrow(new IResourceStore.ResourceNotFoundException("gone"));

        assertEquals(List.of(new VaultGrantChecker.RestrictedReference("${vault:agent-a-key}", List.of("agentA"), false)),
                checker.findAgentRestrictedReferences(knowledgeBase("${vault:agent-a-key}")));
    }

    @Test
    @DisplayName("a wildcard, an empty or an absent grant is not restricted")
    void unrestrictedGrantsAreNot() throws Exception {
        grant("everyone", List.of("*"));
        grant("unset", List.of());

        assertEquals(List.of(), checker.findAgentRestrictedReferences(knowledgeBase("${vault:everyone}")));
        assertEquals(List.of(), checker.findAgentRestrictedReferences(knowledgeBase("${vault:unset}")));
    }

    @Test
    @DisplayName("a reference behind a global variable is found")
    void variableIndirectionIsFollowed() throws Exception {
        grant("agent-a-key", List.of("agentA"));
        when(agentStore.getCurrentResourceId("agentA")).thenReturn(mock(IResourceStore.IResourceId.class));
        when(globalVariableResolver.resolveValue(eq("${vars:kb-key}"), anyString())).thenReturn("${vault:agent-a-key}");

        var restricted = checker.findAgentRestrictedReferences(knowledgeBase("${vars:kb-key}"));
        assertEquals(List.of("${vault:agent-a-key}"), restricted.stream().map(VaultGrantChecker.RestrictedReference::reference).toList());
        // The granted agent survives the indirection: an editor of agentA is let
        // through.
        assertEquals(List.of("agentA"), restricted.getFirst().grantedAgents());
    }

    @Test
    @DisplayName("a grant that cannot be read fails closed; a secret that does not exist is not reported")
    void unreadableGrantFailsClosed() throws Exception {
        when(secretProvider.getMetadata(eq(new SecretReference("default", "flaky"))))
                .thenThrow(new ISecretProvider.SecretProviderException("store down"));
        when(secretProvider.getMetadata(eq(new SecretReference("default", "absent"))))
                .thenThrow(new ISecretProvider.SecretNotFoundException("absent"));

        var restricted = checker.findAgentRestrictedReferences(knowledgeBase("${vault:flaky}"));
        assertEquals(1, restricted.size());
        assertTrue(restricted.get(0).unverifiable());
        assertTrue(restricted.get(0).reference().contains("could not be read"), restricted.get(0).reference());
        assertEquals(List.of(), checker.findAgentRestrictedReferences(knowledgeBase("${vault:absent}")));
    }

    @Test
    @DisplayName("with the vault disabled nothing resolves, so nothing is reported")
    void disabledVaultReportsNothing() throws Exception {
        when(secretProvider.isAvailable()).thenReturn(false);
        grant("agent-a-key", List.of("agentA"));

        assertEquals(List.of(), checker.findAgentRestrictedReferences(knowledgeBase("${vault:agent-a-key}")));
    }
}
