/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.shared.RetryConfiguration;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.engine.health.model.HealthFailure;
import ai.labs.eddi.engine.lifecycle.ConversationEventSink;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.DataFactory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.modules.apicalls.impl.IApiCallExecutor;
import ai.labs.eddi.modules.apicalls.impl.PrePostUtils;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.engine.memory.MemoryKeys.ACTIONS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Agent Health: a stream whose last attempt timed out with no text is recorded
 * once, with the recovery the task decided. Since the streaming contract (6.7)
 * such a timeout fails the model call instead of returning an empty answer, so
 * it reaches the task's failure path like any other model failure.
 */
@DisplayName("LlmTask — a streaming timeout with no text is recorded for Agent Health")
class LlmTaskStreamingTimeoutHealthTest {

    private ConversationMemory memory;
    private LlmTask llmTask;
    private ChatModelRegistry chatModelRegistry;

    @BeforeEach
    void setUp() throws Exception {
        var templatingEngine = mock(ITemplatingEngine.class);
        chatModelRegistry = mock(ChatModelRegistry.class);
        var memoryItemConverter = mock(IMemoryItemConverter.class);
        var promptSnippetService = mock(PromptSnippetService.class);
        var globalVariableResolver = mock(GlobalVariableResolver.class);
        lenient().when(promptSnippetService.getForAgent(any())).thenReturn(Map.of());
        lenient().when(globalVariableResolver.getTemplateData()).thenReturn(Map.of());
        lenient().when(globalVariableResolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(templatingEngine.processTemplate(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(memoryItemConverter.convert(any())).thenAnswer(inv -> new HashMap<String, Object>());
        lenient().when(chatModelRegistry.getOrCreate(anyString(), any())).thenReturn(mock(ChatModel.class));
        // A stream that never answers: the backstop ends it with no text.
        lenient().when(chatModelRegistry.getOrCreateStreaming(anyString(), any())).thenReturn(mock(StreamingChatModel.class));
        var counterweightService = mock(CounterweightService.class);
        lenient().when(counterweightService.apply(anyString(), any(), any(), any())).thenAnswer(inv -> inv.getArgument(0));
        var identityMaskingService = mock(IdentityMaskingService.class);
        lenient().when(identityMaskingService.apply(anyString(), any())).thenAnswer(inv -> inv.getArgument(0));

        llmTask = new LlmTask(mock(IResourceClientLibrary.class), new DataFactory(), memoryItemConverter, templatingEngine,
                new JsonSerialization(new ObjectMapper()), mock(PrePostUtils.class), chatModelRegistry, mock(IApiCallExecutor.class),
                mock(IAgentStore.class), mock(IWorkflowStore.class), mock(RagContextProvider.class), new TokenCounterFactory(),
                mock(ConversationSummarizer.class), promptSnippetService, globalVariableResolver, counterweightService, identityMaskingService,
                mock(AgentOrchestrator.class), new ConversationHistoryBuilder(), new SimpleMeterRegistry(), new CallerIdentityContext(null, null));

        memory = new ConversationMemory("conv-stream", "agent-stream", 1, "user-1");
        memory.getCurrentStep().storeData(new Data<>(ACTIONS.key(), List.of("answer")));
        memory.getCurrentStep().addConversationOutputString("input", "question");
        memory.setEventSink(mock(ConversationEventSink.class));
    }

    private static LlmConfiguration config(LlmConfiguration.ResponseValidation validation) {
        var task = new LlmConfiguration.Task();
        task.setId("writer");
        task.setType("openai");
        task.setActions(List.of("answer"));
        task.setStreamingTimeoutSeconds(1);
        var retry = new RetryConfiguration();
        retry.setMaxAttempts(1);
        task.setRetry(retry);
        var params = new HashMap<String, String>();
        params.put("apiKey", "key");
        params.put("modelName", "base-model");
        params.put("systemMessage", "You write answers.");
        task.setParameters(params);
        if (validation != null) {
            task.setResponseValidation(validation);
        }
        return new LlmConfiguration(List.of(task));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> records() {
        IData<Object> data = memory.getCurrentStep().getData(MemoryKeys.HEALTH_LLM_FAILURES);
        return data == null || data.getResult() == null ? List.of() : (List<Map<String, Object>>) data.getResult();
    }

    @Test
    @DisplayName("without validation the task fails, and the timeout is recorded once, unrecovered, with its model")
    void recordedOnce() {
        assertThrows(LifecycleException.class, () -> llmTask.execute(memory, config(null)));

        List<Map<String, Object>> records = records();
        assertEquals(1, records.size(), records.toString());
        assertEquals("TIMEOUT", records.get(0).get(HealthFailure.KEY_CLASS));
        assertEquals(HealthFailure.NONE, records.get(0).get(HealthFailure.KEY_RECOVERY));
        assertEquals("base-model", records.get(0).get(HealthFailure.KEY_MODEL));
    }

    @Test
    @DisplayName("a cascade whose last, streamed step times out with no text: the step it escalated past recovered nothing either")
    void cascadeEndingInATimeout() throws Exception {
        ChatModel failing = mock(ChatModel.class);
        when(failing.chat(anyList())).thenThrow(new IllegalStateException("provider down"));
        when(chatModelRegistry.getOrCreate(eq("openai"), any())).thenReturn(failing);
        var config = config(null);
        var cascade = new LlmConfiguration.ModelCascadeConfig();
        cascade.setEnabled(true);
        cascade.setEvaluationStrategy("none");
        var first = new LlmConfiguration.CascadeStep();
        first.setType("openai");
        first.setConfidenceThreshold(0.5);
        var last = new LlmConfiguration.CascadeStep();
        last.setType("anthropic");
        cascade.setSteps(List.of(first, last));
        config.tasks().get(0).setModelCascade(cascade);

        assertThrows(LifecycleException.class, () -> llmTask.execute(memory, config));

        List<Map<String, Object>> records = records();
        assertEquals(2, records.size(), records.toString());
        assertEquals("TIMEOUT", records.get(1).get(HealthFailure.KEY_CLASS));
        assertNotNull(records.get(1).get(HealthFailure.KEY_MODEL), "the timed-out step's model");
        assertTrue(records.stream().allMatch(r -> HealthFailure.NONE.equals(r.get(HealthFailure.KEY_RECOVERY))), records.toString());
    }

    @Test
    @DisplayName("onStreamingTimeout: error fails the task, and the timeout is still recorded once")
    void recordedWhenValidationFails() {
        var validation = new LlmConfiguration.ResponseValidation();
        validation.setEnabled(true);
        validation.setOnEmpty("ignore");
        validation.setOnStreamingTimeout("error");

        assertThrows(LifecycleException.class, () -> llmTask.execute(memory, config(validation)));

        List<Map<String, Object>> records = records();
        assertEquals(1, records.size(), records.toString());
        assertEquals("TIMEOUT", records.get(0).get(HealthFailure.KEY_CLASS));
        assertEquals(HealthFailure.NONE, records.get(0).get(HealthFailure.KEY_RECOVERY));
    }
}
