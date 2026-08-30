/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.lifecycle.ConversationEventSink;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IDataFactory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.modules.apicalls.impl.IApiCallExecutor;
import ai.labs.eddi.modules.apicalls.impl.PrePostUtils;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * Tool-enabled turns stream token-by-token: with the kill-switch on and an
 * event sink present, {@code LlmTask} hands the tool loop a
 * {@link ToolLoopStreamingChatModel} instead of the synchronous model, and —
 * when the final answer was in fact delivered live — suppresses both the
 * single-chunk fallback emit and the F10 downgrade record.
 * <p>
 * The suppression is an exact-match comparison against what the bridge's last
 * round actually forwarded, so the fallback (and its downgrade record) still
 * fires for a buffered provider that never emits partials, for a synthetic
 * final message (iteration budget), and whenever the streaming builder is
 * unavailable. Both directions are pinned here; reverting the suppression makes
 * the double-emit test red, reverting the fallback makes the buffered-provider
 * test red.
 */
@DisplayName("LlmTask — tool-enabled turns stream via the bridge")
class LlmTaskToolLoopStreamingTest {

    private static final String METADATA_KEY = "llmMeta";
    private static final String FINAL_ANSWER = "the whole agent answer";
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
                mock(IApiCallExecutor.class), mock(IAgentStore.class), mock(IWorkflowStore.class),
                ragContextProvider, new TokenCounterFactory(), conversationSummarizer,
                promptSnippetService, globalVariableResolver, counterweightService,
                identityMaskingService, agentOrchestrator, new ConversationHistoryBuilder(),
                meterRegistry, new CallerIdentityContext(null, null));
        // Direct construction leaves the field-injected kill-switch at false —
        // which is exactly why every OTHER LlmTask test keeps its pre-streaming
        // behaviour. These tests opt in.
        llmTask.toolLoopStreamingEnabled = true;

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
        lenient().when(memory.getEventSink()).thenReturn(eventSink);

        // The orchestrator drives whatever model it is handed for one round and
        // returns that round's text — the tightest faithful stand-in for the real
        // loop's final round.
        lenient().when(agentOrchestrator.executeIfToolsEnabled(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any()))
                .thenAnswer(inv -> {
                    ChatModel model = inv.getArgument(0);
                    var response = model.chat(ChatRequest.builder().messages(List.of(UserMessage.from("hi"))).build());
                    return new AgentOrchestrator.ExecutionResult(response.aiMessage().text(), new ArrayList<>());
                });
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

    /** Streams the final answer in two partials, then completes with it. */
    private static StreamingChatModel streamingModel() {
        return new StreamingChatModel() {
            @Override
            public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                handler.onPartialResponse("the whole ");
                handler.onPartialResponse("agent answer");
                handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from(FINAL_ANSWER)).build());
            }
        };
    }

    /** Never emits a partial — the buffered-provider shape (F8). */
    private static StreamingChatModel bufferedModel() {
        return new StreamingChatModel() {
            @Override
            public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from(FINAL_ANSWER)).build());
            }
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> capturedMetadata() {
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(prePostUtils).createMemoryEntry(eq(currentStep), captor.capture(), eq(METADATA_KEY), eq("langchain"));
        return captor.getValue();
    }

    private double downgradeCount() {
        var counter = meterRegistry.find(COUNTER).tag("reason", "tools_enabled").counter();
        return counter != null ? counter.count() : 0.0;
    }

    @Test
    @DisplayName("a streamed final answer is not emitted a second time, and is not a downgrade")
    void streamedAnswerIsNotDoubleEmitted() throws Exception {
        when(chatModelRegistry.getOrCreateStreaming(anyString(), any())).thenReturn(streamingModel());

        llmTask.execute(memory, new LlmConfiguration(List.of(toolTask())));

        // Tokens went out live, in order — and NOTHING else: the third onToken
        // would be the single-chunk fallback duplicating the whole answer.
        var inOrder = inOrder(eventSink);
        inOrder.verify(eventSink).onToken("the whole ");
        inOrder.verify(eventSink).onToken("agent answer");
        verify(eventSink, times(2)).onToken(anyString());

        var metadata = capturedMetadata();
        assertEquals(Boolean.TRUE, metadata.get("streamedLive"));
        assertNull(metadata.get("streamingDowngraded"), "a turn that streamed live is not a downgrade");
        assertEquals(0.0, downgradeCount());
    }

    @Test
    @DisplayName("the tool loop receives the bridge, not the synchronous model")
    void toolLoopReceivesTheBridge() throws Exception {
        when(chatModelRegistry.getOrCreateStreaming(anyString(), any())).thenReturn(streamingModel());

        llmTask.execute(memory, new LlmConfiguration(List.of(toolTask())));

        ArgumentCaptor<ChatModel> modelCaptor = ArgumentCaptor.forClass(ChatModel.class);
        verify(agentOrchestrator).executeIfToolsEnabled(modelCaptor.capture(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any());
        assertInstanceOf(ToolLoopStreamingChatModel.class, modelCaptor.getValue());
    }

    @Test
    @DisplayName("a buffered provider (no partials) still gets the single-chunk fallback and the downgrade record")
    void bufferedProviderKeepsFallbackEmit() throws Exception {
        when(chatModelRegistry.getOrCreateStreaming(anyString(), any())).thenReturn(bufferedModel());

        llmTask.execute(memory, new LlmConfiguration(List.of(toolTask())));

        // Nothing streamed → the client's only copy of the answer is this emit.
        verify(eventSink).onToken(FINAL_ANSWER);
        verify(eventSink, times(1)).onToken(anyString());
        var metadata = capturedMetadata();
        assertEquals(Boolean.TRUE, metadata.get("streamingDowngraded"));
        assertEquals(1.0, downgradeCount());
    }

    @Test
    @DisplayName("no streaming builder for the provider → previous behaviour, synchronous model and fallback emit")
    void noStreamingBuilderFallsBackToSynchronousModel() throws Exception {
        when(chatModelRegistry.getOrCreateStreaming(anyString(), any())).thenReturn(null);
        // doReturn: a when() re-stub would run the setUp thenAnswer during stubbing.
        doReturn(new AgentOrchestrator.ExecutionResult(FINAL_ANSWER, new ArrayList<>()))
                .when(agentOrchestrator).executeIfToolsEnabled(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any());

        llmTask.execute(memory, new LlmConfiguration(List.of(toolTask())));

        ArgumentCaptor<ChatModel> modelCaptor = ArgumentCaptor.forClass(ChatModel.class);
        verify(agentOrchestrator).executeIfToolsEnabled(modelCaptor.capture(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any());
        assertSame(chatModel, modelCaptor.getValue());
        verify(eventSink).onToken(FINAL_ANSWER);
        assertEquals(1.0, downgradeCount());
    }

    @Test
    @DisplayName("addToOutput=false → no bridge: the postResponse owns the output, live tokens would leak it")
    void addToOutputFalseNeverStreams() throws Exception {
        var task = toolTask();
        task.getParameters().put("addToOutput", "false");
        doReturn(new AgentOrchestrator.ExecutionResult(FINAL_ANSWER, new ArrayList<>()))
                .when(agentOrchestrator).executeIfToolsEnabled(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any());

        llmTask.execute(memory, new LlmConfiguration(List.of(task)));

        verify(chatModelRegistry, times(0)).getOrCreateStreaming(anyString(), any());
        verify(eventSink, times(0)).onToken(anyString());
    }

    /**
     * The HITL continuation streams too — reverting the {@code resumeBridge} wiring
     * in {@code executeResume} silently loses live streaming for exactly the turns
     * a human just approved.
     */
    @Test
    @DisplayName("the resume loop also receives the bridge")
    void resumeLoopReceivesTheBridge() throws Exception {
        when(chatModelRegistry.getOrCreateStreaming(anyString(), any())).thenReturn(streamingModel());
        var batch = new PendingToolCallBatch();
        batch.setLlmTaskId("taskA");
        batch.setLlmTaskIndex(0);
        var resumeDecision = new HitlDecision();
        resumeDecision.setVerdict(HitlDecision.HitlVerdict.APPROVED);
        when(memory.getHitlPendingToolCalls()).thenReturn(batch);
        when(memory.getHitlResumeDecision()).thenReturn(resumeDecision);
        doReturn(new AgentOrchestrator.ExecutionResult(FINAL_ANSWER, new ArrayList<>()))
                .when(agentOrchestrator).resumeToolLoop(any(), any(), any(), any(), any(), anyBoolean(), any());

        llmTask.execute(memory, new LlmConfiguration(List.of(toolTask())));

        ArgumentCaptor<ChatModel> modelCaptor = ArgumentCaptor.forClass(ChatModel.class);
        verify(agentOrchestrator).resumeToolLoop(modelCaptor.capture(), any(), any(), any(), any(), anyBoolean(), any());
        assertInstanceOf(ToolLoopStreamingChatModel.class, modelCaptor.getValue());
    }

    @Test
    @DisplayName("kill-switch off → the bridge is never built, previous behaviour exactly")
    void killSwitchOffKeepsPreviousBehaviour() throws Exception {
        llmTask.toolLoopStreamingEnabled = false;
        // doReturn: a when() re-stub would run the setUp thenAnswer during stubbing.
        doReturn(new AgentOrchestrator.ExecutionResult(FINAL_ANSWER, new ArrayList<>()))
                .when(agentOrchestrator).executeIfToolsEnabled(any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), any());

        llmTask.execute(memory, new LlmConfiguration(List.of(toolTask())));

        verify(chatModelRegistry, times(0)).getOrCreateStreaming(anyString(), any());
        verify(eventSink).onToken(FINAL_ANSWER);
        assertEquals(1.0, downgradeCount());
    }
}
