/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets;

import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.apicalls.IApiCallsStore;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.llm.ILlmStore;
import ai.labs.eddi.configs.mcpcalls.IMcpCallsStore;
import ai.labs.eddi.configs.mcpcalls.model.McpCallsConfiguration;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.secrets.model.SecretMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code SecretMetadata.allowedAgents} was documented as "for visibility only —
 * enforcement is via configuration authorship". These tests pin it actually
 * restricting something.
 */
@DisplayName("VaultGrantChecker — allowedAgents is enforced at deploy time")
class VaultGrantCheckerTest {

    private static final String VAULT_REF = "${vault:setup.parent.1.apiKey}";

    // Real ObjectId-shaped ids: RestUtilities.extractResourceId requires >=18 hex
    // characters and returns a null id for anything shorter, so a toy "w1"/"l1"
    // would make the scanner silently find nothing and every test pass vacuously.
    private static final String WORKFLOW_ID = "5f2a1b3c4d5e6f7a8b9c0d1e";
    private static final String LLM_ID = "6a1b2c3d4e5f6a7b8c9d0e1f";

    private ISecretProvider secretProvider;
    private IWorkflowStore workflowStore;
    private ILlmStore llmStore;
    private IApiCallsStore apiCallsStore;
    private IMcpCallsStore mcpCallsStore;
    private ai.labs.eddi.configs.agents.IAgentStore agentStore;
    private VaultGrantChecker checker;

    @BeforeEach
    void setUp() throws Exception {
        secretProvider = mock(ISecretProvider.class);
        workflowStore = mock(IWorkflowStore.class);
        llmStore = mock(ILlmStore.class);
        when(secretProvider.isAvailable()).thenReturn(true);

        apiCallsStore = mock(IApiCallsStore.class);
        mcpCallsStore = mock(IMcpCallsStore.class);

        agentStore = mock(ai.labs.eddi.configs.agents.IAgentStore.class);
        checker = new VaultGrantChecker(secretProvider, agentStore, workflowStore, llmStore, apiCallsStore, mcpCallsStore,
                mock(ai.labs.eddi.configs.rag.IRagStore.class));
    }

    /**
     * An agent whose single LLM config carries {@link #VAULT_REF} as its apiKey.
     */
    private AgentConfiguration agentReferencingTheVault() throws Exception {
        var agent = new AgentConfiguration();
        agent.setWorkflows(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + WORKFLOW_ID + "?version=1")));

        var step = new WorkflowConfiguration.WorkflowStep();
        step.setType(URI.create("eddi://ai.labs.llm"));
        var stepConfig = new LinkedHashMap<String, Object>();
        stepConfig.put("uri", "eddi://ai.labs.llm/llmstore/llms/" + LLM_ID + "?version=1");
        step.setConfig(stepConfig);

        var workflow = new WorkflowConfiguration();
        workflow.setWorkflowSteps(List.of(step));
        when(workflowStore.read(eq(WORKFLOW_ID), anyInt())).thenReturn(workflow);

        var task = new LlmConfiguration.Task();
        task.setType("anthropic");
        task.setParameters(new LinkedHashMap<>(Map.of("apiKey", VAULT_REF, "modelName", "claude-sonnet-4-6")));
        when(llmStore.read(eq(LLM_ID), anyInt())).thenReturn(new LlmConfiguration(List.of(task)));

        return agent;
    }

    /**
     * An agent with one workflow step of {@code stepType} pointing at
     * {@code configId}.
     */
    private AgentConfiguration agentWithStep(String stepType, String configId) throws Exception {
        var agent = new AgentConfiguration();
        agent.setWorkflows(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + WORKFLOW_ID + "?version=1")));

        var step = new WorkflowConfiguration.WorkflowStep();
        step.setType(URI.create("eddi://" + stepType));
        var stepConfig = new LinkedHashMap<String, Object>();
        stepConfig.put("uri", "eddi://" + stepType + "/store/things/" + configId + "?version=1");
        step.setConfig(stepConfig);

        var workflow = new WorkflowConfiguration();
        workflow.setWorkflowSteps(List.of(step));
        when(workflowStore.read(eq(WORKFLOW_ID), anyInt())).thenReturn(workflow);
        return agent;
    }

    private void givenGrant(List<String> allowedAgents) throws Exception {
        when(secretProvider.getMetadata(any())).thenReturn(new SecretMetadata("default", "setup.parent.1.apiKey", Instant.now(), null, null,
                "checksum", "desc", allowedAgents));
    }

    @Nested
    @DisplayName("a scoped grant")
    class ScopedGrant {

        @Test
        @DisplayName("an agent NOT on the list is reported")
        void ungrantedAgentIsReported() throws Exception {
            givenGrant(List.of("agent-owner"));

            List<String> violations = checker.findUngrantedReferences(agentReferencingTheVault(), "some-other-agent");

            assertEquals(List.of(VAULT_REF), violations,
                    "an operator who scoped a secret to one agent must not silently get no enforcement");
        }

        @Test
        @DisplayName("the agent ON the list is allowed")
        void grantedAgentIsAllowed() throws Exception {
            givenGrant(List.of("agent-owner"));

            assertTrue(checker.findUngrantedReferences(agentReferencingTheVault(), "agent-owner").isEmpty());
        }
    }

    @Nested
    @DisplayName("shapes that mean 'not restricted'")
    class Unrestricted {

        @Test
        @DisplayName("the wildcard the setup wizard writes allows everyone")
        void wildcardAllowsEveryone() throws Exception {
            givenGrant(List.of("*"));

            assertTrue(checker.findUngrantedReferences(agentReferencingTheVault(), "any-agent").isEmpty(),
                    "every key AgentSetupService vaults carries the wildcard — enforcing against it would break every deployment");
        }

        @Test
        @DisplayName("an empty or absent list allows everyone")
        void emptyAndNullAllowEveryone() throws Exception {
            givenGrant(List.of());
            assertTrue(checker.findUngrantedReferences(agentReferencingTheVault(), "any-agent").isEmpty());

            givenGrant(null);
            assertTrue(checker.findUngrantedReferences(agentReferencingTheVault(), "any-agent").isEmpty());
        }
    }

    @Nested
    @DisplayName("uncertainty never becomes a violation")
    class FailOpenOnUncertainty {

        /**
         * A deployment gate that fires on a transient store failure is worse than the
         * hole it closes.
         */
        @Test
        @DisplayName("unreadable metadata is not a violation")
        void unreadableMetadataIsNotAViolation() throws Exception {
            when(secretProvider.getMetadata(any())).thenThrow(new ISecretProvider.SecretProviderException("vault down"));

            assertTrue(checker.findUngrantedReferences(agentReferencingTheVault(), "some-other-agent").isEmpty());
        }

        @Test
        @DisplayName("a disabled vault checks nothing")
        void disabledVaultChecksNothing() throws Exception {
            when(secretProvider.isAvailable()).thenReturn(false);

            assertTrue(checker.findUngrantedReferences(agentReferencingTheVault(), "some-other-agent").isEmpty());
        }

        @Test
        @DisplayName("an unreadable workflow yields no violations rather than throwing")
        void unreadableWorkflowIsSafe() throws Exception {
            givenGrant(List.of("agent-owner"));
            var agent = agentReferencingTheVault();
            when(workflowStore.read(anyString(), anyInt())).thenThrow(new RuntimeException("store down"));

            assertTrue(checker.findUngrantedReferences(agent, "some-other-agent").isEmpty());
        }

        @Test
        @DisplayName("an agent with no workflows, or a null agent, is trivially clean")
        void nothingToCheck() {
            assertTrue(checker.findUngrantedReferences(new AgentConfiguration(), "a").isEmpty());
            assertTrue(checker.findUngrantedReferences(null, "a").isEmpty());
        }
    }

    @Nested
    @DisplayName("every extension type is scanned, not just LLM configs")
    class AllExtensionTypes {

        /**
         * The LLM-only suite could not tell a working scanner from one that had
         * silently stopped covering httpcalls or MCP: both stores were mocked and never
         * reached.
         */
        @Test
        @DisplayName("an httpcall config's vault reference is found")
        void apiCallsAreScanned() throws Exception {
            givenGrant(List.of("agent-owner"));
            var agent = agentWithStep("ai.labs.httpcalls", LLM_ID);

            var apiCalls = new ApiCallsConfiguration();
            apiCalls.setTargetServerUrl("https://api.example.com/" + VAULT_REF);
            when(apiCallsStore.read(eq(LLM_ID), anyInt())).thenReturn(apiCalls);

            assertEquals(List.of(VAULT_REF), checker.findUngrantedReferences(agent, "some-other-agent"));
        }

        @Test
        @DisplayName("an MCP config's vault reference is found")
        void mcpCallsAreScanned() throws Exception {
            givenGrant(List.of("agent-owner"));
            var agent = agentWithStep("ai.labs.mcpcalls", LLM_ID);

            var mcpCalls = new McpCallsConfiguration();
            mcpCalls.setMcpServerUrl("https://mcp.example.com");
            mcpCalls.setApiKey(VAULT_REF);
            when(mcpCallsStore.read(eq(LLM_ID), anyInt())).thenReturn(mcpCalls);

            assertEquals(List.of(VAULT_REF), checker.findUngrantedReferences(agent, "some-other-agent"));
        }

        @Test
        @DisplayName("a granted agent passes on those types too")
        void grantedAgentPassesForEveryType() throws Exception {
            givenGrant(List.of("agent-owner"));
            var agent = agentWithStep("ai.labs.mcpcalls", LLM_ID);

            var mcpCalls = new McpCallsConfiguration();
            mcpCalls.setApiKey(VAULT_REF);
            when(mcpCallsStore.read(eq(LLM_ID), anyInt())).thenReturn(mcpCalls);

            assertTrue(checker.findUngrantedReferences(agent, "agent-owner").isEmpty());
        }
    }

    @Nested
    @DisplayName("surfaces outside the workflow resources")
    class BeyondWorkflows {

        /**
         * AgentConfiguration.DreamConfig.parameters explicitly supports vault
         * references and is handed to ChatModelRegistry by DreamService, so a Dream
         * credential lives outside every workflow resource and a workflow-only
         * traversal could not see it.
         */
        @Test
        @DisplayName("a reference on the agent document itself is found")
        void agentDocumentIsScanned() throws Exception {
            givenGrant(List.of("agent-owner"));

            var agent = new AgentConfiguration();
            agent.setWorkflows(List.of());
            var userMemory = new AgentConfiguration.UserMemoryConfig();
            userMemory.getDream().setParameters(new LinkedHashMap<>(Map.of("apiKey", VAULT_REF)));
            agent.setUserMemoryConfig(userMemory);

            assertEquals(List.of(VAULT_REF), checker.findUngrantedReferences(agent, "some-other-agent"));
        }

        @Test
        @DisplayName("the id/version overload reads the agent and reports the same violation")
        void idVersionOverloadReadsTheAgent() throws Exception {
            givenGrant(List.of("agent-owner"));
            // Built BEFORE the stub: agentReferencingTheVault() itself stubs, and a
            // when(...) inside a when(...) is an UnfinishedStubbing error.
            var agent = agentReferencingTheVault();
            when(agentStore.read("agent-x", 3)).thenReturn(agent);

            assertEquals(List.of(VAULT_REF), checker.findUngrantedReferences("agent-x", 3));
        }

        @Test
        @DisplayName("an unreadable agent yields no violations rather than throwing")
        void unreadableAgentIsSafe() throws Exception {
            when(agentStore.read(anyString(), anyInt())).thenThrow(new RuntimeException("store down"));

            assertTrue(checker.findUngrantedReferences("agent-x", 3).isEmpty());
        }
    }

    @Nested
    @DisplayName("reference discovery")
    class Discovery {

        /**
         * The scan serializes each config rather than enumerating its secret-bearing
         * fields — enumeration is how this kind of check silently stops covering a
         * newly added credential field.
         */
        @Test
        @DisplayName("finds a reference in any field, not only a known apiKey")
        void findsReferencesInArbitraryFields() throws Exception {
            givenGrant(List.of("agent-owner"));

            var agent = new AgentConfiguration();
            agent.setWorkflows(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/" + WORKFLOW_ID + "?version=1")));

            var step = new WorkflowConfiguration.WorkflowStep();
            step.setType(URI.create("eddi://ai.labs.llm"));
            var stepConfig = new LinkedHashMap<String, Object>();
            stepConfig.put("uri", "eddi://ai.labs.llm/llmstore/llms/" + LLM_ID + "?version=1");
            step.setConfig(stepConfig);
            var workflow = new WorkflowConfiguration();
            workflow.setWorkflowSteps(List.of(step));
            when(workflowStore.read(eq(WORKFLOW_ID), anyInt())).thenReturn(workflow);

            var task = new LlmConfiguration.Task();
            task.setType("openai");
            // Not "apiKey" — a field a hand-rolled scanner would have missed.
            task.setParameters(new LinkedHashMap<>(Map.of("someFutureCredentialField", VAULT_REF)));
            when(llmStore.read(eq(LLM_ID), anyInt())).thenReturn(new LlmConfiguration(List.of(task)));

            assertEquals(List.of(VAULT_REF), checker.findUngrantedReferences(agent, "some-other-agent"));
        }
    }
}
