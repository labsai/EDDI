/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.IRestAgentStore;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.configs.workflows.IRestWorkflowStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.lifecycle.ConversationEventSink;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IDataFactory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.modules.apicalls.impl.IApiCallExecutor;
import ai.labs.eddi.modules.apicalls.impl.PrePostUtils;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.CascadeStep;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.ModelCascadeConfig;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.TokenUsage;
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
import static dev.langchain4j.data.message.AiMessage.aiMessage;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * Finding F10 — a tool-enabled task cannot stream token by token: the agent
 * loop is synchronous and the finished answer is pushed through a single
 * {@code onToken}. To the SSE client that is a long silence followed by one
 * enormous token, indistinguishable from a slow stream.
 * <p>
 * {@code recordStreamingDowngrade} makes it observable — a
 * {@code streamingDowngraded} flag in {@code responseMetadata} (so an agent
 * designer can branch on it via {@code responseMetadataObjectName}) plus an
 * {@code eddi.llm.streaming.downgraded} counter for operators. The first group
 * of tests below pins both on the two non-cascade agent call sites; deleting
 * either call site or the method itself turns them red.
 * <p>
 * The second group pins the THIRD call site: the multi-model-cascade agent
 * path, which emits the whole agent answer as one chunk in exactly the same way
 * and is the DEFAULT for a cascading agent ({@code enableInAgentMode} defaults
 * to true). Those tests drive the real {@code CascadingModelExecutor} so the
 * cascade call site is pinned independently of the other two — and they assert
 * the negative case as well, since a cascade LEGACY step also emits a single
 * chunk but is not a {@code tools_enabled} downgrade.
 */
@DisplayName("LlmTask — streaming downgrade is observable (F10)")
class LlmTaskStreamingDowngradeTest {

    private static final String METADATA_KEY = "llmMeta";
    private static final String COUNTER = "eddi.llm.streaming.downgraded";

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
    private ConversationSummarizer conversationSummarizer;
    @Mock
    private CounterweightService counterweightService;
    @Mock
    private IdentityMaskingService identityMaskingService;
    @Mock
    private AgentOrchestrator agentOrchestrator;
    @Mock
    private ChatModel chatModel;
    @Mock
    private IConversationMemory memory;
    @Mock
    private IWritableConversationStep currentStep;
    @Mock
    private ConversationEventSink eventSink;

    private SimpleMeterRegistry meterRegistry;
    private LlmTask llmTask;
    private Map<String, Object> templateData;

    @BeforeEach
    void setUp() throws Exception {
        openMocks(this);

        meterRegistry = new SimpleMeterRegistry();
        templateData = new HashMap<>();

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
                meterRegistry, new CallerIdentityContext(null, null));

        lenient().when(dataFactory.createData(anyString(), any())).thenAnswer(inv -> {
            IData<?> data = mock(IData.class);
            lenient().when(data.getResult()).thenAnswer(x -> inv.getArgument(1));
            return data;
        });

        when(memory.getCurrentStep()).thenReturn(currentStep);
        @SuppressWarnings("unchecked")
        IData<List<String>> actionData = mock(IData.class);
        lenient().when(currentStep.<List<String>>getLatestData(ACTIONS)).thenReturn(actionData);
        lenient().when(actionData.getResult()).thenReturn(List.of("action1"));
        lenient().when(memoryItemConverter.convert(memory)).thenReturn(templateData);
        lenient().when(memory.getHitlPendingToolCalls()).thenReturn(null);
        lenient().when(memory.getHitlResumeDecision()).thenReturn(null);
        var conversationOutput = new ConversationOutput();
        conversationOutput.put("input", "user input");
        lenient().when(memory.getConversationOutputs()).thenReturn(List.of(conversationOutput));
        lenient().when(chatModelRegistry.getOrCreate(anyString(), any())).thenReturn(chatModel);
        lenient().when(templatingEngine.processTemplate(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
        @SuppressWarnings("unchecked")
        IData<String> inputData = mock(IData.class);
        lenient().when(currentStep.<String>getLatestData("input")).thenReturn(inputData);
        lenient().when(inputData.getResult()).thenReturn("user input");

        // SSE is active — this is the whole point of the downgrade signal. Individual
        // tests re-stub this (notably the no-sink case).
        lenient().when(memory.getEventSink()).thenReturn(eventSink);

        lenient().when(agentOrchestrator.executeIfToolsEnabled(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(new AgentOrchestrator.ExecutionResult("the whole agent answer", new ArrayList<>()));
    }

    private LlmConfiguration.Task toolTask() {
        var task = new LlmConfiguration.Task();
        task.setId("taskA");
        task.setType("openai");
        task.setActions(List.of("action1"));
        var params = new HashMap<String, String>();
        params.put("apiKey", "key");
        task.setParameters(params);
        task.setResponseMetadataObjectName(METADATA_KEY);
        task.setEnableBuiltInTools(true); // → isAgentMode()
        return task;
    }

    /** A cascade that is enabled AND active in agent mode (the shipped default). */
    private static ModelCascadeConfig agentModeCascade() {
        var cascade = new ModelCascadeConfig();
        cascade.setEnabled(true);
        cascade.setEnableInAgentMode(true);
        cascade.setEvaluationStrategy("none");
        var step = new CascadeStep();
        step.setType("openai");
        step.setTimeoutMs(5000L);
        cascade.setSteps(List.of(step));
        return cascade;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> capturedMetadata() {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(prePostUtils).createMemoryEntry(eq(currentStep), captor.capture(), eq(METADATA_KEY), eq("langchain"));
        return captor.getValue();
    }

    private double counterCount() {
        var counter = meterRegistry.find(COUNTER).tag("reason", "tools_enabled").counter();
        return counter != null ? counter.count() : 0.0;
    }

    // ---------------------------------------------------------------------
    // The two non-cascade agent call sites.
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("tool mode + event sink → responseMetadata carries the downgrade flag and reason")
    void toolModeWithSink_flagsDowngradeInMetadata() throws Exception {
        when(memory.getEventSink()).thenReturn(eventSink);

        llmTask.execute(memory, new LlmConfiguration(List.of(toolTask())));

        var metadata = capturedMetadata();
        assertEquals(Boolean.TRUE, metadata.get("streamingDowngraded"),
                "an agent designer must be able to branch on the downgrade via responseMetadata");
        assertEquals("tools_enabled", metadata.get("streamingDowngradeReason"));
        // …and the whole answer really did arrive as ONE token event.
        verify(eventSink).onToken("the whole agent answer");
    }

    @Test
    @DisplayName("tool mode + event sink → the eddi.llm.streaming.downgraded counter is incremented")
    void toolModeWithSink_incrementsCounter() throws Exception {
        when(memory.getEventSink()).thenReturn(eventSink);

        llmTask.execute(memory, new LlmConfiguration(List.of(toolTask())));

        assertEquals(1.0, counterCount(), 0.0001,
                "operators alert on this counter — one downgraded turn must count exactly once");
    }

    @Test
    @DisplayName("skipCascade tool branch downgrades too (a second, separately-revertable call site)")
    void skipCascadeToolMode_flagsDowngrade() throws Exception {
        when(memory.getEventSink()).thenReturn(eventSink);

        var cascade = new ModelCascadeConfig();
        cascade.setEnabled(true);
        cascade.setEnableInAgentMode(false);
        cascade.setEvaluationStrategy("none");
        var step = new CascadeStep();
        step.setType("openai");
        step.setTimeoutMs(5000L);
        cascade.setSteps(List.of(step));

        var task = toolTask();
        task.setModelCascade(cascade);

        llmTask.execute(memory, new LlmConfiguration(List.of(task)));

        assertEquals(Boolean.TRUE, capturedMetadata().get("streamingDowngraded"),
                "the skipCascade branch pushes the whole answer through one onToken as well");
        assertEquals(1.0, counterCount(), 0.0001);
    }

    @Test
    @DisplayName("no event sink → nothing was streamed, so nothing is flagged or counted")
    void noSink_noDowngradeRecorded() throws Exception {
        when(memory.getEventSink()).thenReturn(null);

        llmTask.execute(memory, new LlmConfiguration(List.of(toolTask())));

        var metadata = capturedMetadata();
        assertNull(metadata.get("streamingDowngraded"),
                "a non-streaming turn was never downgraded — flagging it would be a false alarm");
        assertFalse(metadata.containsKey("streamingDowngradeReason"));
        assertEquals(0.0, counterCount(), 0.0001);
    }

    // ---------------------------------------------------------------------
    // The third call site: the multi-model-cascade agent path.
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("the cascade agent path records the downgrade when it emits the answer as one chunk")
    void cascadeAgentModeRecordsDowngrade() throws Exception {
        when(agentOrchestrator.executeIfToolsEnabled(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(new AgentOrchestrator.ExecutionResult("agent answer", new ArrayList<>(), Map.of()));

        var task = toolTask();
        task.setModelCascade(agentModeCascade());

        llmTask.execute(memory, new LlmConfiguration(List.of(task)));

        // The whole answer really did go out as a single chunk…
        verify(eventSink).onToken("agent answer");
        // …so the client-visible downgrade must be observable in the metadata the
        // agent designer can branch on via responseMetadataObjectName.
        Map<String, Object> metadata = capturedMetadata();
        assertEquals(Boolean.TRUE, metadata.get("streamingDowngraded"),
                "the cascade agent path emits one chunk exactly like the two instrumented paths: " + metadata);
        assertEquals("tools_enabled", metadata.get("streamingDowngradeReason"));
    }

    @Test
    @DisplayName("a cascade LEGACY step is not reported as a tool downgrade")
    void cascadeLegacyModeDoesNotRecordDowngrade() throws Exception {
        // The orchestrator declines (no tools resolved) → the cascade step falls back
        // to the legacy executor, whose single-chunk emit is a different (non-tool)
        // situation and must NOT be labelled a tools_enabled downgrade.
        when(agentOrchestrator.executeIfToolsEnabled(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(null);
        when(chatModel.chat(anyList())).thenReturn(ChatResponse.builder()
                .aiMessage(aiMessage("legacy answer"))
                .metadata(ChatResponseMetadata.builder().tokenUsage(new TokenUsage(7, 3)).build())
                .build());

        var task = toolTask();
        task.setModelCascade(agentModeCascade());

        llmTask.execute(memory, new LlmConfiguration(List.of(task)));

        Map<String, Object> metadata = capturedMetadata();
        assertNull(metadata.get("streamingDowngraded"),
                "only the agent (tool-loop) path is a tools_enabled downgrade: " + metadata);
    }
}
