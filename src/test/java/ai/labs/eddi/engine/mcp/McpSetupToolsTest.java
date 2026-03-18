package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.configs.behavior.IRestBehaviorStore;
import ai.labs.eddi.configs.bots.IRestBotStore;
import ai.labs.eddi.configs.documentdescriptor.IRestDocumentDescriptorStore;
import ai.labs.eddi.configs.http.IRestHttpCallsStore;
import ai.labs.eddi.configs.langchain.IRestLangChainStore;
import ai.labs.eddi.configs.output.IRestOutputStore;
import ai.labs.eddi.configs.packages.IRestPackageStore;
import ai.labs.eddi.configs.packages.model.PackageConfiguration;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.IRestBotAdministration;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.engine.runtime.client.factory.IRestInterfaceFactory;
import ai.labs.eddi.modules.langchain.model.LangChainConfiguration;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class McpSetupToolsTest {

    private IRestBehaviorStore behaviorStore;
    private IRestLangChainStore langchainStore;
    private IRestOutputStore outputStore;
    private IRestHttpCallsStore httpCallsStore;
    private IRestPackageStore packageStore;
    private IRestBotStore botStore;
    private IRestDocumentDescriptorStore descriptorStore;
    private IRestBotAdministration botAdmin;
    private IJsonSerialization jsonSerialization;
    private McpSetupTools tools;

    @BeforeEach
    void setUp() throws Exception {
        behaviorStore = mock(IRestBehaviorStore.class);
        langchainStore = mock(IRestLangChainStore.class);
        outputStore = mock(IRestOutputStore.class);
        httpCallsStore = mock(IRestHttpCallsStore.class);
        packageStore = mock(IRestPackageStore.class);
        botStore = mock(IRestBotStore.class);
        descriptorStore = mock(IRestDocumentDescriptorStore.class);
        botAdmin = mock(IRestBotAdministration.class);
        jsonSerialization = mock(IJsonSerialization.class);

        // Wire store mocks through IRestInterfaceFactory
        var restInterfaceFactory = mock(IRestInterfaceFactory.class);
        when(restInterfaceFactory.get(IRestBehaviorStore.class)).thenReturn(behaviorStore);
        when(restInterfaceFactory.get(IRestLangChainStore.class)).thenReturn(langchainStore);
        when(restInterfaceFactory.get(IRestOutputStore.class)).thenReturn(outputStore);
        when(restInterfaceFactory.get(IRestHttpCallsStore.class)).thenReturn(httpCallsStore);
        when(restInterfaceFactory.get(IRestPackageStore.class)).thenReturn(packageStore);
        when(restInterfaceFactory.get(IRestBotStore.class)).thenReturn(botStore);
        when(restInterfaceFactory.get(IRestDocumentDescriptorStore.class)).thenReturn(descriptorStore);

        // Default serialization
        lenient().when(jsonSerialization.serialize(any())).thenReturn("{}");

        tools = new McpSetupTools(restInterfaceFactory, botAdmin, jsonSerialization);
    }

    @Test
    void setupBot_fullPipeline_createsAllResources() throws Exception {
        // Mock all store responses with Location headers
        when(behaviorStore.createBehaviorRuleSet(any()))
                .thenReturn(Response.created(URI.create("/behaviorstore/behaviorsets/beh-1?version=1")).build());
        when(langchainStore.createLangChain(any()))
                .thenReturn(Response.created(URI.create("/langchainstore/langchains/lc-1?version=1")).build());
        when(outputStore.createOutputSet(any()))
                .thenReturn(Response.created(URI.create("/outputstore/outputsets/out-1?version=1")).build());
        when(packageStore.createPackage(any()))
                .thenReturn(Response.created(URI.create("/packagestore/packages/pkg-1?version=1")).build());
        when(botStore.createBot(any()))
                .thenReturn(Response.created(URI.create("/botstore/bots/bot-1?version=1")).build());
        when(botAdmin.deployBot(any(), any(), anyInt(), anyBoolean(), anyBoolean()))
                .thenReturn(Response.ok().build());

        String result = tools.setupBot(
                "Test Bot", "You are helpful", "anthropic", "claude-sonnet-4-6",
                "sk-test", null, "Hello!", true, "calculator,datetime",
                null, null, true, "unrestricted");

        assertNotNull(result);

        // Verify all resources created in order
        verify(behaviorStore).createBehaviorRuleSet(any());
        verify(langchainStore).createLangChain(any());
        verify(outputStore).createOutputSet(any());
        verify(packageStore).createPackage(any());
        verify(botStore).createBot(any());
        verify(botAdmin).deployBot(Environment.unrestricted, "bot-1", 1, true, true);

        // Verify 5 descriptors patched (behavior, langchain, output, package, bot)
        verify(descriptorStore, times(5)).patchDescriptor(any(), anyInt(), any());
    }

    @Test
    void setupBot_withoutIntro_skipsOutput() throws Exception {
        when(behaviorStore.createBehaviorRuleSet(any()))
                .thenReturn(Response.created(URI.create("/behaviorstore/behaviorsets/beh-1?version=1")).build());
        when(langchainStore.createLangChain(any()))
                .thenReturn(Response.created(URI.create("/langchainstore/langchains/lc-1?version=1")).build());
        when(packageStore.createPackage(any()))
                .thenReturn(Response.created(URI.create("/packagestore/packages/pkg-1?version=1")).build());
        when(botStore.createBot(any()))
                .thenReturn(Response.created(URI.create("/botstore/bots/bot-1?version=1")).build());
        when(botAdmin.deployBot(any(), any(), anyInt(), anyBoolean(), anyBoolean()))
                .thenReturn(Response.ok().build());

        tools.setupBot("Test Bot", "You are helpful", null, null,
                "sk-test", null, null, null, null, null, null, true, null);

        // Output store should NOT be called
        verify(outputStore, never()).createOutputSet(any());
        // Only 4 descriptors patched (no output)
        verify(descriptorStore, times(4)).patchDescriptor(any(), anyInt(), any());
    }

    @Test
    void setupBot_withoutDeploy_skipsDeploy() throws Exception {
        when(behaviorStore.createBehaviorRuleSet(any()))
                .thenReturn(Response.created(URI.create("/behaviorstore/behaviorsets/beh-1?version=1")).build());
        when(langchainStore.createLangChain(any()))
                .thenReturn(Response.created(URI.create("/langchainstore/langchains/lc-1?version=1")).build());
        when(packageStore.createPackage(any()))
                .thenReturn(Response.created(URI.create("/packagestore/packages/pkg-1?version=1")).build());
        when(botStore.createBot(any()))
                .thenReturn(Response.created(URI.create("/botstore/bots/bot-1?version=1")).build());

        tools.setupBot("Test Bot", "You are helpful", null, null,
                "sk-test", null, null, null, null, null, null, false, null);

        // Deploy should NOT be called
        verify(botAdmin, never()).deployBot(any(), any(), anyInt(), anyBoolean(), anyBoolean());
    }

    @Test
    void setupBot_deployFails_returnsSuccessWithWarning() throws Exception {
        when(behaviorStore.createBehaviorRuleSet(any()))
                .thenReturn(Response.created(URI.create("/behaviorstore/behaviorsets/beh-1?version=1")).build());
        when(langchainStore.createLangChain(any()))
                .thenReturn(Response.created(URI.create("/langchainstore/langchains/lc-1?version=1")).build());
        when(packageStore.createPackage(any()))
                .thenReturn(Response.created(URI.create("/packagestore/packages/pkg-1?version=1")).build());
        when(botStore.createBot(any()))
                .thenReturn(Response.created(URI.create("/botstore/bots/bot-1?version=1")).build());
        when(botAdmin.deployBot(any(), any(), anyInt(), anyBoolean(), anyBoolean()))
                .thenThrow(new RuntimeException("Deploy failed"));

        String result = tools.setupBot("Test Bot", "You are helpful", null, null,
                "sk-test", null, null, null, null, null, null, true, null);

        // Should still return a result (bot was created), not an error
        assertNotNull(result);
        assertFalse(result.contains("\"error\""));
    }

    @Test
    void setupBot_missingName_returnsError() {
        String result = tools.setupBot(null, "prompt", null, null, "key", null, null, null, null, null, null, null, null);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("name is required"));
    }

    @Test
    void setupBot_missingPrompt_returnsError() {
        String result = tools.setupBot("Bot", null, null, null, "key", null, null, null, null, null, null, null, null);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("System prompt is required"));
    }

    @Test
    void setupBot_missingApiKey_returnsError() {
        String result = tools.setupBot("Bot", "prompt", null, null, null, null, null, null, null, null, null, null, null);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("API key is required"));
    }

    @Test
    void setupBot_ollamaNoApiKey_succeeds() throws Exception {
        when(behaviorStore.createBehaviorRuleSet(any()))
                .thenReturn(Response.created(URI.create("/behaviorstore/behaviorsets/beh-1?version=1")).build());
        when(langchainStore.createLangChain(any()))
                .thenReturn(Response.created(URI.create("/langchainstore/langchains/lc-1?version=1")).build());
        when(packageStore.createPackage(any()))
                .thenReturn(Response.created(URI.create("/packagestore/packages/pkg-1?version=1")).build());
        when(botStore.createBot(any()))
                .thenReturn(Response.created(URI.create("/botstore/bots/bot-1?version=1")).build());

        // Ollama should NOT require an apiKey
        String result = tools.setupBot("Ollama Bot", "You are helpful", "ollama", "llama3.2:1b",
                null, null, null, null, null, null, null, false, null);

        assertNotNull(result);
        assertFalse(result.contains("\"error\""), "Ollama setup should succeed without API key");
        verify(langchainStore).createLangChain(any());
    }

    @Test
    void setupBot_jlamaNoApiKey_succeeds() throws Exception {
        when(behaviorStore.createBehaviorRuleSet(any()))
                .thenReturn(Response.created(URI.create("/behaviorstore/behaviorsets/beh-1?version=1")).build());
        when(langchainStore.createLangChain(any()))
                .thenReturn(Response.created(URI.create("/langchainstore/langchains/lc-1?version=1")).build());
        when(packageStore.createPackage(any()))
                .thenReturn(Response.created(URI.create("/packagestore/packages/pkg-1?version=1")).build());
        when(botStore.createBot(any()))
                .thenReturn(Response.created(URI.create("/botstore/bots/bot-1?version=1")).build());

        String result = tools.setupBot("Jlama Bot", "You are helpful", "jlama", "tinyllama",
                null, null, null, null, null, null, null, false, null);

        assertNotNull(result);
        assertFalse(result.contains("\"error\""), "Jlama setup should succeed without API key");
    }

    @Test
    void setupBot_capturesLangchainConfig() throws Exception {
        when(behaviorStore.createBehaviorRuleSet(any()))
                .thenReturn(Response.created(URI.create("/behaviorstore/behaviorsets/beh-1?version=1")).build());
        when(langchainStore.createLangChain(any()))
                .thenReturn(Response.created(URI.create("/langchainstore/langchains/lc-1?version=1")).build());
        when(packageStore.createPackage(any()))
                .thenReturn(Response.created(URI.create("/packagestore/packages/pkg-1?version=1")).build());
        when(botStore.createBot(any()))
                .thenReturn(Response.created(URI.create("/botstore/bots/bot-1?version=1")).build());

        tools.setupBot("My Bot", "You are a pirate", "anthropic", "claude-3-5-sonnet",
                "sk-ant-key", null, null, null, null, null, null, false, null);

        // Capture the langchain config
        var langchainCaptor = ArgumentCaptor.forClass(LangChainConfiguration.class);
        verify(langchainStore).createLangChain(langchainCaptor.capture());

        var config = langchainCaptor.getValue();
        assertEquals(1, config.tasks().size());
        var task = config.tasks().get(0);
        assertEquals("anthropic", task.getType());
        assertEquals("You are a pirate", task.getParameters().get("systemMessage"));
        assertEquals("claude-3-5-sonnet", task.getParameters().get("modelName"));
        assertEquals("sk-ant-key", task.getParameters().get("apiKey"));
    }

    @Test
    void setupBot_capturesPackageConfig() throws Exception {
        when(behaviorStore.createBehaviorRuleSet(any()))
                .thenReturn(Response.created(URI.create("/behaviorstore/behaviorsets/beh-1?version=1")).build());
        when(langchainStore.createLangChain(any()))
                .thenReturn(Response.created(URI.create("/langchainstore/langchains/lc-1?version=1")).build());
        when(outputStore.createOutputSet(any()))
                .thenReturn(Response.created(URI.create("/outputstore/outputsets/out-1?version=1")).build());
        when(packageStore.createPackage(any()))
                .thenReturn(Response.created(URI.create("/packagestore/packages/pkg-1?version=1")).build());
        when(botStore.createBot(any()))
                .thenReturn(Response.created(URI.create("/botstore/bots/bot-1?version=1")).build());

        tools.setupBot("Bot", "prompt", null, null, "key", null, "Hello!",
                null, null, null, null, false, null);

        // Capture package config
        var packageCaptor = ArgumentCaptor.forClass(PackageConfiguration.class);
        verify(packageStore).createPackage(packageCaptor.capture());

        var pkgConfig = packageCaptor.getValue();
        // Should have 3 extensions: behavior, langchain, output (no parser for LLM-powered bots)
        assertEquals(3, pkgConfig.getPackageExtensions().size());
        assertEquals(URI.create("eddi://ai.labs.behavior"), pkgConfig.getPackageExtensions().get(0).getType());
        assertEquals(URI.create("eddi://ai.labs.langchain"), pkgConfig.getPackageExtensions().get(1).getType());
        assertEquals(URI.create("eddi://ai.labs.output"), pkgConfig.getPackageExtensions().get(2).getType());
    }

    @Test
    void setupBot_packageWithoutOutput_has3Extensions() throws Exception {
        when(behaviorStore.createBehaviorRuleSet(any()))
                .thenReturn(Response.created(URI.create("/behaviorstore/behaviorsets/beh-1?version=1")).build());
        when(langchainStore.createLangChain(any()))
                .thenReturn(Response.created(URI.create("/langchainstore/langchains/lc-1?version=1")).build());
        when(packageStore.createPackage(any()))
                .thenReturn(Response.created(URI.create("/packagestore/packages/pkg-1?version=1")).build());
        when(botStore.createBot(any()))
                .thenReturn(Response.created(URI.create("/botstore/bots/bot-1?version=1")).build());

        tools.setupBot("Bot", "prompt", null, null, "key", null, null,
                null, null, null, null, false, null);

        var packageCaptor = ArgumentCaptor.forClass(PackageConfiguration.class);
        verify(packageStore).createPackage(packageCaptor.capture());

        // Without intro message, should have 2 extensions (no output, no parser)
        assertEquals(2, packageCaptor.getValue().getPackageExtensions().size());
    }

    @Test
    void createBehaviorConfig_producesCorrectStructure() {
        var config = tools.createBehaviorConfig();
        assertTrue(config.getExpressionsAsActions());
        assertEquals(1, config.getBehaviorGroups().size());

        var group = config.getBehaviorGroups().get(0);
        assertEquals(1, group.getBehaviorRules().size());

        var rule = group.getBehaviorRules().get(0);
        assertEquals("Send Message to LLM", rule.getName());
        assertEquals(List.of("send_message"), rule.getActions());
        assertEquals("inputmatcher", rule.getConditions().get(0).getType());
        assertEquals("*", rule.getConditions().get(0).getConfigs().get("expressions"));
    }

    @Test
    void createLangchainConfig_withTooling_setsToolFields() {
        var config = tools.createLangchainConfig(
                "anthropic", "claude-sonnet-4-6", "key", "You are helpful",
                true, "calculator,websearch", null, null);

        var task = config.tasks().get(0);
        assertTrue(task.getEnableBuiltInTools());
        assertEquals(java.util.List.of("calculator", "websearch"), task.getBuiltInToolsWhitelist());
    }

    @Test
    void createLangchainConfig_ollama_usesModelParam() {
        var config = tools.createLangchainConfig(
                "ollama", "llama3.2:1b", null, "prompt",
                false, null, "http://host.docker.internal:11434", null);

        var task = config.tasks().get(0);
        var params = task.getParameters();
        assertEquals("llama3.2:1b", params.get("model"));
        assertEquals("http://host.docker.internal:11434", params.get("baseUrl"));
        assertNull(params.get("modelName"), "Ollama should NOT use 'modelName'");
        assertNull(params.get("apiKey"), "Ollama should NOT set 'apiKey'");
    }

    @Test
    void createLangchainConfig_jlama_usesAuthToken() {
        var config = tools.createLangchainConfig(
                "jlama", "tinyllama", "my-token", "prompt",
                false, null, null, null);

        var task = config.tasks().get(0);
        var params = task.getParameters();
        assertEquals("tinyllama", params.get("modelName"));
        assertEquals("my-token", params.get("authToken"));
        assertNull(params.get("apiKey"), "Jlama should use 'authToken' not 'apiKey'");
    }

    // --- Quick Replies & Sentiment Analysis tests ---

    @Test
    void setupBot_withQuickReplies_appendsJsonFormat() throws Exception {
        when(behaviorStore.createBehaviorRuleSet(any()))
                .thenReturn(Response.created(URI.create("/behaviorstore/behaviorsets/beh-1?version=1")).build());
        when(langchainStore.createLangChain(any()))
                .thenReturn(Response.created(URI.create("/langchainstore/langchains/lc-1?version=1")).build());
        when(packageStore.createPackage(any()))
                .thenReturn(Response.created(URI.create("/packagestore/packages/pkg-1?version=1")).build());
        when(botStore.createBot(any()))
                .thenReturn(Response.created(URI.create("/botstore/bots/bot-1?version=1")).build());

        tools.setupBot("QR Bot", "You are helpful", "openai", "gpt-4o",
                "sk-test", null, null, null, null, true, null, false, null);

        var lcCaptor = ArgumentCaptor.forClass(LangChainConfiguration.class);
        verify(langchainStore).createLangChain(lcCaptor.capture());
        var params = lcCaptor.getValue().tasks().get(0).getParameters();

        assertTrue(params.get("systemMessage").contains("quickReplies"),
                "System message should contain quickReplies format");
        assertTrue(params.get("systemMessage").contains("htmlResponseText"),
                "System message should contain htmlResponseText");
        assertFalse(params.get("systemMessage").contains("sentimentScore"),
                "System message should NOT contain sentiment fields");
        assertEquals("true", params.get("convertToObject"),
                "convertToObject should be true for JSON format");
        assertEquals("json", params.get("responseFormat"),
                "OpenAI should have responseFormat=json");
    }

    @Test
    void setupBot_withSentiment_appendsJsonFormat() throws Exception {
        when(behaviorStore.createBehaviorRuleSet(any()))
                .thenReturn(Response.created(URI.create("/behaviorstore/behaviorsets/beh-1?version=1")).build());
        when(langchainStore.createLangChain(any()))
                .thenReturn(Response.created(URI.create("/langchainstore/langchains/lc-1?version=1")).build());
        when(packageStore.createPackage(any()))
                .thenReturn(Response.created(URI.create("/packagestore/packages/pkg-1?version=1")).build());
        when(botStore.createBot(any()))
                .thenReturn(Response.created(URI.create("/botstore/bots/bot-1?version=1")).build());

        tools.setupBot("Sentiment Bot", "You are helpful", "gemini", "gemini-2.0-flash",
                "key", null, null, null, null, null, true, false, null);

        var lcCaptor = ArgumentCaptor.forClass(LangChainConfiguration.class);
        verify(langchainStore).createLangChain(lcCaptor.capture());
        var params = lcCaptor.getValue().tasks().get(0).getParameters();

        assertTrue(params.get("systemMessage").contains("\"sentiment\":"),
                "System message should contain nested sentiment object");
        assertTrue(params.get("systemMessage").contains("\"score\":"),
                "System message should contain score field");
        assertTrue(params.get("systemMessage").contains("\"emotions\":"),
                "System message should contain emotions field");
        assertTrue(params.get("systemMessage").contains("\"confidence\":"),
                "System message should contain confidence field");
        assertTrue(params.get("systemMessage").contains("\"topicTags\":"),
                "System message should contain topicTags field");
        assertTrue(params.get("systemMessage").contains("htmlResponseText"),
                "System message should contain htmlResponseText");
        assertFalse(params.get("systemMessage").contains("quickReplies"),
                "System message should NOT contain quickReplies");
        assertEquals("json", params.get("responseFormat"),
                "Gemini should have responseFormat=json");
    }

    @Test
    void setupBot_withBothFeatures_appendsFullJsonFormat() throws Exception {
        when(behaviorStore.createBehaviorRuleSet(any()))
                .thenReturn(Response.created(URI.create("/behaviorstore/behaviorsets/beh-1?version=1")).build());
        when(langchainStore.createLangChain(any()))
                .thenReturn(Response.created(URI.create("/langchainstore/langchains/lc-1?version=1")).build());
        when(packageStore.createPackage(any()))
                .thenReturn(Response.created(URI.create("/packagestore/packages/pkg-1?version=1")).build());
        when(botStore.createBot(any()))
                .thenReturn(Response.created(URI.create("/botstore/bots/bot-1?version=1")).build());

        tools.setupBot("Full Bot", "You are helpful", "openai", "gpt-4o",
                "sk-test", null, null, null, null, true, true, false, null);

        var lcCaptor = ArgumentCaptor.forClass(LangChainConfiguration.class);
        verify(langchainStore).createLangChain(lcCaptor.capture());
        var params = lcCaptor.getValue().tasks().get(0).getParameters();

        assertTrue(params.get("systemMessage").contains("quickReplies"));
        assertTrue(params.get("systemMessage").contains("\"sentiment\":"));
        assertTrue(params.get("systemMessage").contains("\"score\":"));
        assertTrue(params.get("systemMessage").contains("\"confidence\":"));
        assertTrue(params.get("systemMessage").contains("\"topicTags\":"));
        assertTrue(params.get("systemMessage").contains("htmlResponseText"));
        assertEquals("true", params.get("convertToObject"));
        assertEquals("json", params.get("responseFormat"));
    }

    @Test
    void setupBot_anthropic_noResponseFormat() throws Exception {
        when(behaviorStore.createBehaviorRuleSet(any()))
                .thenReturn(Response.created(URI.create("/behaviorstore/behaviorsets/beh-1?version=1")).build());
        when(langchainStore.createLangChain(any()))
                .thenReturn(Response.created(URI.create("/langchainstore/langchains/lc-1?version=1")).build());
        when(packageStore.createPackage(any()))
                .thenReturn(Response.created(URI.create("/packagestore/packages/pkg-1?version=1")).build());
        when(botStore.createBot(any()))
                .thenReturn(Response.created(URI.create("/botstore/bots/bot-1?version=1")).build());

        tools.setupBot("Anthropic Bot", "You are helpful", "anthropic", "claude-sonnet-4-6",
                "sk-test", null, null, null, null, true, null, false, null);

        var lcCaptor = ArgumentCaptor.forClass(LangChainConfiguration.class);
        verify(langchainStore).createLangChain(lcCaptor.capture());
        var params = lcCaptor.getValue().tasks().get(0).getParameters();

        // Anthropic doesn't support responseFormat but should still have the prompt instruction
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
    void buildPromptResponseJson_both() {
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

    // --- create_api_bot tests ---

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
    void createApiBot_fullPipeline_createsAllResources() throws Exception {
        // Mock all store responses
        when(httpCallsStore.createHttpCalls(any()))
                .thenReturn(Response.created(URI.create("/httpcallsstore/httpcalls/hc-1?version=1")).build())
                .thenReturn(Response.created(URI.create("/httpcallsstore/httpcalls/hc-2?version=1")).build());
        when(behaviorStore.createBehaviorRuleSet(any()))
                .thenReturn(Response.created(URI.create("/behaviorstore/behaviorsets/beh-1?version=1")).build());
        when(langchainStore.createLangChain(any()))
                .thenReturn(Response.created(URI.create("/langchainstore/langchains/lc-1?version=1")).build());
        when(packageStore.createPackage(any()))
                .thenReturn(Response.created(URI.create("/packagestore/packages/pkg-1?version=1")).build());
        when(botStore.createBot(any()))
                .thenReturn(Response.created(URI.create("/botstore/bots/bot-1?version=1")).build());
        when(botAdmin.deployBot(any(), any(), anyInt(), anyBoolean(), anyBoolean()))
                .thenReturn(Response.ok().build());

        String result = tools.createApiBot(
                "API Bot", "You are an API assistant", SIMPLE_SPEC,
                "anthropic", "claude-sonnet-4-6", "sk-test",
                null, "Bearer api-key", null, true, null);

        assertNotNull(result);

        // Verify httpcalls created (2 groups: users, orders)
        verify(httpCallsStore, times(2)).createHttpCalls(any());
        verify(behaviorStore).createBehaviorRuleSet(any());
        verify(langchainStore).createLangChain(any());
        verify(packageStore).createPackage(any());
        verify(botStore).createBot(any());
        verify(botAdmin).deployBot(Environment.unrestricted, "bot-1", 1, true, true);

        // Verify the system prompt was enriched with API summary
        var lcCaptor = ArgumentCaptor.forClass(LangChainConfiguration.class);
        verify(langchainStore).createLangChain(lcCaptor.capture());
        String systemMessage = lcCaptor.getValue().tasks().get(0).getParameters().get("systemMessage");
        assertTrue(systemMessage.startsWith("You are an API assistant"),
                "System prompt should keep the original text");
        assertTrue(systemMessage.contains("Available API endpoints"),
                "System prompt should include the API summary");
    }

    @Test
    void createApiBot_missingSpec_returnsError() {
        String result = tools.createApiBot("Bot", "prompt", null,
                null, null, "key", null, null, null, null, null);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("OpenAPI spec is required"));
    }

    @Test
    void createApiBot_missingApiKey_returnsError() {
        String result = tools.createApiBot("Bot", "prompt", SIMPLE_SPEC,
                null, null, null, null, null, null, null, null);
        assertTrue(result.contains("error"));
        assertTrue(result.contains("API key is required"));
    }

    @Test
    void createApiBot_packageContainsHttpCallsExtensions() throws Exception {
        when(httpCallsStore.createHttpCalls(any()))
                .thenReturn(Response.created(URI.create("/httpcallsstore/httpcalls/hc-1?version=1")).build())
                .thenReturn(Response.created(URI.create("/httpcallsstore/httpcalls/hc-2?version=1")).build());
        when(behaviorStore.createBehaviorRuleSet(any()))
                .thenReturn(Response.created(URI.create("/behaviorstore/behaviorsets/beh-1?version=1")).build());
        when(langchainStore.createLangChain(any()))
                .thenReturn(Response.created(URI.create("/langchainstore/langchains/lc-1?version=1")).build());
        when(packageStore.createPackage(any()))
                .thenReturn(Response.created(URI.create("/packagestore/packages/pkg-1?version=1")).build());
        when(botStore.createBot(any()))
                .thenReturn(Response.created(URI.create("/botstore/bots/bot-1?version=1")).build());

        tools.createApiBot("Bot", "prompt", SIMPLE_SPEC,
                null, null, "key", null, null, null, false, null);

        var packageCaptor = ArgumentCaptor.forClass(PackageConfiguration.class);
        verify(packageStore).createPackage(packageCaptor.capture());

        var pkgConfig = packageCaptor.getValue();
        // Should have 4 extensions: behavior + 2 httpcalls groups + langchain (no parser for LLM-powered bots)
        assertEquals(4, pkgConfig.getPackageExtensions().size());
        assertEquals(URI.create("eddi://ai.labs.behavior"), pkgConfig.getPackageExtensions().get(0).getType());
        assertEquals(URI.create("eddi://ai.labs.httpcalls"), pkgConfig.getPackageExtensions().get(1).getType());
        assertEquals(URI.create("eddi://ai.labs.httpcalls"), pkgConfig.getPackageExtensions().get(2).getType());
        assertEquals(URI.create("eddi://ai.labs.langchain"), pkgConfig.getPackageExtensions().get(3).getType());
    }
}
