/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision;
import ai.labs.eddi.engine.lifecycle.model.HitlDecision.HitlVerdict;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IDataFactory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.ConversationProperties;
import ai.labs.eddi.engine.memory.model.PendingToolCallBatch;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.modules.apicalls.impl.IApiCallExecutor;
import ai.labs.eddi.modules.apicalls.impl.PrePostUtils;
import ai.labs.eddi.modules.llm.capability.JsonResponseFormatPolicy;
import ai.labs.eddi.modules.llm.capability.ModelCapabilityService.Support;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.testing.FaultInjectingChatModel;
import ai.labs.eddi.modules.llm.testing.FaultInjectingChatModel.Step;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ResponseFormat;
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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;

/**
 * WS10: {@code LlmTask} hands an {@code openai-compatible} task's own
 * parameters to the JSON policy (live and HITL-resume paths) and to the
 * attachment gate.
 */
@DisplayName("LlmTask — openai-compatible endpoint capabilities are threaded through")
class LlmTaskOpenAiCompatibleTest {

    @Mock
    private IResourceClientLibrary resourceClientLibrary;
    @Mock
    private IDataFactory dataFactory;
    @Mock
    private IMemoryItemConverter memoryItemConverter;
    @Mock
    private ITemplatingEngine templatingEngine;
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
    private IConversationMemory memory;
    @Mock
    private IWritableConversationStep currentStep;

    private LlmTask llmTask;

    @BeforeEach
    void setUp() throws Exception {
        openMocks(this);
        lenient().when(promptSnippetService.getAll()).thenReturn(Map.of());
        lenient().when(promptSnippetService.getForAgent(any())).thenReturn(Map.of());
        lenient().when(globalVariableResolver.getTemplateData()).thenReturn(Map.of());
        lenient().when(globalVariableResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(counterweightService.apply(anyString(), any(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(identityMaskingService.apply(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(dataFactory.createData(anyString(), any())).thenAnswer(inv -> {
            IData<?> d = mock(IData.class);
            lenient().when(d.getResult()).thenAnswer(x -> inv.getArgument(1));
            return d;
        });
        lenient().when(memory.getCurrentStep()).thenReturn(currentStep);
        var actionData = mock(IData.class);
        lenient().when(currentStep.getLatestData(ACTIONS)).thenReturn(actionData);
        lenient().when(actionData.getResult()).thenReturn(List.of("action1"));
        lenient().when(memoryItemConverter.convert(memory)).thenReturn(new HashMap<>());
        // Like Qute: {|...|} is a literal, which is how LlmTask keeps a configured
        // ${vars:...} reference intact through templating.
        lenient().when(templatingEngine.processTemplate(anyString(), any()))
                .thenAnswer(inv -> ((String) inv.getArgument(0)).replace("{|", "").replace("|}", ""));
        var inputData = mock(IData.class);
        lenient().when(currentStep.getLatestData("input")).thenReturn(inputData);
        lenient().when(inputData.getResult()).thenReturn("question");
        lenient().when(memory.getConversationProperties()).thenReturn(new ConversationProperties(memory));
        var output = new ConversationOutput();
        output.put("input", "question");
        lenient().when(memory.getConversationOutputs()).thenReturn(List.of(output));

        llmTask = new LlmTask(resourceClientLibrary, dataFactory, memoryItemConverter, templatingEngine,
                new JsonSerialization(new ObjectMapper()), prePostUtils, chatModelRegistry,
                mock(IApiCallExecutor.class), mock(IAgentStore.class), mock(IWorkflowStore.class),
                ragContextProvider, new TokenCounterFactory(), conversationSummarizer,
                promptSnippetService, globalVariableResolver, counterweightService,
                identityMaskingService, agentOrchestrator, new ConversationHistoryBuilder(),
                new SimpleMeterRegistry(), new CallerIdentityContext(null, null));
    }

    private static LlmConfiguration config(boolean tools, String... flags) {
        var t = new LlmConfiguration.Task();
        t.setId("taskA");
        t.setType("openai-compatible");
        t.setActions(List.of("action1"));
        var params = new HashMap<String, String>();
        params.put("baseUrl", "http://vllm:8000/v1");
        params.put("modelName", "granite");
        params.put("systemMessage", "You are helpful.");
        params.put("convertToObject", "true");
        if (flags.length % 2 != 0) {
            throw new IllegalArgumentException("key/value pairs expected, got " + flags.length + " values");
        }
        for (int i = 0; i < flags.length; i += 2) {
            params.put(flags[i], flags[i + 1]);
        }
        t.setParameters(params);
        t.setEnableBuiltInTools(tools);
        return new LlmConfiguration(List.of(t));
    }

    private FaultInjectingChatModel liveModel() throws Exception {
        var model = FaultInjectingChatModel.script(Step.text("{\"a\":\"b\"}")).repeatLast();
        when(chatModelRegistry.getOrCreate(anyString(), any())).thenReturn(model);
        return model;
    }

    @Test
    @DisplayName("live: jsonMode=true sends a JSON response format; default flags send none")
    void liveJsonPolicy() throws Exception {
        var model = liveModel();

        llmTask.execute(memory, config(false, "jsonMode", "true"));
        assertSame(ResponseFormat.JSON, model.requests().getFirst().responseFormat());

        llmTask.execute(memory, config(false));
        assertNull(model.requests().get(1).responseFormat(), "an unknown server must not get response_format by default");
    }

    @Test
    @DisplayName("templated flags: a Qute template and a ${vars:...} reference are read resolved")
    void templatedFlags() throws Exception {
        var model = liveModel();
        when(templatingEngine.processTemplate(eq("{context.selfJson}"), any())).thenReturn("true");
        when(globalVariableResolver.resolveValue("${vars:selfJson}")).thenReturn("true");

        llmTask.execute(memory, config(false, "jsonMode", "{context.selfJson}"));
        assertSame(ResponseFormat.JSON, model.requests().getFirst().responseFormat());

        llmTask.execute(memory, config(false, "jsonMode", "${vars:selfJson}"));
        assertSame(ResponseFormat.JSON, model.requests().get(1).responseFormat());
    }

    @Test
    @DisplayName("a flag that resolves to a non-boolean degrades to false and the turn still completes")
    void unresolvableFlagDegrades() throws Exception {
        var model = liveModel();
        when(globalVariableResolver.resolveValue("${vars:missing}")).thenReturn("${vars:missing}");
        var forwarder = mock(AttachmentForwarder.class);
        llmTask.attachmentForwarder = forwarder;

        llmTask.execute(memory, config(false, "jsonMode", "${vars:missing}", "vision", "maybe"));

        assertNull(model.requests().getFirst().responseFormat());
        verify(forwarder).forward(any(), eq(memory), eq("openai-compatible"), eq("granite"), eq(Support.AUTO), any(), any());
    }

    @Test
    @DisplayName("HITL resume: the continuation gets the endpoint's own capabilities")
    void resumeJsonPolicy() throws Exception {
        var batch = new PendingToolCallBatch();
        batch.setLlmTaskId("taskA");
        batch.setLlmTaskIndex(0);
        var decision = new HitlDecision();
        decision.setVerdict(HitlVerdict.APPROVED);
        when(memory.getHitlPendingToolCalls()).thenReturn(batch);
        when(memory.getHitlResumeDecision()).thenReturn(decision);
        when(chatModelRegistry.getOrCreate(anyString(), any())).thenReturn(mock(ChatModel.class));
        when(agentOrchestrator.resumeToolLoop(any(), any(), any(), any(), any(), anyBoolean(), any()))
                .thenReturn(new AgentOrchestrator.ExecutionResult("{\"a\":\"b\"}", new ArrayList<>()));

        llmTask.execute(memory, config(true, "jsonMode", "true", "jsonModeWithTools", "true"));

        ArgumentCaptor<JsonResponseFormatPolicy> policy = ArgumentCaptor.forClass(JsonResponseFormatPolicy.class);
        verify(agentOrchestrator).resumeToolLoop(any(), any(), any(), any(), any(), anyBoolean(), policy.capture());
        assertTrue(policy.getValue().endpoint() != null && policy.getValue().endpoint().jsonModeWithTools(),
                "resume must use the endpoint flags: " + policy.getValue());
        assertSame(ResponseFormat.JSON, policy.getValue().resolve(true));
    }

    @Test
    @DisplayName("attachments: vision=true reaches the forwarder as ON; absent stays AUTO")
    void visionReachesForwarder() throws Exception {
        liveModel();
        var forwarder = mock(AttachmentForwarder.class);
        llmTask.attachmentForwarder = forwarder;

        llmTask.execute(memory, config(false, "vision", "true"));
        verify(forwarder).forward(any(), eq(memory), eq("openai-compatible"), eq("granite"), eq(Support.ON), any(), any());

        llmTask.execute(memory, config(false));
        verify(forwarder).forward(any(), eq(memory), eq("openai-compatible"), eq("granite"), eq(Support.AUTO), any(), any());
    }
}
