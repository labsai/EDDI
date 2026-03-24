package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.configs.rules.IRestRuleSetStore;
import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.descriptors.IRestDocumentDescriptorStore;
import ai.labs.eddi.configs.apicalls.IRestApiCallsStore;
import ai.labs.eddi.configs.llm.IRestLlmStore;
import ai.labs.eddi.configs.output.IRestOutputStore;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.configs.parser.IRestParserStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.api.IRestAgentAdministration;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.runtime.client.factory.IRestInterfaceFactory;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class McpSetupToolsTest {

        private IRestRuleSetStore behaviorStore;
        private IRestLlmStore langchainStore;
        private IRestOutputStore outputStore;
        private IRestApiCallsStore httpCallsStore;
        private IRestWorkflowStore WorkflowStore;
        private IRestAgentStore AgentStore;
        private IRestDocumentDescriptorStore descriptorStore;
        private IRestAgentAdministration agentAdmin;
        private IRestParserStore parserStore;
        private IJsonSerialization jsonSerialization;
        private McpSetupTools tools;

        @BeforeEach
        void setUp() throws Exception {
                behaviorStore = mock(IRestRuleSetStore.class);
                langchainStore = mock(IRestLlmStore.class);
                outputStore = mock(IRestOutputStore.class);
                httpCallsStore = mock(IRestApiCallsStore.class);
                WorkflowStore = mock(IRestWorkflowStore.class);
                AgentStore = mock(IRestAgentStore.class);
                descriptorStore = mock(IRestDocumentDescriptorStore.class);
                agentAdmin = mock(IRestAgentAdministration.class);
                parserStore = mock(IRestParserStore.class);
                jsonSerialization = mock(IJsonSerialization.class);

                // Wire store mocks through IRestInterfaceFactory
                var restInterfaceFactory = mock(IRestInterfaceFactory.class);
                when(restInterfaceFactory.get(IRestRuleSetStore.class)).thenReturn(behaviorStore);
                when(restInterfaceFactory.get(IRestLlmStore.class)).thenReturn(langchainStore);
                when(restInterfaceFactory.get(IRestOutputStore.class)).thenReturn(outputStore);
                when(restInterfaceFactory.get(IRestApiCallsStore.class)).thenReturn(httpCallsStore);
                when(restInterfaceFactory.get(IRestWorkflowStore.class)).thenReturn(WorkflowStore);
                when(restInterfaceFactory.get(IRestAgentStore.class)).thenReturn(AgentStore);
                when(restInterfaceFactory.get(IRestDocumentDescriptorStore.class)).thenReturn(descriptorStore);
                when(restInterfaceFactory.get(IRestParserStore.class)).thenReturn(parserStore);

                // Default serialization
                lenient().when(jsonSerialization.serialize(any())).thenReturn("{}");

                // Default parser mock (needed by all setupAgent calls)
                lenient().when(parserStore.createParser(any()))
                                .thenReturn(Response.created(URI.create("/parserstore/parsers/par-1?version=1"))
                                                .build());

                tools = new McpSetupTools(restInterfaceFactory, agentAdmin, jsonSerialization);
        }

        @Test
        void setupAgent_fullWorkflow_createsAllResources() throws Exception {
                // Mock all store responses with Location headers
                when(behaviorStore.createRuleSet(any()))
                                .thenReturn(Response.created(URI.create("/rulestore/rulesets/beh-1?version=1"))
                                                .build());
                when(langchainStore.createLlm(any()))
                                .thenReturn(Response.created(URI.create("/llmstore/llms/lc-1?version=1"))
                                                .build());
                when(outputStore.createOutputSet(any()))
                                .thenReturn(Response.created(URI.create("/outputstore/outputsets/out-1?version=1"))
                                                .build());
                when(WorkflowStore.createWorkflow(any()))
                                .thenReturn(Response.created(URI.create("/workflowstore/workflows/pkg-1?version=1"))
                                                .build());
                when(AgentStore.createAgent(any()))
                                .thenReturn(Response.created(URI.create("/agentstore/agents/agent-1?version=1"))
                                                .build());
                when(agentAdmin.deployAgent(any(), any(), anyInt(), anyBoolean(), anyBoolean()))
                                .thenReturn(Response.ok().build());

                String result = tools.setupAgent(
                                "Test Agent", "You are helpful", "anthropic", "claude-sonnet-4-6",
                                "sk-test", null, "Hello!", true, "calculator,datetime",
                                null, null, null, true, "production");

                assertNotNull(result);

                // Verify all resources created in order
                verify(parserStore).createParser(any());
                verify(behaviorStore).createRuleSet(any());
                verify(langchainStore).createLlm(any());
                verify(outputStore).createOutputSet(any());
                verify(WorkflowStore).createWorkflow(any());
                verify(AgentStore).createAgent(any());
                verify(agentAdmin).deployAgent(Environment.production, "agent-1", 1, true, true);

                // Verify 6 descriptors patched (parser, behavior, langchain, output, package,
                // agent)
                verify(descriptorStore, times(6)).patchDescriptor(any(), anyInt(), any());
        }

        @Test
        void setupAgent_withoutIntro_skipsOutput() throws Exception {
                when(behaviorStore.createRuleSet(any()))
                                .thenReturn(Response.created(URI.create("/rulestore/rulesets/beh-1?version=1"))
                                                .build());
                when(langchainStore.createLlm(any()))
                                .thenReturn(Response.created(URI.create("/llmstore/llms/lc-1?version=1"))
                                                .build());
                when(WorkflowStore.createWorkflow(any()))
                                .thenReturn(Response.created(URI.create("/workflowstore/workflows/pkg-1?version=1"))
                                                .build());
                when(AgentStore.createAgent(any()))
                                .thenReturn(Response.created(URI.create("/agentstore/agents/agent-1?version=1"))
                                                .build());

                tools.setupAgent("Test Agent", "You are helpful", null, null,
                                "sk-test", null, null, null, null, null, null, null, true, null);

                // Output store should NOT be called
                verify(outputStore, never()).createOutputSet(any());
                // 5 descriptors patched (parser, behavior, langchain, package, Agent — no
                // output)
                verify(descriptorStore, times(5)).patchDescriptor(any(), anyInt(), any());
        }

        @Test
        void setupAgent_withoutDeploy_skipsDeploy() throws Exception {
                when(behaviorStore.createRuleSet(any()))
                                .thenReturn(Response.created(URI.create("/rulestore/rulesets/beh-1?version=1"))
                                                .build());
                when(langchainStore.createLlm(any()))
                                .thenReturn(Response.created(URI.create("/llmstore/llms/lc-1?version=1"))
                                                .build());
                when(WorkflowStore.createWorkflow(any()))
                                .thenReturn(Response.created(URI.create("/workflowstore/workflows/pkg-1?version=1"))
                                                .build());
                when(AgentStore.createAgent(any()))
                                .thenReturn(Response.created(URI.create("/agentstore/agents/agent-1?version=1"))
                                                .build());

                tools.setupAgent("Test Agent", "You are helpful", null, null,
                                "sk-test", null, null, null, null, null, null, null, false, null);

                // Deploy should NOT be called
                verify(agentAdmin, never()).deployAgent(any(), any(), anyInt(), anyBoolean(), anyBoolean());
        }

        @Test
        void setupAgent_deployFails_returnsSuccessWithWarning() throws Exception {
                when(behaviorStore.createRuleSet(any()))
                                .thenReturn(Response.created(URI.create("/rulestore/rulesets/beh-1?version=1"))
                                                .build());
                when(langchainStore.createLlm(any()))
                                .thenReturn(Response.created(URI.create("/llmstore/llms/lc-1?version=1"))
                                                .build());
                when(WorkflowStore.createWorkflow(any()))
                                .thenReturn(Response.created(URI.create("/workflowstore/workflows/pkg-1?version=1"))
                                                .build());
                when(AgentStore.createAgent(any()))
                                .thenReturn(Response.created(URI.create("/agentstore/agents/agent-1?version=1"))
                                                .build());
                when(agentAdmin.deployAgent(any(), any(), anyInt(), anyBoolean(), anyBoolean()))
                                .thenThrow(new RuntimeException("Deploy failed"));

                String result = tools.setupAgent("Test Agent", "You are helpful", null, null,
                                "sk-test", null, null, null, null, null, null, null, true, null);

                // Should still return a result (agent was created), not an error
                assertNotNull(result);
                assertFalse(result.contains("\"error\""));
        }

        @Test
        void setupAgent_missingName_returnsError() {
                String result = tools.setupAgent(null, "prompt", null, null, "key", null, null, null, null, null, null,
                                null, null, null);
                assertTrue(result.contains("error"));
                assertTrue(result.contains("name is required"));
        }

        @Test
        void setupAgent_missingPrompt_returnsError() {
                String result = tools.setupAgent("Agent", null, null, null, "key", null, null, null, null, null, null,
                                null, null, null);
                assertTrue(result.contains("error"));
                assertTrue(result.contains("System prompt is required"));
        }

        @Test
        void setupAgent_missingApiKey_returnsError() {
                String result = tools.setupAgent("Agent", "prompt", null, null, null, null, null, null, null, null,
                                null, null, null, null);
                assertTrue(result.contains("error"));
                assertTrue(result.contains("API key is required"));
        }

        @Test
        void setupAgent_ollamaNoApiKey_succeeds() throws Exception {
                when(behaviorStore.createRuleSet(any()))
                                .thenReturn(Response.created(URI.create("/rulestore/rulesets/beh-1?version=1"))
                                                .build());
                when(langchainStore.createLlm(any()))
                                .thenReturn(Response.created(URI.create("/llmstore/llms/lc-1?version=1"))
                                                .build());
                when(WorkflowStore.createWorkflow(any()))
                                .thenReturn(Response.created(URI.create("/workflowstore/workflows/pkg-1?version=1"))
                                                .build());
                when(AgentStore.createAgent(any()))
                                .thenReturn(Response.created(URI.create("/agentstore/agents/agent-1?version=1"))
                                                .build());

                // Ollama should NOT require an apiKey
                String result = tools.setupAgent("Ollama Agent", "You are helpful", "ollama", "llama3.2:1b",
                                null, null, null, null, null, null, null, null, false, null);

                assertNotNull(result);
                assertFalse(result.contains("\"error\""), "Ollama setup should succeed without API key");
                verify(langchainStore).createLlm(any());
        }

        @Test
        void setupAgent_jlamaNoApiKey_succeeds() throws Exception {
                when(behaviorStore.createRuleSet(any()))
                                .thenReturn(Response.created(URI.create("/rulestore/rulesets/beh-1?version=1"))
                                                .build());
                when(langchainStore.createLlm(any()))
                                .thenReturn(Response.created(URI.create("/llmstore/llms/lc-1?version=1"))
                                                .build());
                when(WorkflowStore.createWorkflow(any()))
                                .thenReturn(Response.created(URI.create("/workflowstore/workflows/pkg-1?version=1"))
                                                .build());
                when(AgentStore.createAgent(any()))
                                .thenReturn(Response.created(URI.create("/agentstore/agents/agent-1?version=1"))
                                                .build());

                String result = tools.setupAgent("Jlama Agent", "You are helpful", "jlama", "tinyllama",
                                null, null, null, null, null, null, null, null, false, null);

                assertNotNull(result);
                assertFalse(result.contains("\"error\""), "Jlama setup should succeed without API key");
        }

        @Test
        void setupAgent_capturesLangchainConfig() throws Exception {
                when(behaviorStore.createRuleSet(any()))
                                .thenReturn(Response.created(URI.create("/rulestore/rulesets/beh-1?version=1"))
                                                .build());
                when(langchainStore.createLlm(any()))
                                .thenReturn(Response.created(URI.create("/llmstore/llms/lc-1?version=1"))
                                                .build());
                when(WorkflowStore.createWorkflow(any()))
                                .thenReturn(Response.created(URI.create("/workflowstore/workflows/pkg-1?version=1"))
                                                .build());
                when(AgentStore.createAgent(any()))
                                .thenReturn(Response.created(URI.create("/agentstore/agents/agent-1?version=1"))
                                                .build());

                tools.setupAgent("My Agent", "You are a pirate", "anthropic", "claude-3-5-sonnet",
                                "sk-ant-key", null, null, null, null, null, null, null, false, null);

                // Capture the langchain config
                var langchainCaptor = ArgumentCaptor.forClass(LlmConfiguration.class);
                verify(langchainStore).createLlm(langchainCaptor.capture());

                var config = langchainCaptor.getValue();
                assertEquals(1, config.tasks().size());
                var task = config.tasks().get(0);
                assertEquals("anthropic", task.getType());
                assertEquals("You are a pirate", task.getParameters().get("systemMessage"));
                assertEquals("claude-3-5-sonnet", task.getParameters().get("modelName"));
                assertEquals("sk-ant-key", task.getParameters().get("apiKey"));
        }

        @Test
        void setupAgent_capturesWorkflowConfig() throws Exception {
                when(behaviorStore.createRuleSet(any()))
                                .thenReturn(Response.created(URI.create("/rulestore/rulesets/beh-1?version=1"))
                                                .build());
                when(langchainStore.createLlm(any()))
                                .thenReturn(Response.created(URI.create("/llmstore/llms/lc-1?version=1"))
                                                .build());
                when(outputStore.createOutputSet(any()))
                                .thenReturn(Response.created(URI.create("/outputstore/outputsets/out-1?version=1"))
                                                .build());
                when(WorkflowStore.createWorkflow(any()))
                                .thenReturn(Response.created(URI.create("/workflowstore/workflows/pkg-1?version=1"))
                                                .build());
                when(AgentStore.createAgent(any()))
                                .thenReturn(Response.created(URI.create("/agentstore/agents/agent-1?version=1"))
                                                .build());

                tools.setupAgent("Agent", "prompt", null, null, "key", null, "Hello!",
                                null, null, null, null, null, false, null);

                // Capture package config
                var packageCaptor = ArgumentCaptor.forClass(WorkflowConfiguration.class);
                verify(WorkflowStore).createWorkflow(packageCaptor.capture());

                var pkgConfig = packageCaptor.getValue();
                // Should have 4 extensions: parser, behavior, langchain, output
                assertEquals(4, pkgConfig.getWorkflowSteps().size());
                assertEquals(URI.create("eddi://ai.labs.parser"), pkgConfig.getWorkflowSteps().get(0).getType());
                assertEquals(URI.create("eddi://ai.labs.rules"), pkgConfig.getWorkflowSteps().get(1).getType());
                assertEquals(URI.create("eddi://ai.labs.llm"), pkgConfig.getWorkflowSteps().get(2).getType());
                assertEquals(URI.create("eddi://ai.labs.output"), pkgConfig.getWorkflowSteps().get(3).getType());
        }

        @Test
        void setupAgent_packageWithoutOutput_has3Extensions() throws Exception {
                when(behaviorStore.createRuleSet(any()))
                                .thenReturn(Response.created(URI.create("/rulestore/rulesets/beh-1?version=1"))
                                                .build());
                when(langchainStore.createLlm(any()))
                                .thenReturn(Response.created(URI.create("/llmstore/llms/lc-1?version=1"))
                                                .build());
                when(WorkflowStore.createWorkflow(any()))
                                .thenReturn(Response.created(URI.create("/workflowstore/workflows/pkg-1?version=1"))
                                                .build());
                when(AgentStore.createAgent(any()))
                                .thenReturn(Response.created(URI.create("/agentstore/agents/agent-1?version=1"))
                                                .build());

                tools.setupAgent("Agent", "prompt", null, null, "key", null, null,
                                null, null, null, null, null, false, null);

                var packageCaptor = ArgumentCaptor.forClass(WorkflowConfiguration.class);
                verify(WorkflowStore).createWorkflow(packageCaptor.capture());

                // Without intro message, should have 3 extensions: parser, behavior, langchain
                // (no output)
                assertEquals(3, packageCaptor.getValue().getWorkflowSteps().size());
        }

        @Test
        void createBehaviorConfig_producesCorrectStructure() {
                var config = tools.createBehaviorConfig();
                assertTrue(config.getExpressionsAsActions());
                assertEquals(1, config.getBehaviorGroups().size());

                var group = config.getBehaviorGroups().get(0);
                assertEquals(1, group.getRules().size());

                var rule = group.getRules().get(0);
                assertEquals("Send Message to LLM", rule.getName());
                assertEquals(List.of("send_message"), rule.getActions());
                assertEquals("inputmatcher", rule.getConditions().get(0).getType());
                assertEquals("*", rule.getConditions().get(0).getConfigs().get("expressions"));
        }

        @Test
        void createLlmConfig_withTooling_setsToolFields() {
                var config = tools.createLlmConfig(
                                "anthropic", "claude-sonnet-4-6", "key", "You are helpful",
                                true, "calculator,websearch", null, null, false, false, null);

                var task = config.tasks().get(0);
                assertTrue(task.getEnableBuiltInTools());
                assertEquals(java.util.List.of("calculator", "websearch"), task.getBuiltInToolsWhitelist());
        }

        @Test
        void createLlmConfig_ollama_usesModelParam() {
                var config = tools.createLlmConfig(
                                "ollama", "llama3.2:1b", null, "prompt",
                                false, null, "http://host.docker.internal:11434", null, false, false, null);

                var task = config.tasks().get(0);
                var params = task.getParameters();
                assertEquals("llama3.2:1b", params.get("model"));
                assertEquals("http://host.docker.internal:11434", params.get("baseUrl"));
                assertNull(params.get("modelName"), "Ollama should NOT use 'modelName'");
                assertNull(params.get("apiKey"), "Ollama should NOT set 'apiKey'");
        }

        @Test
        void createLlmConfig_jlama_usesAuthToken() {
                var config = tools.createLlmConfig(
                                "jlama", "tinyllama", "my-token", "prompt",
                                false, null, null, null, false, false, null);

                var task = config.tasks().get(0);
                var params = task.getParameters();
                assertEquals("tinyllama", params.get("modelName"));
                assertEquals("my-token", params.get("authToken"));
                assertNull(params.get("apiKey"), "Jlama should use 'authToken' not 'apiKey'");
        }

        @Test
        void createLlmConfig_withJsonFormat_setsPostResponse() {
                String jsonPrompt = McpSetupTools.buildPromptResponseJson(true, true);
                var config = tools.createLlmConfig(
                                "openai", "gpt-4o", "key", "prompt",
                                false, null, null, jsonPrompt, true, true, null);

                var task = config.tasks().get(0);
                assertEquals("aiOutput", task.getResponseObjectName());
                assertNotNull(task.getPostResponse(), "PostResponse should be set for JSON format");
                assertNull(task.getPostResponse().getPropertyInstructions(),
                                "propertyInstructions not needed — aiOutput fields accessed directly in templates");
                assertNotNull(task.getPostResponse().getOutputBuildInstructions());
                assertNotNull(task.getPostResponse().getQrBuildInstructions());
        }

        @Test
        void createLlmConfig_withoutJsonFormat_noPostResponse() {
                var config = tools.createLlmConfig(
                                "anthropic", "claude-sonnet-4-6", "key", "prompt",
                                false, null, null, null, false, false, null);

                var task = config.tasks().get(0);
                assertNull(task.getResponseObjectName());
                assertNull(task.getPostResponse());
        }

        // --- Quick Replies & Sentiment Analysis tests ---

        @Test
        void setupAgent_withQuickReplies_appendsJsonFormat() throws Exception {
                when(behaviorStore.createRuleSet(any()))
                                .thenReturn(Response.created(URI.create("/rulestore/rulesets/beh-1?version=1"))
                                                .build());
                when(langchainStore.createLlm(any()))
                                .thenReturn(Response.created(URI.create("/llmstore/llms/lc-1?version=1"))
                                                .build());
                when(WorkflowStore.createWorkflow(any()))
                                .thenReturn(Response.created(URI.create("/workflowstore/workflows/pkg-1?version=1"))
                                                .build());
                when(AgentStore.createAgent(any()))
                                .thenReturn(Response.created(URI.create("/agentstore/agents/agent-1?version=1"))
                                                .build());

                tools.setupAgent("QR Agent", "You are helpful", "openai", "gpt-4o",
                                "sk-test", null, null, null, null, true, null, null, false, null);

                var lcCaptor = ArgumentCaptor.forClass(LlmConfiguration.class);
                verify(langchainStore).createLlm(lcCaptor.capture());
                var task = lcCaptor.getValue().tasks().get(0);
                var params = task.getParameters();

                assertTrue(params.get("systemMessage").contains("quickReplies"),
                                "System message should contain quickReplies format");
                assertTrue(params.get("systemMessage").contains("htmlResponseText"),
                                "System message should contain htmlResponseText");
                assertEquals("true", params.get("convertToObject"),
                                "convertToObject should be true for JSON format");
                assertEquals("json", params.get("responseFormat"),
                                "OpenAI should have responseFormat=json");

                // Verify postResponse is set with QR instructions
                assertNotNull(task.getPostResponse());
                assertNotNull(task.getPostResponse().getQrBuildInstructions());
                assertEquals(1, task.getPostResponse().getQrBuildInstructions().size());
        }

        @Test
        void setupAgent_withSentiment_appendsJsonFormat() throws Exception {
                when(behaviorStore.createRuleSet(any()))
                                .thenReturn(Response.created(URI.create("/rulestore/rulesets/beh-1?version=1"))
                                                .build());
                when(langchainStore.createLlm(any()))
                                .thenReturn(Response.created(URI.create("/llmstore/llms/lc-1?version=1"))
                                                .build());
                when(WorkflowStore.createWorkflow(any()))
                                .thenReturn(Response.created(URI.create("/workflowstore/workflows/pkg-1?version=1"))
                                                .build());
                when(AgentStore.createAgent(any()))
                                .thenReturn(Response.created(URI.create("/agentstore/agents/agent-1?version=1"))
                                                .build());

                tools.setupAgent("Sentiment Agent", "You are helpful", "gemini", "gemini-2.0-flash",
                                "key", null, null, null, null, null, true, null, false, null);

                var lcCaptor = ArgumentCaptor.forClass(LlmConfiguration.class);
                verify(langchainStore).createLlm(lcCaptor.capture());
                var task = lcCaptor.getValue().tasks().get(0);
                var params = task.getParameters();

                assertTrue(params.get("systemMessage").contains("\"sentiment\":"),
                                "System message should contain nested sentiment object");
                assertTrue(params.get("systemMessage").contains("\"score\":"),
                                "System message should contain score field");
                assertTrue(params.get("systemMessage").contains("htmlResponseText"),
                                "System message should contain htmlResponseText");
                assertFalse(params.get("systemMessage").contains("quickReplies"),
                                "System message should NOT contain quickReplies");
                assertEquals("json", params.get("responseFormat"),
                                "Gemini should have responseFormat=json");

                // PostResponse should NOT have QR instructions (only sentiment, no QR)
                assertNotNull(task.getPostResponse());
                assertNull(task.getPostResponse().getQrBuildInstructions());
        }

        @Test
        void setupAgent_withAgenthFeatures_appendsFullJsonFormat() throws Exception {
                when(behaviorStore.createRuleSet(any()))
                                .thenReturn(Response.created(URI.create("/rulestore/rulesets/beh-1?version=1"))
                                                .build());
                when(langchainStore.createLlm(any()))
                                .thenReturn(Response.created(URI.create("/llmstore/llms/lc-1?version=1"))
                                                .build());
                when(WorkflowStore.createWorkflow(any()))
                                .thenReturn(Response.created(URI.create("/workflowstore/workflows/pkg-1?version=1"))
                                                .build());
                when(AgentStore.createAgent(any()))
                                .thenReturn(Response.created(URI.create("/agentstore/agents/agent-1?version=1"))
                                                .build());

                tools.setupAgent("Full Agent", "You are helpful", "openai", "gpt-4o",
                                "sk-test", null, null, null, null, true, true, null, false, null);

                var lcCaptor = ArgumentCaptor.forClass(LlmConfiguration.class);
                verify(langchainStore).createLlm(lcCaptor.capture());
                var task = lcCaptor.getValue().tasks().get(0);
                var params = task.getParameters();

                assertTrue(params.get("systemMessage").contains("quickReplies"));
                assertTrue(params.get("systemMessage").contains("\"sentiment\":"));
                assertTrue(params.get("systemMessage").contains("htmlResponseText"));
                assertEquals("true", params.get("convertToObject"));
                assertEquals("json", params.get("responseFormat"));

                // PostResponse should have agenth output and QR instructions
                assertNotNull(task.getPostResponse());
                assertNotNull(task.getPostResponse().getOutputBuildInstructions());
                assertNotNull(task.getPostResponse().getQrBuildInstructions());
        }

        @Test
        void setupAgent_anthropic_noResponseFormat() throws Exception {
                when(behaviorStore.createRuleSet(any()))
                                .thenReturn(Response.created(URI.create("/rulestore/rulesets/beh-1?version=1"))
                                                .build());
                when(langchainStore.createLlm(any()))
                                .thenReturn(Response.created(URI.create("/llmstore/llms/lc-1?version=1"))
                                                .build());
                when(WorkflowStore.createWorkflow(any()))
                                .thenReturn(Response.created(URI.create("/workflowstore/workflows/pkg-1?version=1"))
                                                .build());
                when(AgentStore.createAgent(any()))
                                .thenReturn(Response.created(URI.create("/agentstore/agents/agent-1?version=1"))
                                                .build());

                tools.setupAgent("Anthropic Agent", "You are helpful", "anthropic", "claude-sonnet-4-6",
                                "sk-test", null, null, null, null, true, null, null, false, null);

                var lcCaptor = ArgumentCaptor.forClass(LlmConfiguration.class);
                verify(langchainStore).createLlm(lcCaptor.capture());
                var params = lcCaptor.getValue().tasks().get(0).getParameters();

                // Anthropic doesn't support responseFormat but should still have the prompt
                // instruction
                assertTrue(params.get("systemMessage").contains("quickReplies"));
                assertEquals("true", params.get("convertToObject"));
                assertNull(params.get("responseFormat"),
                                "Anthropic should NOT have responseFormat param");
        }

        @Test
        void buildPromptResponseJson_quickRepliesOnly() {
                String result = McpSetupTools.buildPromptResponseJson(true, false);
                assertNotNull(result);
                assertTrue(result.contains("quickReplies"));
                assertTrue(result.contains("htmlResponseText"));
                assertFalse(result.contains("sentimentScore"));
        }

        @Test
        void buildPromptResponseJson_sentimentOnly() {
                String result = McpSetupTools.buildPromptResponseJson(false, true);
                assertNotNull(result);
                assertTrue(result.contains("\"sentiment\":"));
                assertTrue(result.contains("\"score\":"));
                assertTrue(result.contains("\"emotions\":"));
                assertTrue(result.contains("\"urgency\":"));
                assertTrue(result.contains("\"confidence\":"));
                assertTrue(result.contains("\"topicTags\":"));
                assertFalse(result.contains("quickReplies"));
        }

        @Test
        void buildPromptResponseJson_agenth() {
                String result = McpSetupTools.buildPromptResponseJson(true, true);
                assertNotNull(result);
                assertTrue(result.contains("quickReplies"));
                assertTrue(result.contains("\"sentiment\":"));
                assertTrue(result.contains("\"score\":"));
                assertTrue(result.contains("\"confidence\":"));
                assertTrue(result.contains("\"topicTags\":"));
                assertTrue(result.contains("htmlResponseText"));
        }

        @Test
        void buildPromptResponseJson_neither_returnsNull() {
                assertNull(McpSetupTools.buildPromptResponseJson(false, false));
        }

        @Test
        void supportsResponseFormat_openaiAndGemini() {
                assertTrue(McpSetupTools.supportsResponseFormat("openai"));
                assertTrue(McpSetupTools.supportsResponseFormat("gemini"));
                assertTrue(McpSetupTools.supportsResponseFormat("gemini-vertex"));
                assertFalse(McpSetupTools.supportsResponseFormat("anthropic"));
                assertFalse(McpSetupTools.supportsResponseFormat("ollama"));
                assertFalse(McpSetupTools.supportsResponseFormat("jlama"));
        }

        @Test
        void isLocalLlmProvider_recognizesOllamaAndJlama() {
                assertTrue(McpSetupTools.isLocalLlmProvider("ollama"));
                assertTrue(McpSetupTools.isLocalLlmProvider("Ollama"));
                assertTrue(McpSetupTools.isLocalLlmProvider("jlama"));
                assertTrue(McpSetupTools.isLocalLlmProvider("JLAMA"));
                assertFalse(McpSetupTools.isLocalLlmProvider("anthropic"));
                assertFalse(McpSetupTools.isLocalLlmProvider("openai"));
                assertFalse(McpSetupTools.isLocalLlmProvider(null));
                assertFalse(McpSetupTools.isLocalLlmProvider(""));
        }

        @Test
        void createOutputConfig_hasConversationStartAction() {
                var config = tools.createOutputConfig("Welcome!");

                assertEquals(1, config.getOutputSet().size());
                var output = config.getOutputSet().get(0);
                assertEquals("CONVERSATION_START", output.getAction());
                assertEquals(0, output.getTimesOccurred());
                assertEquals(1, output.getOutputs().size());
        }

        @Test
        void buildPostResponse_withQuickReplies_hasQrInstructions() {
                var postResponse = tools.buildPostResponse(true, false);

                // No propertyInstructions — aiOutput is already a Map in templateDataObjects
                assertNull(postResponse.getPropertyInstructions());

                assertNotNull(postResponse.getOutputBuildInstructions());
                assertEquals("text", postResponse.getOutputBuildInstructions().get(0).getOutputType());
                assertTrue(postResponse.getOutputBuildInstructions().get(0).getOutputValue()
                                .contains("aiOutput.htmlResponseText"));

                assertNotNull(postResponse.getQrBuildInstructions());
                assertEquals(1, postResponse.getQrBuildInstructions().size());
                assertEquals("aiOutput.quickReplies",
                                postResponse.getQrBuildInstructions().get(0).getPathToTargetArray());
        }

        @Test
        void buildPostResponse_withoutQuickReplies_noQrInstructions() {
                var postResponse = tools.buildPostResponse(false, true);

                assertNull(postResponse.getPropertyInstructions());
                assertNotNull(postResponse.getOutputBuildInstructions());
                assertNull(postResponse.getQrBuildInstructions());
        }

        // --- create_api_agent tests ---

        private static final String SIMPLE_SPEC = """
                        {
                          "openapi": "3.0.3",
                          "info": { "title": "Test API", "version": "1.0.0" },
                          "servers": [{ "url": "https://api.example.com" }],
                          "paths": {
                            "/users": {
                              "get": {
                                "operationId": "listUsers",
                                "summary": "List users",
                                "tags": ["users"]
                              }
                            },
                            "/orders": {
                              "post": {
                                "operationId": "createOrder",
                                "summary": "Create order",
                                "tags": ["orders"]
                              }
                            }
                          }
                        }
                        """;

        @Test
        void createApiAgent_fullWorkflow_createsAllResources() throws Exception {
                // Mock all store responses
                when(httpCallsStore.createApiCalls(any()))
                                .thenReturn(Response.created(URI.create("/apicallstore/apicalls/hc-1?version=1"))
                                                .build())
                                .thenReturn(Response.created(URI.create("/apicallstore/apicalls/hc-2?version=1"))
                                                .build());
                when(behaviorStore.createRuleSet(any()))
                                .thenReturn(Response.created(URI.create("/rulestore/rulesets/beh-1?version=1"))
                                                .build());
                when(langchainStore.createLlm(any()))
                                .thenReturn(Response.created(URI.create("/llmstore/llms/lc-1?version=1"))
                                                .build());
                when(WorkflowStore.createWorkflow(any()))
                                .thenReturn(Response.created(URI.create("/workflowstore/workflows/pkg-1?version=1"))
                                                .build());
                when(AgentStore.createAgent(any()))
                                .thenReturn(Response.created(URI.create("/agentstore/agents/agent-1?version=1"))
                                                .build());
                when(agentAdmin.deployAgent(any(), any(), anyInt(), anyBoolean(), anyBoolean()))
                                .thenReturn(Response.ok().build());

                String result = tools.createApIAgent(
                                "API Agent", "You are an API assistant", SIMPLE_SPEC,
                                "anthropic", "claude-sonnet-4-6", "sk-test",
                                null, "Bearer api-key", null, null, null, true, null);

                assertNotNull(result);

                // Verify httpcalls created (2 groups: users, orders)
                verify(httpCallsStore, times(2)).createApiCalls(any());
                verify(parserStore).createParser(any());
                verify(behaviorStore).createRuleSet(any());
                verify(langchainStore).createLlm(any());
                verify(WorkflowStore).createWorkflow(any());
                verify(AgentStore).createAgent(any());
                verify(agentAdmin).deployAgent(Environment.production, "agent-1", 1, true, true);

                // Verify the system prompt was enriched with API summary
                var lcCaptor = ArgumentCaptor.forClass(LlmConfiguration.class);
                verify(langchainStore).createLlm(lcCaptor.capture());
                String systemMessage = lcCaptor.getValue().tasks().get(0).getParameters().get("systemMessage");
                assertTrue(systemMessage.startsWith("You are an API assistant"),
                                "System prompt should keep the original text");
                assertTrue(systemMessage.contains("Available API endpoints"),
                                "System prompt should include the API summary");
        }

        @Test
        void createApiAgent_missingSpec_returnsError() {
                String result = tools.createApIAgent("Agent", "prompt", null,
                                null, null, "key", null, null, null, null, null, null, null);
                assertTrue(result.contains("error"));
                assertTrue(result.contains("OpenAPI spec is required"));
        }

        @Test
        void createApiAgent_missingApiKey_returnsError() {
                String result = tools.createApIAgent("Agent", "prompt", SIMPLE_SPEC,
                                null, null, null, null, null, null, null, null, null, null);
                assertTrue(result.contains("error"));
                assertTrue(result.contains("API key is required"));
        }

        @Test
        void createApiAgent_packageContainsApiCallsExtensions() throws Exception {
                when(httpCallsStore.createApiCalls(any()))
                                .thenReturn(Response.created(URI.create("/apicallstore/apicalls/hc-1?version=1"))
                                                .build())
                                .thenReturn(Response.created(URI.create("/apicallstore/apicalls/hc-2?version=1"))
                                                .build());
                when(behaviorStore.createRuleSet(any()))
                                .thenReturn(Response.created(URI.create("/rulestore/rulesets/beh-1?version=1"))
                                                .build());
                when(langchainStore.createLlm(any()))
                                .thenReturn(Response.created(URI.create("/llmstore/llms/lc-1?version=1"))
                                                .build());
                when(WorkflowStore.createWorkflow(any()))
                                .thenReturn(Response.created(URI.create("/workflowstore/workflows/pkg-1?version=1"))
                                                .build());
                when(AgentStore.createAgent(any()))
                                .thenReturn(Response.created(URI.create("/agentstore/agents/agent-1?version=1"))
                                                .build());

                tools.createApIAgent("Agent", "prompt", SIMPLE_SPEC,
                                null, null, "key", null, null, null, null, null, false, null);

                var packageCaptor = ArgumentCaptor.forClass(WorkflowConfiguration.class);
                verify(WorkflowStore).createWorkflow(packageCaptor.capture());

                var pkgConfig = packageCaptor.getValue();
                // Should have 5 extensions: parser + behavior + 2 httpcalls groups + langchain
                assertEquals(5, pkgConfig.getWorkflowSteps().size());
                assertEquals(URI.create("eddi://ai.labs.parser"), pkgConfig.getWorkflowSteps().get(0).getType());
                assertEquals(URI.create("eddi://ai.labs.rules"), pkgConfig.getWorkflowSteps().get(1).getType());
                assertEquals(URI.create("eddi://ai.labs.apicalls"), pkgConfig.getWorkflowSteps().get(2).getType());
                assertEquals(URI.create("eddi://ai.labs.apicalls"), pkgConfig.getWorkflowSteps().get(3).getType());
                assertEquals(URI.create("eddi://ai.labs.llm"), pkgConfig.getWorkflowSteps().get(4).getType());
        }
}
