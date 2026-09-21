/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.shared.RetryConfiguration;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.engine.lifecycle.ConversationEventSink;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.CascadeStep;
import ai.labs.eddi.modules.llm.model.LlmConfiguration.ModelCascadeConfig;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Finding F9 — only the LAST cascade step may be streamed live.
 * <p>
 * "Guaranteed accept" (a null {@code confidenceThreshold}, or a {@code none}
 * strategy) only holds while the step SUCCEEDS. A non-final step that was
 * streamed live and then dies mid-stream has already pushed tokens into the SSE
 * sink, and the escalated step streams its own full answer into the SAME sink —
 * the client renders the first step's fragment glued to the next step's answer,
 * and this executor cannot un-send tokens.
 * <p>
 * Every pre-existing streaming test uses a single-step cascade or asserts on
 * the last step, so dropping {@code && isLastStep} left the whole suite green.
 */
@DisplayName("CascadingModelExecutor — only the last step streams live (F9)")
class CascadingModelExecutorLiveStreamScopeTest {

    private static final String CHEAP_FRAGMENT = "cheap step fragment…";
    private static final String FINAL_ANSWER = "the final answer";

    private static IConversationMemory memory(ConversationEventSink sink) {
        IConversationMemory memory = mock(IConversationMemory.class);
        when(memory.getEventSink()).thenReturn(sink);
        return memory;
    }

    private static LlmConfiguration.Task task() {
        var task = new LlmConfiguration.Task();
        task.setId("t");
        task.setType("openai");
        task.setParameters(Map.of("apiKey", "test-key"));
        var retry = new RetryConfiguration();
        retry.setMaxAttempts(1);
        retry.setBackoffDelayMs(1L);
        task.setRetry(retry);
        return task;
    }

    private static List<ChatMessage> messages() {
        var m = new ArrayList<ChatMessage>();
        m.add(SystemMessage.from("sys"));
        m.add(UserMessage.from("hi"));
        return m;
    }

    private static CascadingModelExecutor executor(ChatModelRegistry registry) {
        GlobalVariableResolver resolver = mock(GlobalVariableResolver.class);
        when(resolver.resolveValue(anyString())).thenAnswer(inv -> inv.getArgument(0));
        return new CascadingModelExecutor(registry, resolver, null, new LegacyChatExecutor(), new StreamingLegacyChatExecutor(), null,
                new CallerIdentityContext(null, null));
    }

    private static StreamingChatModel streamingModelEmitting(String text) {
        StreamingChatModel streaming = mock(StreamingChatModel.class);
        doAnswer(inv -> {
            StreamingChatResponseHandler h = inv.getArgument(1);
            h.onPartialResponse(text);
            h.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from(text)).build());
            return null;
        }).when(streaming).chat(any(ChatRequest.class), any(StreamingChatResponseHandler.class));
        return streaming;
    }

    /**
     * Two steps; the first is "guaranteed accept" (null threshold) but NOT last,
     * and it fails. Before the fix it received a streaming model and emitted its
     * fragment to the client before dying.
     */
    private static ModelCascadeConfig twoStepCascade() {
        var cascade = new ModelCascadeConfig();
        cascade.setEnabled(true);
        cascade.setEvaluationStrategy("none");

        var cheap = new CascadeStep();
        cheap.setType("cheap");
        cheap.setConfidenceThreshold(null); // → guaranteedAccept, yet not the last step
        cheap.setTimeoutMs(5000L);

        var expensive = new CascadeStep();
        expensive.setType("expensive");
        expensive.setTimeoutMs(5000L);

        cascade.setSteps(List.of(cheap, expensive));
        return cascade;
    }

    @Test
    @DisplayName("a non-final step is never given a streaming model, even when it is 'guaranteed accept'")
    void nonFinalStep_isNeverStreamedLive() throws Exception {
        var sink = mock(ConversationEventSink.class);

        ChatModel cheapBuffered = mock(ChatModel.class);
        when(cheapBuffered.chat(anyList())).thenThrow(new RuntimeException("cheap provider died mid-answer"));

        ChatModelRegistry registry = mock(ChatModelRegistry.class);
        when(registry.getOrCreate(eq("cheap"), anyMap())).thenReturn(cheapBuffered);
        when(registry.getOrCreate(eq("expensive"), anyMap())).thenReturn(mock(ChatModel.class));
        // Deliberately available: the point is that step 0 must not ASK for it.
        // streamingModelEmitting() stubs its own mock, so it must be evaluated before
        // the outer when(...) opens — a nested stubbing inside an unfinished one is
        // an UnfinishedStubbing error, not a compile error.
        StreamingChatModel cheapStreaming = streamingModelEmitting(CHEAP_FRAGMENT);
        StreamingChatModel expensiveStreaming = streamingModelEmitting(FINAL_ANSWER);
        when(registry.getOrCreateStreaming(eq("cheap"), anyMap())).thenReturn(cheapStreaming);
        when(registry.getOrCreateStreaming(eq("expensive"), anyMap())).thenReturn(expensiveStreaming);

        var result = executor(registry).execute(twoStepCascade(), messages(), "sys", Map.of("apiKey", "k"), task(), memory(sink),
                mock(AgentOrchestrator.class), Map.of(), false, false, /* allowLiveStreaming */ true);

        verify(registry, never()).getOrCreateStreaming(eq("cheap"), anyMap());
        verify(sink, never()).onToken(CHEAP_FRAGMENT);

        // The last step still streams live — the fix narrows the scope, it does not
        // disable live streaming.
        verify(registry).getOrCreateStreaming(eq("expensive"), anyMap());
        verify(sink).onToken(FINAL_ANSWER);
        assertEquals(FINAL_ANSWER, result.response());
        assertEquals(1, result.stepUsed());
        assertTrue(result.streamedLive(), "the final step is still streamed live");
    }

    @Test
    @DisplayName("exactly one answer reaches the client — no fragment of the failed earlier step")
    void clientSeesOnlyTheFinalAnswer() throws Exception {
        var sink = mock(ConversationEventSink.class);

        ChatModel cheapBuffered = mock(ChatModel.class);
        when(cheapBuffered.chat(anyList())).thenThrow(new RuntimeException("cheap provider died mid-answer"));

        ChatModelRegistry registry = mock(ChatModelRegistry.class);
        when(registry.getOrCreate(eq("cheap"), anyMap())).thenReturn(cheapBuffered);
        when(registry.getOrCreate(eq("expensive"), anyMap())).thenReturn(mock(ChatModel.class));
        // streamingModelEmitting() stubs its own mock, so it must be evaluated before
        // the outer when(...) opens — a nested stubbing inside an unfinished one is
        // an UnfinishedStubbing error, not a compile error.
        StreamingChatModel cheapStreaming = streamingModelEmitting(CHEAP_FRAGMENT);
        StreamingChatModel expensiveStreaming = streamingModelEmitting(FINAL_ANSWER);
        when(registry.getOrCreateStreaming(eq("cheap"), anyMap())).thenReturn(cheapStreaming);
        when(registry.getOrCreateStreaming(eq("expensive"), anyMap())).thenReturn(expensiveStreaming);

        executor(registry).execute(twoStepCascade(), messages(), "sys", Map.of("apiKey", "k"), task(), memory(sink),
                mock(AgentOrchestrator.class), Map.of(), false, false, /* allowLiveStreaming */ true);

        // Without the isLastStep guard the sink receives TWO token events — the dead
        // step's fragment followed by the real answer — and the client renders them
        // concatenated.
        verify(sink).onToken(anyString());
    }
}
