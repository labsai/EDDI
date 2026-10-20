/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.configs.workflows.IWorkflowStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IDataFactory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.modules.apicalls.impl.IApiCallExecutor;
import ai.labs.eddi.modules.apicalls.impl.PrePostUtils;
import ai.labs.eddi.modules.llm.capability.JsonResponseFormatPolicy;
import ai.labs.eddi.modules.llm.guardrails.Reprompter;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.CascadeStep;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.ModelCascadeConfig;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The guardrail reprompt of a cascade step builds its JSON-format policy from
 * the STEP's own parameters, capability flags included - the same way the
 * cascade does - so an openai-compatible step that declares {@code jsonMode} is
 * not re-asked without a response format it was just served with.
 */
@DisplayName("LlmTask - cascade guardrail reprompt keeps the step's capability flags")
class LlmTaskCascadeRepromptPolicyTest {

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = LlmTask.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    @DisplayName("an openai-compatible step's jsonMode flag reaches the reprompt's policy")
    void capabilityFlagsReachTheRepromptPolicy() throws Exception {
        var globals = mock(GlobalVariableResolver.class);
        when(globals.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        var registry = mock(ChatModelRegistry.class);
        when(registry.getOrCreate(anyString(), any())).thenReturn(mock(ChatModel.class));
        var llmTask = new LlmTask(mock(IResourceClientLibrary.class), mock(IDataFactory.class), mock(IMemoryItemConverter.class),
                mock(ITemplatingEngine.class), mock(IJsonSerialization.class), mock(PrePostUtils.class), registry, mock(IApiCallExecutor.class),
                mock(IAgentStore.class), mock(IWorkflowStore.class), mock(RagContextProvider.class), new TokenCounterFactory(),
                mock(ConversationSummarizer.class), mock(PromptSnippetService.class), globals, mock(CounterweightService.class),
                mock(IdentityMaskingService.class), mock(AgentOrchestrator.class), new ConversationHistoryBuilder(), new SimpleMeterRegistry(),
                new CallerIdentityContext(null, null));

        var cascading = mock(CascadingModelExecutor.class);
        Map<String, String> stepParams = new HashMap<>();
        stepParams.put("jsonMode", "true");
        when(cascading.resolveStepModel(any(), any(), any(), any(), any(), any()))
                .thenReturn(new CascadingModelExecutor.StepModel("openai-compatible", stepParams));
        var legacy = mock(LegacyChatExecutor.class);
        when(legacy.execute(any(), any(), any(), any(), any(), any())).thenReturn(new LegacyChatExecutor.ChatResult("{}", Map.of()));
        set(llmTask, "cascadingModelExecutor", cascading);
        set(llmTask, "legacyChatExecutor", legacy);

        var config = new ModelCascadeConfig();
        var step = new CascadeStep();
        config.setSteps(List.of(step));
        var task = new LlmConfiguration.Task();
        task.setId("t");
        var memory = mock(IConversationMemory.class);
        List<ChatMessage> request = List.of(UserMessage.from("hi"));
        var result = new CascadingModelExecutor.CascadeResult("{}", 1.0, 0, "openai-compatible", "m", Map.of(), 0, 0, List.of(), null, false,
                Map.of());

        Method m = LlmTask.class.getDeclaredMethod("cascadeReprompter", ModelCascadeConfig.class, CascadingModelExecutor.CascadeResult.class,
                LlmConfiguration.Task.class, Map.class, Map.class, IConversationMemory.class, boolean.class, String.class, List.class, List.class);
        m.setAccessible(true);
        var reprompter = (Reprompter) m.invoke(llmTask, config, result, task, new HashMap<String, String>(), new HashMap<String, Object>(), memory,
                true, "sys", request, request);
        reprompter.reprompt("bad", "fix it");

        var policy = ArgumentCaptor.forClass(JsonResponseFormatPolicy.class);
        verify(legacy).execute(any(), any(), any(), policy.capture(), any(), any());
        assertTrue(policy.getValue().applies(false), "the step declared jsonMode, so the reprompt must request the JSON format");
    }
}
