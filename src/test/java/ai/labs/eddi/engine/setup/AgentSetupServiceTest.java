/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.setup;

import ai.labs.eddi.configs.mcpcalls.model.McpCallsConfiguration;
import ai.labs.eddi.configs.output.model.OutputConfigurationSet;
import ai.labs.eddi.configs.parser.model.ParserConfiguration;
import ai.labs.eddi.configs.rules.model.RuleSetConfiguration;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Tests for {@link AgentSetupService} — pure logic, config builders, and static
 * utilities. Does NOT test the full setupAgent/createApiAgent flow (those
 * require REST stores).
 */
@DisplayName("AgentSetupService")
class AgentSetupServiceTest {

    private AgentSetupService service;

    @BeforeEach
    void setUp() {
        service = new AgentSetupService(
                mock(ai.labs.eddi.engine.runtime.client.factory.IRestInterfaceFactory.class),
                mock(ai.labs.eddi.engine.api.IRestAgentAdministration.class),
                mock(ai.labs.eddi.secrets.ISecretProvider.class),
                "http://localhost:11434");
    }

    // ==================== Static Utility Methods ====================

    @Nested
    @DisplayName("extractIdFromLocation")
    class ExtractIdTests {

        @Test
        @DisplayName("extracts ID from standard location path")
        void standardPath() {
            assertEquals("abc123", AgentSetupService.extractIdFromLocation(
                    "/configstore/parsers/abc123?version=1"));
        }

        @Test
        @DisplayName("extracts ID without query params")
        void noQueryParams() {
            assertEquals("def456", AgentSetupService.extractIdFromLocation(
                    "/configstore/agents/def456"));
        }

        @Test
        @DisplayName("returns null for null input")
        void nullInput() {
            assertNull(AgentSetupService.extractIdFromLocation(null));
        }

        @Test
        @DisplayName("returns null for blank input")
        void blankInput() {
            assertNull(AgentSetupService.extractIdFromLocation("   "));
        }

        @Test
        @DisplayName("handles trailing slash")
        void trailingSlash() {
            // path ends at last slash with nothing after
            assertNull(AgentSetupService.extractIdFromLocation("/path/"));
        }
    }

    @Nested
    @DisplayName("extractVersionFromLocation")
    class ExtractVersionTests {

        @Test
        @DisplayName("extracts version from query string")
        void standardVersion() {
            assertEquals(3, AgentSetupService.extractVersionFromLocation(
                    "/parsers/abc?version=3"));
        }

        @Test
        @DisplayName("defaults to 1 when no version param")
        void noVersion() {
            assertEquals(1, AgentSetupService.extractVersionFromLocation(
                    "/parsers/abc"));
        }

        @Test
        @DisplayName("defaults to 1 for null location")
        void nullLocation() {
            assertEquals(1, AgentSetupService.extractVersionFromLocation(null));
        }

        @Test
        @DisplayName("handles version with additional params")
        void multipleParams() {
            assertEquals(5, AgentSetupService.extractVersionFromLocation(
                    "/parsers/abc?version=5&other=foo"));
        }

        @Test
        @DisplayName("defaults to 1 for malformed version")
        void malformedVersion() {
            assertEquals(1, AgentSetupService.extractVersionFromLocation(
                    "/parsers/abc?version=notanumber"));
        }
    }

    @Nested
    @DisplayName("isLocalLlmProvider")
    class LocalLlmProviderTests {

        @ParameterizedTest
        @ValueSource(strings = {"ollama", "jlama", "bedrock", "oracle-genai",
                "OLLAMA", "Jlama", "BEDROCK", "Oracle-GenAI"})
        @DisplayName("returns true for local/keyless providers")
        void localProviders(String provider) {
            assertTrue(AgentSetupService.isLocalLlmProvider(provider));
        }

        @ParameterizedTest
        @ValueSource(strings = {"openai", "anthropic", "gemini", "mistral", "azure-openai"})
        @DisplayName("returns false for cloud providers requiring API key")
        void cloudProviders(String provider) {
            assertFalse(AgentSetupService.isLocalLlmProvider(provider));
        }

        @ParameterizedTest
        @NullAndEmptySource
        @DisplayName("returns false for null/empty")
        void nullEmpty(String provider) {
            assertFalse(AgentSetupService.isLocalLlmProvider(provider));
        }
    }

    @Nested
    @DisplayName("supportsResponseFormat")
    class ResponseFormatTests {

        @ParameterizedTest
        @ValueSource(strings = {"openai", "mistral", "azure-openai"})
        @DisplayName("returns true for supported providers")
        void supported(String modelType) {
            assertTrue(AgentSetupService.supportsResponseFormat(modelType));
        }

        @ParameterizedTest
        @ValueSource(strings = {"anthropic", "gemini", "ollama", "bedrock"})
        @DisplayName("returns false for unsupported providers")
        void unsupported(String modelType) {
            assertFalse(AgentSetupService.supportsResponseFormat(modelType));
        }
    }

    @Nested
    @DisplayName("parseEnvironment")
    class ParseEnvironmentTests {

        @Test
        @DisplayName("returns production for null input")
        void nullInput() {
            assertEquals(Deployment.Environment.production,
                    AgentSetupService.parseEnvironment(null));
        }

        @Test
        @DisplayName("returns production for blank input")
        void blankInput() {
            assertEquals(Deployment.Environment.production,
                    AgentSetupService.parseEnvironment(""));
        }

        @Test
        @DisplayName("parses 'production' correctly")
        void production() {
            assertEquals(Deployment.Environment.production,
                    AgentSetupService.parseEnvironment("production"));
        }

        @Test
        @DisplayName("parses 'test' correctly")
        void testEnv() {
            assertEquals(Deployment.Environment.test,
                    AgentSetupService.parseEnvironment("test"));
        }

        @Test
        @DisplayName("returns production for invalid environment")
        void invalidEnvironment() {
            assertEquals(Deployment.Environment.production,
                    AgentSetupService.parseEnvironment("invalid_env"));
        }
    }

    @Nested
    @DisplayName("resolveParams")
    class ResolveParamsTests {

        @Test
        @DisplayName("defaults provider to 'anthropic' when null")
        void defaultProvider() {
            var params = service.resolveParams(null, null, null, null);
            assertEquals("anthropic", params.providerType());
        }

        @Test
        @DisplayName("defaults model to 'claude-sonnet-4-6' when null")
        void defaultModel() {
            var params = service.resolveParams(null, null, null, null);
            assertEquals("claude-sonnet-4-6", params.modelId());
        }

        @Test
        @DisplayName("defaults deploy to true when null")
        void defaultDeploy() {
            var params = service.resolveParams(null, null, null, null);
            assertTrue(params.shouldDeploy());
        }

        @Test
        @DisplayName("respects explicit deploy=false")
        void deployFalse() {
            var params = service.resolveParams("openai", "gpt-4", false, null);
            assertFalse(params.shouldDeploy());
        }

        @Test
        @DisplayName("normalizes provider to lowercase")
        void providerNormalized() {
            var params = service.resolveParams("OpenAI", "gpt-4", null, null);
            assertEquals("openai", params.providerType());
        }
    }

    // ==================== Config Builders ====================

    @Nested
    @DisplayName("Config Builders")
    class ConfigBuilderTests {

        @Test
        @DisplayName("createParserConfig returns valid parser configuration")
        void parserConfig() {
            ParserConfiguration config = service.createParserConfig();
            assertNotNull(config);
            assertNotNull(config.getExtensions());
            assertTrue(config.getExtensions().containsKey("dictionaries"));
            assertTrue(config.getExtensions().containsKey("corrections"));
        }

        @Test
        @DisplayName("createBehaviorConfig creates catch-all rule with send_message action")
        void behaviorConfig() {
            RuleSetConfiguration config = service.createBehaviorConfig();
            assertNotNull(config);
            assertTrue(config.getExpressionsAsActions());
            assertFalse(config.getBehaviorGroups().isEmpty());
            var firstRule = config.getBehaviorGroups().getFirst().getRules().getFirst();
            assertEquals("Send Message to LLM", firstRule.getName());
            assertTrue(firstRule.getActions().contains("send_message"));
        }

        @Test
        @DisplayName("createMcpCallsConfig sets URL and defaults")
        void mcpCallsConfig() {
            McpCallsConfiguration config = service.createMcpCallsConfig("http://mcp.local:7070");
            assertEquals("http://mcp.local:7070", config.getMcpServerUrl());
            assertEquals("http", config.getTransport());
            assertEquals(30000L, config.getTimeoutMs());
        }

        @Test
        @DisplayName("createOutputConfig creates CONVERSATION_START output set")
        void outputConfig() {
            OutputConfigurationSet config = service.createOutputConfig("Hello, I'm your assistant!");
            assertNotNull(config);
            assertFalse(config.getOutputSet().isEmpty());
            assertEquals("CONVERSATION_START", config.getOutputSet().getFirst().getAction());
        }

        @Test
        @DisplayName("createWorkflowConfig builds correct step order")
        void workflowConfig() {
            WorkflowConfiguration config = service.createWorkflowConfig(
                    "/parsers/p1", "/rules/r1",
                    List.of("/httpcalls/h1", "/httpcalls/h2"),
                    List.of("/mcpcalls/m1"),
                    "/llm/l1", "/output/o1");
            var steps = config.getWorkflowSteps();
            // Parser + behavior + 2 httpcalls + 1 mcpcall + langchain + output = 7
            assertEquals(7, steps.size());
            assertTrue(steps.get(0).getType().toString().contains("parser"));
            assertTrue(steps.get(1).getType().toString().contains("behavior"));
            assertTrue(steps.get(2).getType().toString().contains("httpcalls"));
            assertTrue(steps.get(3).getType().toString().contains("httpcalls"));
            assertTrue(steps.get(4).getType().toString().contains("mcpcalls"));
            assertTrue(steps.get(5).getType().toString().contains("llm"));
            assertTrue(steps.get(6).getType().toString().contains("output"));
        }

        @Test
        @DisplayName("createWorkflowConfig omits optional steps when null")
        void workflowConfigMinimal() {
            WorkflowConfiguration config = service.createWorkflowConfig(
                    "/parsers/p1", "/rules/r1",
                    null, null, "/llm/l1", null);
            // Parser + behavior + langchain = 3
            assertEquals(3, config.getWorkflowSteps().size());
        }
    }

    // ==================== buildPromptResponseJson ====================

    @Nested
    @DisplayName("buildPromptResponseJson")
    class PromptResponseJsonTests {

        @Test
        @DisplayName("returns null when both features disabled")
        void bothDisabled() {
            assertNull(AgentSetupService.buildPromptResponseJson(false, false));
        }

        @Test
        @DisplayName("includes quickReplies schema when enabled")
        void quickRepliesEnabled() {
            String json = AgentSetupService.buildPromptResponseJson(true, false);
            assertNotNull(json);
            assertTrue(json.contains("quickReplies"));
            assertTrue(json.contains("htmlResponseText"));
            assertFalse(json.contains("sentiment"));
        }

        @Test
        @DisplayName("includes sentiment schema when enabled")
        void sentimentEnabled() {
            String json = AgentSetupService.buildPromptResponseJson(false, true);
            assertNotNull(json);
            assertTrue(json.contains("sentiment"));
            assertTrue(json.contains("urgency"));
            assertTrue(json.contains("htmlResponseText"));
            assertFalse(json.contains("quickReplies"));
        }

        @Test
        @DisplayName("includes both schemas when both enabled")
        void bothEnabled() {
            String json = AgentSetupService.buildPromptResponseJson(true, true);
            assertNotNull(json);
            assertTrue(json.contains("quickReplies"));
            assertTrue(json.contains("sentiment"));
        }
    }

    // ==================== createLlmConfig ====================

    @Nested
    @DisplayName("createLlmConfig")
    class LlmConfigTests {

        @Test
        @DisplayName("creates config with basic parameters")
        void basicConfig() {
            LlmConfiguration config = service.createLlmConfig(
                    "openai", "gpt-4", "sk-key", "You are helpful", false, null, null, null, false, false, null);
            assertNotNull(config);
            assertFalse(config.tasks().isEmpty());
            var task = config.tasks().getFirst();
            assertEquals("openai", task.getType());
            assertEquals(List.of("send_message"), task.getActions());
            assertTrue(task.getParameters().get("systemMessage").contains("You are helpful"));
        }

        @Test
        @DisplayName("sets model name for openai provider")
        void openaiModel() {
            LlmConfiguration config = service.createLlmConfig(
                    "openai", "gpt-4o", "sk-key", "prompt", false, null, null, null, false, false, null);
            assertEquals("gpt-4o", config.tasks().getFirst().getParameters().get("modelName"));
        }

        @Test
        @DisplayName("sets model ID for ollama with correct baseUrl")
        void ollamaConfig() {
            LlmConfiguration config = service.createLlmConfig(
                    "ollama", "llama3", null, "prompt", false, null, null, null, false, false, null);
            var params = config.tasks().getFirst().getParameters();
            assertEquals("llama3", params.get("model"));
            assertEquals("http://localhost:11434", params.get("baseUrl"));
        }

        @Test
        @DisplayName("ollama uses custom baseUrl when provided")
        void ollamaCustomBaseUrl() {
            LlmConfiguration config = service.createLlmConfig(
                    "ollama", "llama3", null, "prompt", false, null, "http://my-ollama:11434", null, false, false, null);
            assertEquals("http://my-ollama:11434", config.tasks().getFirst().getParameters().get("baseUrl"));
        }

        @Test
        @DisplayName("enables built-in tools when requested")
        void toolsEnabled() {
            LlmConfiguration config = service.createLlmConfig(
                    "openai", "gpt-4", "key", "prompt", true, "calculator,websearch", null, null, false, false, null);
            var task = config.tasks().getFirst();
            assertTrue(task.getEnableBuiltInTools());
            assertEquals(List.of("calculator", "websearch"), task.getBuiltInToolsWhitelist());
        }

        @Test
        @DisplayName("sets tool URIs when provided")
        void toolUris() {
            LlmConfiguration config = service.createLlmConfig(
                    "openai", "gpt-4", "key", "prompt", false, null, null, null, false, false,
                    List.of("/httpcalls/h1", "/httpcalls/h2"));
            var task = config.tasks().getFirst();
            assertEquals(2, task.getTools().size());
        }

        @Test
        @DisplayName("adds postResponse when promptResponseJson is provided")
        void promptResponseJson() {
            String json = AgentSetupService.buildPromptResponseJson(true, false);
            LlmConfiguration config = service.createLlmConfig(
                    "openai", "gpt-4", "key", "prompt", false, null, null, json, true, false, null);
            var task = config.tasks().getFirst();
            assertNotNull(task.getPostResponse());
            assertEquals("aiOutput", task.getResponseObjectName());
        }

        @ParameterizedTest
        @CsvSource({"bedrock, modelId", "azure-openai, deploymentName", "jlama, modelName"})
        @DisplayName("uses correct parameter key per provider")
        void providerSpecificParamKeys(String provider, String expectedKey) {
            LlmConfiguration config = service.createLlmConfig(
                    provider, "test-model", "key", "prompt", false, null, null, null, false, false, null);
            assertTrue(config.tasks().getFirst().getParameters().containsKey(expectedKey));
        }
    }

    // ==================== buildPostResponse ====================

    @Nested
    @DisplayName("buildPostResponse")
    class PostResponseTests {

        @Test
        @DisplayName("always includes output building instruction")
        void alwaysHasOutput() {
            var response = service.buildPostResponse(false, false);
            assertNotNull(response.getOutputBuildInstructions());
            assertFalse(response.getOutputBuildInstructions().isEmpty());
        }

        @Test
        @DisplayName("includes quickReply instructions when enabled")
        void quickRepliesEnabled() {
            var response = service.buildPostResponse(true, false);
            assertNotNull(response.getQrBuildInstructions());
            assertFalse(response.getQrBuildInstructions().isEmpty());
        }

        @Test
        @DisplayName("no quickReply instructions when disabled")
        void quickRepliesDisabled() {
            var response = service.buildPostResponse(false, false);
            assertNull(response.getQrBuildInstructions());
        }
    }

    // ==================== Validation ====================

    @Nested
    @DisplayName("setupAgent validation")
    class ValidationTests {

        @Test
        @DisplayName("throws when agent name is null")
        void nullAgentName() {
            var request = new SetupAgentRequest(null, "prompt", "openai", "gpt-4",
                    "key", null, null, null, null, null, null, null, null, null);
            assertThrows(AgentSetupService.AgentSetupException.class,
                    () -> service.setupAgent(request));
        }

        @Test
        @DisplayName("throws when system prompt is blank")
        void blankPrompt() {
            var request = new SetupAgentRequest("Test Agent", "", "openai", "gpt-4",
                    "key", null, null, null, null, null, null, null, null, null);
            assertThrows(AgentSetupService.AgentSetupException.class,
                    () -> service.setupAgent(request));
        }

        @Test
        @DisplayName("throws when cloud provider has no API key")
        void cloudProviderNoApiKey() {
            var request = new SetupAgentRequest("Test Agent", "prompt", "openai", "gpt-4",
                    null, null, null, null, null, null, null, null, null, null);
            assertThrows(AgentSetupService.AgentSetupException.class,
                    () -> service.setupAgent(request));
        }

        @Test
        @DisplayName("does not throw for local provider without API key")
        void localProviderNoApiKey() {
            // ollama doesn't need an API key, but will fail at REST store call
            // — the validation itself should pass
            var request = new SetupAgentRequest("Test Agent", "prompt", "ollama", "llama3",
                    null, null, null, null, null, null, null, null, null, null);
            // Will throw AgentSetupException at the REST call level, not validation
            var ex = assertThrows(AgentSetupService.AgentSetupException.class,
                    () -> service.setupAgent(request));
            // Should NOT be "API key is required"
            assertFalse(ex.getMessage().contains("API key is required"));
        }
    }
}
