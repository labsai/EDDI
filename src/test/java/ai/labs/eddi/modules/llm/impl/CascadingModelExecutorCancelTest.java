/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.shared.CallCancelledException;
import ai.labs.eddi.configs.shared.RetryConfiguration;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.engine.lifecycle.ConversationEventSink;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.CascadeStep;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.ModelCascadeConfig;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S2: a cancel during a cascade stops the cascade. A cancelled step is not a
 * model failure — it must not escalate to the next (more expensive) model — and
 * a live final step that is cancelled keeps the text it streamed as error
 * detail.
 */
@DisplayName("CascadingModelExecutor — a cancelled turn stops the cascade")
class CascadingModelExecutorCancelTest {

    private static LlmConfiguration.Task task() {
        var task = new LlmConfiguration.Task();
        task.setId("t");
        task.setType("openai");
        task.setParameters(Map.of("apiKey", "test-key"));
        var retry = new RetryConfiguration();
        retry.setMaxAttempts(3);
        retry.setBackoffDelayMs(1L);
        task.setRetry(retry);
        return task;
    }

    private static List<ChatMessage> messages() {
        return List.of(SystemMessage.from("sys"), UserMessage.from("hi"));
    }

    private static CascadingModelExecutor executor(ChatModelRegistry registry) {
        GlobalVariableResolver resolver = mock(GlobalVariableResolver.class);
        when(resolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        return new CascadingModelExecutor(registry, resolver, null, new LegacyChatExecutor(), new StreamingLegacyChatExecutor(), null,
                new CallerIdentityContext(null, null));
    }

    private static CascadeStep step(String type, Double threshold) {
        var step = new CascadeStep();
        step.setType(type);
        step.setConfidenceThreshold(threshold);
        step.setTimeoutMs(30_000L);
        return step;
    }

    private static ModelCascadeConfig cascade(CascadeStep... steps) {
        var cascade = new ModelCascadeConfig();
        cascade.setEnabled(true);
        cascade.setEvaluationStrategy("none");
        cascade.setSteps(List.of(steps));
        return cascade;
    }

    @Test
    @DisplayName("a step stopped by the turn's cancel does not escalate to the next model")
    void cancelledStepDoesNotEscalate() throws Exception {
        ChatModel cheap = mock(ChatModel.class);
        when(cheap.chat(any(ChatRequest.class))).thenThrow(new CallCancelledException("turn cancelled"));
        when(cheap.chat(anyList())).thenThrow(new CallCancelledException("turn cancelled"));
        ChatModel expensive = mock(ChatModel.class);
        ChatModelRegistry registry = mock(ChatModelRegistry.class);
        when(registry.getOrCreate(eq("cheap"), anyMap())).thenReturn(cheap);
        when(registry.getOrCreate(eq("expensive"), anyMap())).thenReturn(expensive);
        IConversationMemory memory = mock(IConversationMemory.class);
        when(memory.getEventSink()).thenReturn(mock(ConversationEventSink.class));

        assertThrows(LifecycleException.LifecycleInterruptedException.class,
                () -> executor(registry).execute(cascade(step("cheap", 0.9), step("expensive", null)), messages(), "sys", Map.of("apiKey", "k"),
                        task(), memory, mock(AgentOrchestrator.class), Map.of(), false, false, false));

        verify(expensive, never()).chat(any(ChatRequest.class));
        verify(expensive, never()).chat(anyList());
    }

    @Test
    @DisplayName("a cancel during the live final step stops its stream and keeps the streamed text as error detail")
    void cancelDuringLiveFinalStep() throws Exception {
        var cancelled = new AtomicBoolean();
        StreamingChatModel streaming = mock(StreamingChatModel.class);
        doAnswer(inv -> {
            StreamingChatResponseHandler handler = inv.getArgument(1);
            handler.onPartialResponse("half an ans");
            // The client pressed stop; the provider never completes.
            cancelled.set(true);
            return null;
        }).when(streaming).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        ChatModelRegistry registry = mock(ChatModelRegistry.class);
        when(registry.getOrCreate(eq("only"), anyMap())).thenReturn(mock(ChatModel.class));
        when(registry.getOrCreateStreaming(eq("only"), anyMap())).thenReturn(streaming);
        IConversationMemory memory = mock(IConversationMemory.class);
        when(memory.getEventSink()).thenReturn(mock(ConversationEventSink.class));
        when(memory.isCancelled()).thenAnswer(inv -> cancelled.get());
        IConversationMemory.IWritableConversationStep currentStep = mock(IConversationMemory.IWritableConversationStep.class);
        when(memory.getCurrentStep()).thenReturn(currentStep);

        assertThrows(LifecycleException.LifecycleInterruptedException.class,
                () -> executor(registry).execute(cascade(step("only", null)), messages(), "sys", Map.of("apiKey", "k"), task(), memory,
                        mock(AgentOrchestrator.class), Map.of(), false, false, true));

        verify(streaming).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        verify(currentStep).storeData(argThat((IData<?> data) -> data.getKey().startsWith(MemoryKeys.LLM_PARTIAL_OUTPUT_PREFIX)
                && "half an ans".equals(data.getResult())));
    }
}
