/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.setup;

import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.llm.IRestLlmStore;
import ai.labs.eddi.configs.output.IRestOutputStore;
import ai.labs.eddi.configs.parser.IRestParserStore;
import ai.labs.eddi.configs.rules.IRestRuleSetStore;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.descriptors.IRestDocumentDescriptorStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.engine.api.IRestAgentAdministration;
import ai.labs.eddi.engine.runtime.client.factory.IRestInterfaceFactory;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.secrets.ISecretProvider;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Hardening pins for {@link AgentSetupService} — the service
 * {@code create_sub_agent} calls, which deploys to production from an
 * LLM-controlled path.
 */
@DisplayName("AgentSetupService — hardening")
class AgentSetupHardeningTest {

    private IRestInterfaceFactory restInterfaceFactory;
    private IRestParserStore parserStore;
    private IRestRuleSetStore ruleSetStore;
    private IRestLlmStore llmStore;
    private IRestWorkflowStore workflowStore;
    private IRestAgentStore agentStore;
    private IRestOutputStore outputStore;
    private AgentSetupService service;

    @BeforeEach
    void setUp() throws Exception {
        restInterfaceFactory = mock(IRestInterfaceFactory.class);
        parserStore = mock(IRestParserStore.class);
        ruleSetStore = mock(IRestRuleSetStore.class);
        llmStore = mock(IRestLlmStore.class);
        workflowStore = mock(IRestWorkflowStore.class);
        agentStore = mock(IRestAgentStore.class);
        outputStore = mock(IRestOutputStore.class);

        when(restInterfaceFactory.get(IRestParserStore.class)).thenReturn(parserStore);
        when(restInterfaceFactory.get(IRestRuleSetStore.class)).thenReturn(ruleSetStore);
        when(restInterfaceFactory.get(IRestLlmStore.class)).thenReturn(llmStore);
        when(restInterfaceFactory.get(IRestWorkflowStore.class)).thenReturn(workflowStore);
        when(restInterfaceFactory.get(IRestAgentStore.class)).thenReturn(agentStore);
        when(restInterfaceFactory.get(IRestOutputStore.class)).thenReturn(outputStore);
        when(restInterfaceFactory.get(IRestDocumentDescriptorStore.class)).thenReturn(mock(IRestDocumentDescriptorStore.class));

        service = new AgentSetupService(restInterfaceFactory, mock(IRestAgentAdministration.class), mock(ISecretProvider.class),
                "http://localhost:11434");
    }

    private static Response created(String location) {
        var response = mock(Response.class);
        when(response.getHeaderString("Location")).thenReturn(location);
        return response;
    }

    private static SetupAgentRequest request(String name, String prompt) {
        return new SetupAgentRequest(name, prompt, "ollama", "llama3", null, null, null, null, null, null, null, null, false, null, null, null);
    }

    @Nested
    @DisplayName("input bounds")
    class InputBounds {

        @Test
        @DisplayName("an over-long agent name is rejected before any resource is created")
        void overLongNameRejected() {
            String name = "n".repeat(AgentSetupService.MAX_AGENT_NAME_LENGTH + 1);

            var thrown = assertThrows(AgentSetupService.AgentSetupException.class, () -> service.setupAgent(request(name, "be helpful")));

            assertTrue(thrown.getMessage().contains("too long"), thrown.getMessage());
            verify(parserStore, never()).createParser(any());
        }

        @Test
        @DisplayName("an over-long system prompt is rejected before any resource is created")
        void overLongPromptRejected() {
            String prompt = "p".repeat(AgentSetupService.MAX_SYSTEM_PROMPT_LENGTH + 1);

            var thrown = assertThrows(AgentSetupService.AgentSetupException.class, () -> service.setupAgent(request("agent", prompt)));

            assertTrue(thrown.getMessage().contains("too long"), thrown.getMessage());
            verify(parserStore, never()).createParser(any());
        }

        @Test
        @DisplayName("names and prompts at the limit are still accepted")
        void limitsAreInclusive() {
            String name = "n".repeat(AgentSetupService.MAX_AGENT_NAME_LENGTH);
            String prompt = "p".repeat(AgentSetupService.MAX_SYSTEM_PROMPT_LENGTH);

            // Fails later (the stores are not stubbed), but NOT on the length check.
            var thrown = assertThrows(AgentSetupService.AgentSetupException.class, () -> service.setupAgent(request(name, prompt)));
            assertTrue(!thrown.getMessage().contains("too long"), thrown.getMessage());
        }
    }

    @Nested
    @DisplayName("rollback of a part-way setup")
    class Rollback {

        /**
         * Setup creates six-plus documents across as many stores with no transaction.
         * The failure path used to wrap-and-rethrow, orphaning everything created
         * before the failing step — on a path an LLM can retry in a loop.
         */
        @Test
        @DisplayName("a failure at the agent step deletes the resources created before it")
        void failureDeletesEarlierResources() {
            Response parser = created("eddi://ai.labs.parser/parserstore/parsers/p1?version=1");
            Response rules = created("eddi://ai.labs.rules/rulestore/rulesets/r1?version=1");
            Response llm = created("eddi://ai.labs.llm/llmstore/llms/l1?version=1");
            Response workflow = created("eddi://ai.labs.workflow/workflowstore/workflows/w1?version=1");
            when(parserStore.createParser(any())).thenReturn(parser);
            when(ruleSetStore.createRuleSet(any())).thenReturn(rules);
            when(llmStore.createLlm(any())).thenReturn(llm);
            when(workflowStore.createWorkflow(any())).thenReturn(workflow);
            when(agentStore.createAgent(any())).thenThrow(new RuntimeException("agent store down"));

            assertThrows(AgentSetupService.AgentSetupException.class, () -> service.setupAgent(request("agent", "be helpful")));

            verify(workflowStore).deleteWorkflow("w1", 1, true, false);
            verify(llmStore).deleteLlm("l1", 1, true);
            verify(ruleSetStore).deleteRuleSet("r1", 1, true);
            verify(parserStore).deleteParser("p1", 1, true);
        }

        @Test
        @DisplayName("a rollback delete that itself fails does not mask the original error")
        void rollbackFailureDoesNotMaskCause() {
            Response parser = created("eddi://ai.labs.parser/parserstore/parsers/p1?version=1");
            when(parserStore.createParser(any())).thenReturn(parser);
            when(ruleSetStore.createRuleSet(any())).thenThrow(new RuntimeException("ruleset store down"));
            when(parserStore.deleteParser(anyString(), anyInt(), any())).thenThrow(new RuntimeException("delete also down"));

            var thrown = assertThrows(AgentSetupService.AgentSetupException.class, () -> service.setupAgent(request("agent", "be helpful")));

            assertTrue(thrown.getMessage().contains("ruleset store down"), thrown.getMessage());
        }
    }

    @Nested
    @DisplayName("parent LLM profile inheritance")
    class Inheritance {

        /**
         * {@code create_sub_agent} passed a null apiKey with a comment claiming vault
         * inheritance, and nothing implemented it — so every provider that needs a key
         * (including the default) failed the required-API-key check outright.
         */
        @Test
        @DisplayName("resolves the parent's provider, model and vault reference")
        void resolvesParentProfile() {
            givenParentWithLlm("anthropic", Map.of("modelName", "claude-sonnet-4-6", "apiKey", "${vault:setup.parent.1.apiKey}"));

            var profile = service.resolveParentLlmProfile("parent-1");

            assertNotNull(profile);
            assertEquals("anthropic", profile.provider());
            assertEquals("claude-sonnet-4-6", profile.model());
            assertEquals("${vault:setup.parent.1.apiKey}", profile.apiKeyReference());
        }

        /**
         * The vault falls back to plaintext storage when it is not configured. Copying
         * such a value into a second config would multiply the blast radius of that
         * fallback rather than reference one secret from two places.
         */
        @Test
        @DisplayName("a plaintext parent key is NOT inherited")
        void plaintextKeyNotInherited() {
            givenParentWithLlm("openai", Map.of("modelName", "gpt-4o", "apiKey", "sk-a-real-secret"));

            var profile = service.resolveParentLlmProfile("parent-1");

            assertNotNull(profile);
            assertNull(profile.apiKeyReference(), "a plaintext secret must never be copied into a second config");
            assertEquals("openai", profile.provider());
        }

        /**
         * RestVersionInfo.read does checkNotNull(version), so readAgent(id, null)
         * always throws. Passing null made resolveParentLlmProfile swallow that and
         * return null, leaving create_sub_agent failing with "API key is required" —
         * the very defect inheritance was added to fix.
         */
        @Test
        @DisplayName("reads the parent at its resolved current version, never at a null version")
        void readsParentAtResolvedVersion() {
            givenParentWithLlm("anthropic", Map.of("modelName", "claude-sonnet-4-6", "apiKey", "${vault:k}"));

            assertNotNull(service.resolveParentLlmProfile("parent-1"));

            verify(agentStore).getCurrentVersion("parent-1");
            verify(agentStore).readAgent("parent-1", 4);
            verify(agentStore, never()).readAgent(anyString(), isNull());
        }

        @Test
        @DisplayName("a workflow whose LLM task carries nothing inheritable is skipped")
        void emptyTaskIsNotAProfile() {
            givenParentWithLlm(null, Map.of());

            assertNull(service.resolveParentLlmProfile("parent-1"),
                    "an all-null profile would make the caller treat inheritance as available");
        }

        @Test
        @DisplayName("an unreadable parent yields no profile rather than throwing")
        void unreadableParentIsNull() {
            when(agentStore.getCurrentVersion(anyString())).thenThrow(new RuntimeException("store down"));

            assertNull(service.resolveParentLlmProfile("parent-1"));
        }

        @Test
        @DisplayName("a blank parent id yields no profile")
        void blankParentIsNull() {
            assertNull(service.resolveParentLlmProfile(null));
            assertNull(service.resolveParentLlmProfile("  "));
        }

        private void givenParentWithLlm(String provider, Map<String, String> parameters) {
            var agent = new AgentConfiguration();
            agent.setWorkflows(List.of(URI.create("eddi://ai.labs.workflow/workflowstore/workflows/w1?version=1")));
            // Stubbed on the RESOLVED version, never any(): readAgent(id, null) throws
            // checkNotNull inside RestVersionInfo, so a lenient any() stub hid the fact
            // that the production call passed null and inheritance never worked.
            when(agentStore.getCurrentVersion("parent-1")).thenReturn(4);
            when(agentStore.readAgent("parent-1", 4)).thenReturn(agent);

            var step = new WorkflowConfiguration.WorkflowStep();
            step.setType(URI.create("eddi://ai.labs.llm"));
            var config = new LinkedHashMap<String, Object>();
            config.put("uri", "eddi://ai.labs.llm/llmstore/llms/l1?version=1");
            step.setConfig(config);

            var workflow = new WorkflowConfiguration();
            workflow.setWorkflowSteps(List.of(step));
            when(workflowStore.readWorkflow(eq("w1"), anyInt())).thenReturn(workflow);

            var task = new LlmConfiguration.Task();
            task.setType(provider);
            task.setParameters(new LinkedHashMap<>(parameters));
            when(llmStore.readLlm(eq("l1"), anyInt())).thenReturn(new LlmConfiguration(List.of(task)));
        }
    }
}
