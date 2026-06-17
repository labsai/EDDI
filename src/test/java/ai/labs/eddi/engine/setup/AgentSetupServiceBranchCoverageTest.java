/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.setup;

import ai.labs.eddi.engine.api.IRestAgentAdministration;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.runtime.client.factory.IRestInterfaceFactory;
import ai.labs.eddi.engine.runtime.client.factory.RestInterfaceFactory;
import ai.labs.eddi.secrets.ISecretProvider;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;

@DisplayName("AgentSetupService — Branch Coverage")
class AgentSetupServiceBranchCoverageTest {

    @Mock
    private IRestInterfaceFactory restInterfaceFactory;
    @Mock
    private IRestAgentAdministration agentAdmin;
    @Mock
    private ISecretProvider secretProvider;

    private AgentSetupService service;

    @BeforeEach
    void setUp() {
        openMocks(this);
        service = new AgentSetupService(restInterfaceFactory, agentAdmin, secretProvider, "http://localhost:11434");
    }

    // ─── parseEnvironment ────────────────────────────────────────────────

    @Nested
    @DisplayName("parseEnvironment")
    class ParseEnvironment {

        @Test
        @DisplayName("null returns production")
        void nullEnv() {
            assertEquals(Deployment.Environment.production, AgentSetupService.parseEnvironment(null));
        }

        @Test
        @DisplayName("blank returns production")
        void blankEnv() {
            assertEquals(Deployment.Environment.production, AgentSetupService.parseEnvironment("  "));
        }

        @Test
        @DisplayName("'test' returns test")
        void testEnv() {
            assertEquals(Deployment.Environment.test, AgentSetupService.parseEnvironment("test"));
        }

        @Test
        @DisplayName("'PRODUCTION' returns production")
        void productionUpperCase() {
            assertEquals(Deployment.Environment.production, AgentSetupService.parseEnvironment("PRODUCTION"));
        }

        @Test
        @DisplayName("invalid value returns production")
        void invalidEnv() {
            assertEquals(Deployment.Environment.production, AgentSetupService.parseEnvironment("staging"));
        }
    }

    // ─── extractIdFromLocation ───────────────────────────────────────────

    @Nested
    @DisplayName("extractIdFromLocation")
    class ExtractId {

        @Test
        @DisplayName("null returns null")
        void nullLocation() {
            assertNull(AgentSetupService.extractIdFromLocation(null));
        }

        @Test
        @DisplayName("blank returns null")
        void blankLocation() {
            assertNull(AgentSetupService.extractIdFromLocation("  "));
        }

        @Test
        @DisplayName("normal location extracts ID")
        void normalLocation() {
            assertEquals("abc123", AgentSetupService.extractIdFromLocation("/store/resources/abc123?version=1"));
        }

        @Test
        @DisplayName("location without query")
        void noQuery() {
            assertEquals("myId", AgentSetupService.extractIdFromLocation("/store/resources/myId"));
        }

        @Test
        @DisplayName("trailing slash returns null")
        void trailingSlash() {
            assertNull(AgentSetupService.extractIdFromLocation("/store/resources/"));
        }
    }

    // ─── extractVersionFromLocation ──────────────────────────────────────

    @Nested
    @DisplayName("extractVersionFromLocation")
    class ExtractVersion {

        @Test
        @DisplayName("null returns 1")
        void nullLocation() {
            assertEquals(1, AgentSetupService.extractVersionFromLocation(null));
        }

        @Test
        @DisplayName("no version param returns 1")
        void noVersion() {
            assertEquals(1, AgentSetupService.extractVersionFromLocation("/store/resources/abc"));
        }

        @Test
        @DisplayName("version=3 returns 3")
        void normalVersion() {
            assertEquals(3, AgentSetupService.extractVersionFromLocation("/store/resources/abc?version=3"));
        }

        @Test
        @DisplayName("version with trailing &param returns correctly")
        void versionWithAmpersand() {
            assertEquals(5, AgentSetupService.extractVersionFromLocation("/store/resources/abc?version=5&other=foo"));
        }

        @Test
        @DisplayName("invalid version number returns 1")
        void invalidVersion() {
            assertEquals(1, AgentSetupService.extractVersionFromLocation("/store/resources/abc?version=notanumber"));
        }
    }

    // ─── isLocalLlmProvider ──────────────────────────────────────────────

    @Nested
    @DisplayName("isLocalLlmProvider")
    class IsLocalLlm {

        @Test
        @DisplayName("null returns false")
        void nullProvider() {
            assertFalse(AgentSetupService.isLocalLlmProvider(null));
        }

        @Test
        @DisplayName("blank returns false")
        void blankProvider() {
            assertFalse(AgentSetupService.isLocalLlmProvider("  "));
        }

        @Test
        @DisplayName("ollama returns true")
        void ollama() {
            assertTrue(AgentSetupService.isLocalLlmProvider("ollama"));
        }

        @Test
        @DisplayName("jlama returns true")
        void jlama() {
            assertTrue(AgentSetupService.isLocalLlmProvider("jlama"));
        }

        @Test
        @DisplayName("bedrock returns true")
        void bedrock() {
            assertTrue(AgentSetupService.isLocalLlmProvider("bedrock"));
        }

        @Test
        @DisplayName("oracle-genai returns true")
        void oracleGenai() {
            assertTrue(AgentSetupService.isLocalLlmProvider("oracle-genai"));
        }

        @Test
        @DisplayName("OLLAMA (uppercase) returns true")
        void ollamaUpperCase() {
            assertTrue(AgentSetupService.isLocalLlmProvider("OLLAMA"));
        }

        @Test
        @DisplayName("openai returns false")
        void openai() {
            assertFalse(AgentSetupService.isLocalLlmProvider("openai"));
        }

        @Test
        @DisplayName("anthropic returns false")
        void anthropic() {
            assertFalse(AgentSetupService.isLocalLlmProvider("anthropic"));
        }
    }

    // ─── supportsResponseFormat ──────────────────────────────────────────

    @Nested
    @DisplayName("supportsResponseFormat")
    class SupportsResponseFormat {

        @Test
        @DisplayName("openai supports response format")
        void openai() {
            assertTrue(AgentSetupService.supportsResponseFormat("openai"));
        }

        @Test
        @DisplayName("mistral supports response format")
        void mistral() {
            assertTrue(AgentSetupService.supportsResponseFormat("mistral"));
        }

        @Test
        @DisplayName("azure-openai supports response format")
        void azureOpenai() {
            assertTrue(AgentSetupService.supportsResponseFormat("azure-openai"));
        }

        @Test
        @DisplayName("anthropic does not support response format")
        void anthropic() {
            assertFalse(AgentSetupService.supportsResponseFormat("anthropic"));
        }

        @Test
        @DisplayName("gemini does not support response format")
        void gemini() {
            assertFalse(AgentSetupService.supportsResponseFormat("gemini"));
        }

        @Test
        @DisplayName("ollama does not support response format")
        void ollama() {
            assertFalse(AgentSetupService.supportsResponseFormat("ollama"));
        }
    }

    // ─── buildPromptResponseJson ─────────────────────────────────────────

    @Nested
    @DisplayName("buildPromptResponseJson")
    class BuildPromptResponseJson {

        @Test
        @DisplayName("neither quick replies nor sentiment returns null")
        void neitherEnabled() {
            assertNull(AgentSetupService.buildPromptResponseJson(false, false));
        }

        @Test
        @DisplayName("quick replies only returns JSON with quickReplies")
        void quickRepliesOnly() {
            String result = AgentSetupService.buildPromptResponseJson(true, false);
            assertNotNull(result);
            assertTrue(result.contains("quickReplies"));
            assertTrue(result.contains("htmlResponseText"));
            assertFalse(result.contains("sentiment"));
        }

        @Test
        @DisplayName("sentiment only returns JSON with sentiment")
        void sentimentOnly() {
            String result = AgentSetupService.buildPromptResponseJson(false, true);
            assertNotNull(result);
            assertTrue(result.contains("sentiment"));
            assertTrue(result.contains("htmlResponseText"));
            assertFalse(result.contains("quickReplies"));
        }

        @Test
        @DisplayName("both enabled returns JSON with both")
        void bothEnabled() {
            String result = AgentSetupService.buildPromptResponseJson(true, true);
            assertNotNull(result);
            assertTrue(result.contains("quickReplies"));
            assertTrue(result.contains("sentiment"));
            assertTrue(result.contains("htmlResponseText"));
        }
    }

    // ─── resolveParams ──────────────────────────────────────────────────

    @Nested
    @DisplayName("resolveParams")
    class ResolveParams {

        @Test
        @DisplayName("null provider defaults to anthropic")
        void nullProvider() {
            var params = service.resolveParams(null, null, null, null);
            assertEquals("anthropic", params.providerType());
            assertEquals("claude-sonnet-4-6", params.modelId());
            assertTrue(params.shouldDeploy());
            assertEquals(Deployment.Environment.production, params.env());
        }

        @Test
        @DisplayName("blank provider defaults to anthropic")
        void blankProvider() {
            var params = service.resolveParams("  ", "  ", null, null);
            assertEquals("anthropic", params.providerType());
            assertEquals("claude-sonnet-4-6", params.modelId());
        }

        @Test
        @DisplayName("explicit provider and model used")
        void explicitProviderAndModel() {
            var params = service.resolveParams("openai", "gpt-4", true, "test");
            assertEquals("openai", params.providerType());
            assertEquals("gpt-4", params.modelId());
            assertTrue(params.shouldDeploy());
            assertEquals(Deployment.Environment.test, params.env());
        }

        @Test
        @DisplayName("deploy=false disables deploy")
        void deployFalse() {
            var params = service.resolveParams("openai", "gpt-4", false, null);
            assertFalse(params.shouldDeploy());
        }
    }

    // ─── setupAgent validation ──────────────────────────────────────────

    @Nested
    @DisplayName("setupAgent — validation")
    class SetupAgentValidation {

        @Test
        @DisplayName("null agent name throws")
        void nullAgentName() {
            var req = new SetupAgentRequest(null, "prompt", "anthropic", "model",
                    "key", null, null, null, null, null, null, null, null, null);
            assertThrows(AgentSetupService.AgentSetupException.class, () -> service.setupAgent(req));
        }

        @Test
        @DisplayName("blank agent name throws")
        void blankAgentName() {
            var req = new SetupAgentRequest("  ", "prompt", "anthropic", "model",
                    "key", null, null, null, null, null, null, null, null, null);
            assertThrows(AgentSetupService.AgentSetupException.class, () -> service.setupAgent(req));
        }

        @Test
        @DisplayName("null system prompt throws")
        void nullSystemPrompt() {
            var req = new SetupAgentRequest("Agent", null, "anthropic", "model",
                    "key", null, null, null, null, null, null, null, null, null);
            assertThrows(AgentSetupService.AgentSetupException.class, () -> service.setupAgent(req));
        }

        @Test
        @DisplayName("blank system prompt throws")
        void blankSystemPrompt() {
            var req = new SetupAgentRequest("Agent", "  ", "anthropic", "model",
                    "key", null, null, null, null, null, null, null, null, null);
            assertThrows(AgentSetupService.AgentSetupException.class, () -> service.setupAgent(req));
        }

        @Test
        @DisplayName("cloud provider without API key throws")
        void cloudProviderNoApiKey() {
            var req = new SetupAgentRequest("Agent", "prompt", "openai", "gpt-4",
                    null, null, null, null, null, null, null, null, null, null);
            assertThrows(AgentSetupService.AgentSetupException.class, () -> service.setupAgent(req));
        }

        @Test
        @DisplayName("cloud provider with blank API key throws")
        void cloudProviderBlankApiKey() {
            var req = new SetupAgentRequest("Agent", "prompt", "anthropic", "model",
                    "  ", null, null, null, null, null, null, null, null, null);
            assertThrows(AgentSetupService.AgentSetupException.class, () -> service.setupAgent(req));
        }

        @Test
        @DisplayName("local provider (ollama) without API key does NOT throw for validation")
        void localProviderNoApiKeyOk() throws Exception {
            var req = new SetupAgentRequest("Agent", "prompt", "ollama", "llama3",
                    null, null, null, null, null, null, null, null, false, null);
            // Will fail at REST call, but validation should pass
            when(restInterfaceFactory.get(any())).thenThrow(new RestInterfaceFactory.RestInterfaceFactoryException("mock", new RuntimeException()));
            assertThrows(AgentSetupService.AgentSetupException.class, () -> service.setupAgent(req));
        }
    }

    // ─── deployAndWait branches ──────────────────────────────────────────

    @Nested
    @DisplayName("deployAndWait")
    class DeployAndWait {

        @Test
        @DisplayName("HTTP 200 with READY status")
        void http200Ready() {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = Map.of("status", "READY");
            Response response = mock(Response.class);
            when(response.getStatus()).thenReturn(200);
            when(response.getEntity()).thenReturn(body);
            when(agentAdmin.deployAgent(any(), anyString(), anyInt(), anyBoolean(), anyBoolean())).thenReturn(response);

            var result = service.deployAndWait(Deployment.Environment.production, "agent1", 1);
            assertEquals(true, result.get("deployed"));
            assertEquals("READY", result.get("deploymentStatus"));
        }

        @Test
        @DisplayName("HTTP 200 with ERROR status includes error")
        void http200ErrorStatus() {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = Map.of("status", "ERROR", "error", "LLM unreachable");
            Response response = mock(Response.class);
            when(response.getStatus()).thenReturn(200);
            when(response.getEntity()).thenReturn(body);
            when(agentAdmin.deployAgent(any(), anyString(), anyInt(), anyBoolean(), anyBoolean())).thenReturn(response);

            var result = service.deployAndWait(Deployment.Environment.production, "agent1", 1);
            assertFalse((Boolean) result.get("deployed"));
            assertEquals("ERROR", result.get("deploymentStatus"));
            assertNotNull(result.get("deployWarning"));
        }

        @Test
        @DisplayName("HTTP 200 with null body → parse error branch")
        void http200NullBody() {
            Response response = mock(Response.class);
            when(response.getStatus()).thenReturn(200);
            when(response.getEntity()).thenThrow(new ClassCastException("not a map"));
            when(agentAdmin.deployAgent(any(), anyString(), anyInt(), anyBoolean(), anyBoolean())).thenReturn(response);

            var result = service.deployAndWait(Deployment.Environment.production, "agent1", 1);
            assertEquals(false, result.get("deployed"));
            assertEquals("UNKNOWN", result.get("deploymentStatus"));
        }

        @Test
        @DisplayName("HTTP 202 → in progress")
        void http202() {
            Response response = mock(Response.class);
            when(response.getStatus()).thenReturn(202);
            when(agentAdmin.deployAgent(any(), anyString(), anyInt(), anyBoolean(), anyBoolean())).thenReturn(response);

            var result = service.deployAndWait(Deployment.Environment.production, "agent1", 1);
            assertEquals(false, result.get("deployed"));
            assertEquals("IN_PROGRESS", result.get("deploymentStatus"));
        }

        @Test
        @DisplayName("HTTP 500 → unexpected status")
        void http500() {
            Response response = mock(Response.class);
            when(response.getStatus()).thenReturn(500);
            when(agentAdmin.deployAgent(any(), anyString(), anyInt(), anyBoolean(), anyBoolean())).thenReturn(response);

            var result = service.deployAndWait(Deployment.Environment.production, "agent1", 1);
            assertEquals(false, result.get("deployed"));
            assertNotNull(result.get("deployError"));
        }

        @Test
        @DisplayName("deploy throws exception")
        void deployException() {
            when(agentAdmin.deployAgent(any(), anyString(), anyInt(), anyBoolean(), anyBoolean()))
                    .thenThrow(new RuntimeException("network error"));

            var result = service.deployAndWait(Deployment.Environment.production, "agent1", 1);
            assertEquals(false, result.get("deployed"));
            assertNotNull(result.get("deployError"));
        }
    }

    // ─── createLlmConfig branches ────────────────────────────────────────

    @Nested
    @DisplayName("createLlmConfig — provider branches")
    class CreateLlmConfig {

        @Test
        @DisplayName("ollama provider sets baseUrl")
        void ollamaProvider() {
            var config = service.createLlmConfig("ollama", "llama3", null, "prompt",
                    false, null, null, null, false, false, null);
            assertNotNull(config);
            assertEquals(1, config.tasks().size());
            var params = config.tasks().get(0).getParameters();
            assertEquals("llama3", params.get("model"));
            assertEquals("http://localhost:11434", params.get("baseUrl"));
        }

        @Test
        @DisplayName("ollama with custom baseUrl")
        void ollamaCustomBaseUrl() {
            var config = service.createLlmConfig("ollama", "llama3", null, "prompt",
                    false, null, "http://custom:1234", null, false, false, null);
            var params = config.tasks().get(0).getParameters();
            assertEquals("http://custom:1234", params.get("baseUrl"));
        }

        @Test
        @DisplayName("jlama provider sets modelName and authToken")
        void jlamaProvider() {
            var config = service.createLlmConfig("jlama", "model-x", "mytoken", "prompt",
                    false, null, null, null, false, false, null);
            var params = config.tasks().get(0).getParameters();
            assertEquals("model-x", params.get("modelName"));
            assertEquals("mytoken", params.get("authToken"));
        }

        @Test
        @DisplayName("jlama without API key does not set authToken")
        void jlamaNoApiKey() {
            var config = service.createLlmConfig("jlama", "model-x", null, "prompt",
                    false, null, null, null, false, false, null);
            var params = config.tasks().get(0).getParameters();
            assertNull(params.get("authToken"));
        }

        @Test
        @DisplayName("bedrock provider sets modelId")
        void bedrockProvider() {
            var config = service.createLlmConfig("bedrock", "anthropic.claude-3", null, "prompt",
                    false, null, null, null, false, false, null);
            var params = config.tasks().get(0).getParameters();
            assertEquals("anthropic.claude-3", params.get("modelId"));
        }

        @Test
        @DisplayName("azure-openai sets deploymentName, apiKey, endpoint, responseFormat")
        void azureOpenai() {
            String promptJson = "some json format";
            var config = service.createLlmConfig("azure-openai", "gpt-4", "mykey", "prompt",
                    false, null, "https://myaoi.openai.azure.com", promptJson, false, false, null);
            var params = config.tasks().get(0).getParameters();
            assertEquals("gpt-4", params.get("deploymentName"));
            assertEquals("mykey", params.get("apiKey"));
            assertEquals("https://myaoi.openai.azure.com", params.get("endpoint"));
            assertEquals("json", params.get("responseFormat"));
        }

        @Test
        @DisplayName("oracle-genai sets modelName")
        void oracleGenai() {
            var config = service.createLlmConfig("oracle-genai", "cohere.command", null, "prompt",
                    false, null, null, null, false, false, null);
            var params = config.tasks().get(0).getParameters();
            assertEquals("cohere.command", params.get("modelName"));
        }

        @Test
        @DisplayName("default provider (anthropic) sets modelName, apiKey, responseFormat if json")
        void defaultProviderWithJson() {
            // openai is in 'supportsResponseFormat' so it takes the default branch but with
            // responseFormat
            var config = service.createLlmConfig("openai", "gpt-4", "sk-key", "prompt",
                    false, null, "https://custom.api.com", "json schema", false, false, null);
            var params = config.tasks().get(0).getParameters();
            assertEquals("gpt-4", params.get("modelName"));
            assertEquals("sk-key", params.get("apiKey"));
            assertEquals("https://custom.api.com", params.get("baseUrl"));
            assertEquals("json", params.get("responseFormat"));
        }

        @Test
        @DisplayName("default provider without baseUrl does not set baseUrl")
        void defaultProviderNoBaseUrl() {
            var config = service.createLlmConfig("anthropic", "claude-3", "key", "prompt",
                    false, null, null, null, false, false, null);
            var params = config.tasks().get(0).getParameters();
            assertNull(params.get("baseUrl"));
        }

        @Test
        @DisplayName("tooling enabled with whitelist")
        void toolingWithWhitelist() {
            var config = service.createLlmConfig("anthropic", "claude-3", "key", "prompt",
                    true, "tool1, tool2, tool3", null, null, false, false, null);
            var task = config.tasks().get(0);
            assertTrue(task.getEnableBuiltInTools());
            assertNotNull(task.getBuiltInToolsWhitelist());
            assertEquals(3, task.getBuiltInToolsWhitelist().size());
        }

        @Test
        @DisplayName("tooling enabled without whitelist")
        void toolingNoWhitelist() {
            var config = service.createLlmConfig("anthropic", "claude-3", "key", "prompt",
                    true, null, null, null, false, false, null);
            var task = config.tasks().get(0);
            assertTrue(task.getEnableBuiltInTools());
        }

        @Test
        @DisplayName("tool URIs set tools list")
        void toolUris() {
            var uris = java.util.List.of("/httpcalls/loc1", "/httpcalls/loc2");
            var config = service.createLlmConfig("anthropic", "claude-3", "key", "prompt",
                    false, null, null, null, false, false, uris);
            var task = config.tasks().get(0);
            assertEquals(uris, task.getTools());
        }

        @Test
        @DisplayName("promptResponseJson sets postResponse and addToOutput=false")
        void promptResponseJsonSetsPostResponse() {
            var config = service.createLlmConfig("anthropic", "claude-3", "key", "prompt",
                    false, null, null, "json format", true, false, null);
            var task = config.tasks().get(0);
            assertNotNull(task.getPostResponse());
            assertEquals("false", task.getParameters().get("addToOutput"));
            assertEquals("true", task.getParameters().get("convertToObject"));
        }
    }

    // ─── buildPostResponse ───────────────────────────────────────────────

    @Nested
    @DisplayName("buildPostResponse")
    class BuildPostResponse {

        @Test
        @DisplayName("without quick replies — no QR instructions")
        void noQuickReplies() {
            var postResponse = service.buildPostResponse(false, false);
            assertNotNull(postResponse.getOutputBuildInstructions());
            assertNull(postResponse.getQrBuildInstructions());
        }

        @Test
        @DisplayName("with quick replies — has QR instructions")
        void withQuickReplies() {
            var postResponse = service.buildPostResponse(true, false);
            assertNotNull(postResponse.getQrBuildInstructions());
            assertEquals(1, postResponse.getQrBuildInstructions().size());
        }
    }

    // ─── createWorkflowConfig branches ───────────────────────────────────

    @Nested
    @DisplayName("createWorkflowConfig")
    class CreateWorkflowConfig {

        @Test
        @DisplayName("all locations provided — full pipeline")
        void fullPipeline() {
            var config = service.createWorkflowConfig(
                    "/parser/loc", "/behavior/loc",
                    java.util.List.of("/http1", "/http2"),
                    java.util.List.of("/mcp1"),
                    "/langchain/loc", "/output/loc");
            // parser + behavior + 2 httpcalls + 1 mcpcalls + langchain + output = 7
            assertEquals(7, config.getWorkflowSteps().size());
        }

        @Test
        @DisplayName("null parser location — skipped")
        void nullParser() {
            var config = service.createWorkflowConfig(
                    null, "/behavior/loc",
                    null, null, "/langchain/loc", null);
            // behavior + langchain = 2
            assertEquals(2, config.getWorkflowSteps().size());
        }

        @Test
        @DisplayName("null output location — skipped")
        void nullOutput() {
            var config = service.createWorkflowConfig(
                    "/parser/loc", "/behavior/loc",
                    null, null, "/langchain/loc", null);
            // parser + behavior + langchain = 3
            assertEquals(3, config.getWorkflowSteps().size());
        }
    }

    // ─── vaultApiKey (private, tested via reflection) ────────────────────

    @Nested
    @DisplayName("vaultApiKey")
    class VaultApiKey {

        private String invokeVaultApiKey(String apiKey, String agentName) throws Exception {
            var method = AgentSetupService.class.getDeclaredMethod("vaultApiKey", String.class, String.class);
            method.setAccessible(true);
            return (String) method.invoke(service, apiKey, agentName);
        }

        @Test
        @DisplayName("vault reference is passed through as-is (not re-vaulted)")
        void passthroughVaultReference() throws Exception {
            String vaultRef = "${vault:anthropic-api-key}";

            String result = invokeVaultApiKey(vaultRef, "MyAgent");

            assertEquals(vaultRef, result, "Already-vaulted reference should be returned unchanged");
            // Should NOT attempt to store anything
            verifyNoInteractions(secretProvider);
        }

        @Test
        @DisplayName("null apiKey returns null")
        void nullApiKey() throws Exception {
            String result = invokeVaultApiKey(null, "MyAgent");

            assertNull(result);
            verifyNoInteractions(secretProvider);
        }

        @Test
        @DisplayName("blank apiKey returns blank")
        void blankApiKey() throws Exception {
            String result = invokeVaultApiKey("   ", "MyAgent");

            assertEquals("   ", result);
            verifyNoInteractions(secretProvider);
        }
    }
}
