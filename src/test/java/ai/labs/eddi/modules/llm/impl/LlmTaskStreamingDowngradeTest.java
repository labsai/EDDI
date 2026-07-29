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
import ai.labs.eddi.modules.apicalls.impl.IApiCallExecutor;
import ai.labs.eddi.modules.apicalls.impl.PrePostUtils;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.CascadeStep;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.ModelCascadeConfig;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import dev.langchain4j.model.chat.ChatModel;
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
 * Finding F10 — a tool-enabled task cannot stream token by token: the agent
 * loop is synchronous and the finished answer is pushed through a single
 * {@code onToken}. To the SSE client that is a long silence followed by one
 * enormous token, indistinguishable from a slow stream.
 * <p>
 * {@code recordStreamingDowngrade} makes it observable — a
 * {@code streamingDowngraded} flag in {@code responseMetadata} (so an agent
 * designer can branch on it via {@code responseMetadataObjectName}) plus an
 * {@code eddi.llm.streaming.downgraded} counter for operators. These tests pin
 * both; deleting the call sites or the method turns them red.
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

    @BeforeEach
    void setUp() throws Exception {
        openMocks(this);

        meterRegistry = new SimpleMeterRegistry();

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
                meterRegistry);

        lenient().when(dataFactory.createData(anyString(), any())).thenAnswer(inv -> {
            IData<?> d = mock(IData.class);
            lenient().when(d.getResult()).thenAnswer(x -> inv.getArgument(1));
            return d;
        });

        when(memory.getCurrentStep()).thenReturn(currentStep);
        var actionData = mock(IData.class);
        lenient().when(currentStep.getLatestData(ACTIONS)).thenReturn(actionData);
        lenient().when(actionData.getResult()).thenReturn(List.of("action1"));
        lenient().when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());
        lenient().when(memory.getHitlPendingToolCalls()).thenReturn(null);
        lenient().when(memory.getHitlResumeDecision()).thenReturn(null);
        var conversationOutput = new ConversationOutput();
        conversationOutput.put("input", "user input");
        lenient().when(memory.getConversationOutputs()).thenReturn(List.of(conversationOutput));
        lenient().when(chatModelRegistry.getOrCreate(anyString(), any())).thenReturn(chatModel);
        lenient().when(templatingEngine.processTemplate(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
        var inputData = mock(IData.class);
        lenient().when(currentStep.getLatestData("input")).thenReturn(inputData);
        lenient().when(inputData.getResult()).thenReturn("user input");

        lenient().when(agentOrchestrator.executeIfToolsEnabled(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any()))
                .thenReturn(new AgentOrchestrator.ExecutionResult("the whole agent answer", new ArrayList<>()));
    }

    private LlmConfiguration.Task toolTask() {
        var t = new LlmConfiguration.Task();
        t.setId("taskA");
        t.setType("openai");
        t.setActions(List.of("action1"));
        var params = new HashMap<String, String>();
        params.put("apiKey", "key");
        t.setParameters(params);
        t.setResponseMetadataObjectName(METADATA_KEY);
        t.setEnableBuiltInTools(true);
        return t;
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

        var t = toolTask();
        t.setModelCascade(cascade);

        llmTask.execute(memory, new LlmConfiguration(List.of(t)));

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
}
