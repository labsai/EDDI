/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IDataFactory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.modules.apicalls.impl.IApiCallExecutor;
import ai.labs.eddi.modules.apicalls.impl.PrePostUtils;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.ConversationSummaryConfig;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import dev.langchain4j.model.chat.ChatModel;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.engine.memory.MemoryKeys.ACTIONS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * The parent LLM task's resolved parameters are inherited by the rolling
 * summarizer (finding F13) — but they belong to the parent's PROVIDER.
 * <p>
 * {@code resolveEffectiveSummaryConfig} deliberately honours a
 * {@code conversationSummary.llmProvider} naming a different vendor, so
 * inheriting the map wholesale sent the parent's plaintext {@code apiKey} (and
 * its {@code baseUrl}, together with the condensed transcript) to that other
 * vendor's endpoint on every summarization turn.
 */
@SuppressWarnings({"rawtypes", "unchecked"})
@DisplayName("LlmTask — summary credentials never cross a provider boundary")
class LlmTaskSummaryCredentialIsolationTest {

    @Mock
    private IResourceClientLibrary resourceClientLibrary;
    @Mock
    private IDataFactory dataFactory;
    @Mock
    private IMemoryItemConverter memoryItemConverter;
    @Mock
    private ITemplatingEngine templatingEngine;
    @Mock
    private IJsonSerialization jsonSerialization;
    @Mock
    private PrePostUtils prePostUtils;
    @Mock
    private ChatModelRegistry chatModelRegistry;
    @Mock
    private RagContextProvider ragContextProvider;
    @Mock
    private PromptSnippetService promptSnippetService;
    @Mock
    private GlobalVariableResolver globalVariableResolver;
    @Mock
    private CounterweightService counterweightService;
    @Mock
    private IdentityMaskingService identityMaskingService;
    @Mock
    private ConversationSummarizer conversationSummarizer;
    @Mock
    private AgentOrchestrator agentOrchestrator;
    @Mock
    private IConversationMemory memory;
    @Mock
    private IWritableConversationStep currentStep;

    private LlmTask llmTask;

    @BeforeEach
    void setUp() throws Exception {
        openMocks(this);

        lenient().when(promptSnippetService.getAll()).thenReturn(Map.of());
        lenient().when(globalVariableResolver.getTemplateData()).thenReturn(Map.of());
        lenient().when(globalVariableResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(counterweightService.apply(anyString(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(identityMaskingService.apply(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));

        llmTask = new LlmTask(resourceClientLibrary, dataFactory, memoryItemConverter,
                templatingEngine, jsonSerialization, prePostUtils, chatModelRegistry,
                mock(IApiCallExecutor.class), mock(IRestAgentStore.class), mock(IRestWorkflowStore.class),
                ragContextProvider, new TokenCounterFactory(), conversationSummarizer,
                promptSnippetService, globalVariableResolver, counterweightService,
                identityMaskingService, agentOrchestrator, new ConversationHistoryBuilder(),
                new SimpleMeterRegistry(), new CallerIdentityContext(null, null));

        lenient().when(dataFactory.createData(anyString(), any())).thenAnswer(inv -> {
            IData d = mock(IData.class);
            when(d.getResult()).thenReturn(inv.getArgument(1));
            return d;
        });

        lenient().when(memory.getCurrentStep()).thenReturn(currentStep);
        IData actionData = mock(IData.class);
        lenient().when(currentStep.getLatestData(ACTIONS)).thenReturn(actionData);
        lenient().when(actionData.getResult()).thenReturn(List.of("action1"));
        lenient().when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());
        var conversationOutput = new ConversationOutput();
        conversationOutput.put("input", "user input");
        lenient().when(memory.getConversationOutputs()).thenReturn(List.of(conversationOutput));
        lenient().when(chatModelRegistry.getOrCreate(anyString(), any())).thenReturn(mock(ChatModel.class));
        lenient().when(templatingEngine.processTemplate(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));

        var props = mock(IConversationMemory.IConversationProperties.class);
        lenient().when(props.get(anyString())).thenReturn(null);
        lenient().when(memory.getConversationProperties()).thenReturn(props);

        lenient().when(agentOrchestrator.executeIfToolsEnabled(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(new AgentOrchestrator.ExecutionResult("done", new ArrayList<>()));
    }

    private LlmConfiguration.Task openAiTaskWithSummaryProvider(String summaryProvider) {
        var t = new LlmConfiguration.Task();
        t.setId("taskA");
        t.setType("openai");
        t.setActions(List.of("action1"));
        var params = new HashMap<String, String>();
        params.put("apiKey", "sk-openai-parent-key");
        params.put("baseUrl", "https://api.openai.com/v1");
        params.put("temperature", "0.3");
        params.put("modelName", "gpt-4o");
        t.setParameters(params);

        var summaryConfig = new ConversationSummaryConfig();
        summaryConfig.setEnabled(true);
        if (summaryProvider != null) {
            summaryConfig.setLlmProvider(summaryProvider);
            summaryConfig.setLlmModel("some-summary-model");
        }
        t.setConversationSummary(summaryConfig);
        return t;
    }

    private Map<String, String> capturedSummaryParameters() {
        ArgumentCaptor<Map> captor = ArgumentCaptor.forClass(Map.class);
        verify(conversationSummarizer).updateIfNeeded(eq(memory), any(), any(), captor.capture());
        return captor.getValue();
    }

    // ============================================================
    // End-to-end through LlmTask.execute
    // ============================================================

    @Test
    @DisplayName("a summary provider different from the task's receives NO inherited apiKey or baseUrl")
    void differentSummaryProvider_getsNoCredentials() throws Exception {
        llmTask.execute(memory, new LlmConfiguration(List.of(openAiTaskWithSummaryProvider("anthropic"))));

        var params = capturedSummaryParameters();
        assertNull(params.get("apiKey"),
                "the OpenAI key must never be handed to a summarizer running on Anthropic");
        assertNull(params.get("baseUrl"),
                "the OpenAI endpoint must not redirect the Anthropic summarization request");
        assertEquals("0.3", params.get("temperature"),
                "vendor-neutral tuning parameters still carry over");
    }

    @Test
    @DisplayName("the summary provider inherited from the task still receives the task's credentials (F13 intact)")
    void sameSummaryProvider_stillInheritsCredentials() throws Exception {
        llmTask.execute(memory, new LlmConfiguration(List.of(openAiTaskWithSummaryProvider(null))));

        var params = capturedSummaryParameters();
        assertEquals("sk-openai-parent-key", params.get("apiKey"),
                "without the key an inherited-provider summarizer can never authenticate");
        assertEquals("https://api.openai.com/v1", params.get("baseUrl"));
    }

    @Test
    @DisplayName("an explicitly configured summary provider equal to the task's still inherits credentials")
    void explicitButIdenticalSummaryProvider_stillInheritsCredentials() throws Exception {
        llmTask.execute(memory, new LlmConfiguration(List.of(openAiTaskWithSummaryProvider("OpenAI"))));

        var params = capturedSummaryParameters();
        assertEquals("sk-openai-parent-key", params.get("apiKey"),
                "same provider spelled differently is still the same provider");
    }

    // ============================================================
    // The resolution helper in isolation
    // ============================================================

    @Test
    @DisplayName("matching providers → the very same map instance is passed through untouched")
    void matchingProviders_passThrough() {
        Map<String, String> parentParams = new HashMap<>(Map.of("apiKey", "k", "baseUrl", "https://p/v1"));

        var resolved = LlmTask.resolveInheritedSummaryParameters(parentParams, "openai", "openai");

        assertSame(parentParams, resolved);
    }

    @Test
    @DisplayName("mismatched providers → every credential and endpoint key is dropped, neutral keys survive")
    void mismatchedProviders_stripCredentialKeys() {
        Map<String, String> parentParams = new HashMap<>();
        parentParams.put("apiKey", "sk-parent");
        parentParams.put("accessToken", "token");
        parentParams.put("authToken", "token");
        parentParams.put("nonAzureApiKey", "token");
        parentParams.put("baseUrl", "https://parent/v1");
        parentParams.put("endpoint", "https://parent.azure");
        parentParams.put("deploymentName", "parent-deployment");
        parentParams.put("compartmentId", "ocid1");
        parentParams.put("configProfile", "DEFAULT");
        parentParams.put("projectId", "gcp-project");
        parentParams.put("region", "us-east-1");
        parentParams.put("location", "us-central1");
        parentParams.put("temperature", "0.7");
        parentParams.put("maxTokens", "512");

        var resolved = LlmTask.resolveInheritedSummaryParameters(parentParams, "anthropic", "openai");

        for (String credentialKey : List.of("apiKey", "accessToken", "authToken", "nonAzureApiKey", "baseUrl", "endpoint",
                "deploymentName", "compartmentId", "configProfile", "projectId", "region", "location")) {
            assertFalse(resolved.containsKey(credentialKey), credentialKey + " must not cross a provider boundary");
        }
        assertEquals("0.7", resolved.get("temperature"));
        assertEquals("512", resolved.get("maxTokens"));
        assertEquals(2, resolved.size());
        assertTrue(parentParams.containsKey("apiKey"), "the parent task's own parameter map must not be mutated");
    }

    @Test
    @DisplayName("an unknown provider on either side is treated as a mismatch")
    void unknownProvider_treatedAsMismatch() {
        Map<String, String> parentParams = new HashMap<>(Map.of("apiKey", "k", "temperature", "0.1"));

        assertNull(LlmTask.resolveInheritedSummaryParameters(parentParams, null, "openai").get("apiKey"));
        assertNull(LlmTask.resolveInheritedSummaryParameters(parentParams, "openai", "").get("apiKey"));
    }

    @Test
    @DisplayName("null / empty parameters are returned unchanged")
    void noParameters_returnedUnchanged() {
        assertNull(LlmTask.resolveInheritedSummaryParameters(null, "openai", "anthropic"));
        Map<String, String> empty = new HashMap<>();
        assertSame(empty, LlmTask.resolveInheritedSummaryParameters(empty, "openai", "anthropic"));
    }
}
