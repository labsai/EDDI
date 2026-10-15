/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.shared.RetryConfiguration;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.CascadeStep;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.ModelCascadeConfig;
import ai.labs.eddi.modules.llm.testing.FaultInjectingChatModel;
import ai.labs.eddi.modules.llm.testing.FaultInjectingChatModel.Step;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * WS10 trap: a cascade step's JSON policy reads the <em>step's</em> endpoint
 * flags, not the task's.
 */
@DisplayName("CascadingModelExecutor — openai-compatible step capabilities")
class CascadingModelExecutorOpenAiCompatibleTest {

    private static ChatRequest runSingleStep(String taskType, Map<String, String> taskParams, Map<String, String> stepParams)
            throws Exception {
        var model = FaultInjectingChatModel.script(Step.text("{\"a\":\"b\"}")).repeatLast();
        ChatModelRegistry registry = mock(ChatModelRegistry.class);
        doReturn(model).when(registry).getOrCreate(eq("openai-compatible"), anyMap());

        var step = new CascadeStep();
        step.setType("openai-compatible");
        step.setParameters(stepParams);
        var cascade = new ModelCascadeConfig();
        cascade.setEnabled(true);
        cascade.setSteps(List.of(step));

        var task = new LlmConfiguration.Task();
        task.setId("cascade-task");
        task.setType(taskType);
        task.setParameters(taskParams);
        var retry = new RetryConfiguration();
        retry.setMaxAttempts(1);
        retry.setBackoffDelayMs(10L);
        task.setRetry(retry);

        GlobalVariableResolver resolver = mock(GlobalVariableResolver.class);
        when(resolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        var executor = new CascadingModelExecutor(registry, resolver, null, new LegacyChatExecutor(), new StreamingLegacyChatExecutor(), null,
                new CallerIdentityContext(null, null));
        List<ChatMessage> messages = List.of(SystemMessage.from("sys"), UserMessage.from("hi"));
        IConversationMemory memory = mock(IConversationMemory.class);
        executor.execute(cascade, messages, "sys", taskParams, task, memory, mock(AgentOrchestrator.class), Map.of(), true, true, false);
        return model.requests().getFirst();
    }

    @Test
    @DisplayName("the step's jsonMode=true applies even though the task is a different type")
    void stepFlagApplies() throws Exception {
        var request = runSingleStep("anthropic", new HashMap<>(Map.of("apiKey", "k")),
                Map.of("baseUrl", "http://vllm:8000/v1", "modelName", "granite", "jsonMode", "true"));
        assertSame(ResponseFormat.JSON, request.responseFormat());
    }

    @Test
    @DisplayName("a step without flags gets no JSON format, even when the task's type would")
    void stepDefaultsApply() throws Exception {
        var request = runSingleStep("openai", new HashMap<>(Map.of("apiKey", "k")),
                Map.of("baseUrl", "http://vllm:8000/v1", "modelName", "granite"));
        assertNull(request.responseFormat());
    }
}
