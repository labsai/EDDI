/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.shared.RetryConfiguration;
import ai.labs.eddi.engine.lifecycle.ConversationEventSink;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.modules.llm.capability.JsonResponseFormatPolicy;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import ai.labs.eddi.modules.llm.model.StreamingConfig;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.FinishReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

/**
 * Finding R3 / B1-B5: the same scripted provider behaviour must end the same
 * way whichever path runs it — the non-streaming call
 * ({@link LegacyChatExecutor}), the no-tools streaming path
 * ({@link StreamingLegacyChatExecutor}) and a tool loop round over the
 * streaming bridge (the bridge inside {@code executeWithRetry}, exactly as
 * {@code ToolLoopRunner} calls it).
 * <p>
 * Each behaviour is scripted twice: as a stream of callbacks, and as what the
 * same failure looks like on a synchronous transport (a broken stream is an I/O
 * error there, a stall a timeout).
 */
@DisplayName("Streaming contract — parity across the three model-call paths")
class StreamingContractParityTest {

    private static final String TEXT = "Hello world";

    /** The provider behaviours of the live findings (mock_llm.py). */
    enum Behaviour {
        NORMAL, NO_FINISH_REASON, ERROR_BEFORE_TOKEN, TRANSIENT_ERROR_ONCE, ERROR_AFTER_TOKENS, LENGTH, CONTENT_FILTER, EMPTY, IDLE_AFTER_TOKEN
    }

    /** What a path did, normalised so the three can be compared. */
    record Outcome(boolean success, String text, String abnormalFinish, int attempts) {
    }

    private static Outcome expected(Behaviour behaviour) {
        return switch (behaviour) {
            case NORMAL -> new Outcome(true, TEXT, null, 1);
            case TRANSIENT_ERROR_ONCE -> new Outcome(true, TEXT, null, 2);
            case LENGTH -> new Outcome(true, TEXT, "length", 1);
            case CONTENT_FILTER -> new Outcome(true, TEXT, "content_filter", 1);
            case EMPTY -> new Outcome(true, "", null, 1);
            // retried with the default policy: three attempts, then the turn fails
            case ERROR_BEFORE_TOKEN -> new Outcome(false, null, null, 3);
            // failures after text: the turn fails (attempt counts differ by design,
            // a streamed path never re-sends text the client already has)
            case NO_FINISH_REASON, ERROR_AFTER_TOKENS, IDLE_AFTER_TOKEN -> new Outcome(false, null, null, -1);
        };
    }

    private static LlmConfiguration.Task task() {
        var task = new LlmConfiguration.Task();
        task.setId("parity");
        task.setType("openai");
        var retry = new RetryConfiguration();
        retry.setBackoffDelayMs(1L);
        retry.setJitterRatio(0.0);
        task.setRetry(retry);
        var streaming = new StreamingConfig();
        streaming.setIdleTimeoutSeconds(1);
        task.setStreaming(streaming);
        return task;
    }

    private static ChatResponse response(String text, FinishReason finishReason) {
        var builder = ChatResponse.builder().aiMessage(text.isEmpty() ? AiMessage.from("") : AiMessage.from(text));
        if (finishReason != null) {
            builder.metadata(ChatResponseMetadata.builder().finishReason(finishReason).build());
        }
        return builder.build();
    }

    private static StreamingChatModel streaming(Behaviour behaviour, AtomicInteger calls) {
        return new StreamingChatModel() {
            @Override
            public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
                int call = calls.incrementAndGet();
                switch (behaviour) {
                    case NORMAL -> {
                        handler.onPartialResponse("Hello ");
                        handler.onPartialResponse("world");
                        handler.onCompleteResponse(response(TEXT, FinishReason.STOP));
                    }
                    case NO_FINISH_REASON -> {
                        handler.onPartialResponse("Hello ");
                        handler.onCompleteResponse(response("Hello ", null));
                    }
                    case ERROR_BEFORE_TOKEN -> handler.onError(new InternalServerException("provider 500"));
                    case TRANSIENT_ERROR_ONCE -> {
                        if (call == 1) {
                            handler.onError(new InternalServerException("provider 500"));
                        } else {
                            handler.onPartialResponse(TEXT);
                            handler.onCompleteResponse(response(TEXT, FinishReason.STOP));
                        }
                    }
                    case ERROR_AFTER_TOKENS -> {
                        handler.onPartialResponse("Hello ");
                        handler.onError(new InternalServerException("mid-stream error frame"));
                    }
                    case LENGTH -> {
                        handler.onPartialResponse(TEXT);
                        handler.onCompleteResponse(response(TEXT, FinishReason.LENGTH));
                    }
                    case CONTENT_FILTER -> {
                        handler.onPartialResponse(TEXT);
                        handler.onCompleteResponse(response(TEXT, FinishReason.CONTENT_FILTER));
                    }
                    case EMPTY -> handler.onCompleteResponse(response("", FinishReason.STOP));
                    case IDLE_AFTER_TOKEN -> handler.onPartialResponse("Hello ");
                }
            }
        };
    }

    private static ChatModel synchronous(Behaviour behaviour, AtomicInteger calls) {
        return new ChatModel() {
            @Override
            public ChatResponse chat(ChatRequest request) {
                int call = calls.incrementAndGet();
                return switch (behaviour) {
                    case NORMAL -> response(TEXT, FinishReason.STOP);
                    case NO_FINISH_REASON -> throw new IllegalStateException("response body ended early", new IOException("premature EOF"));
                    case ERROR_BEFORE_TOKEN -> throw new InternalServerException("provider 500");
                    case TRANSIENT_ERROR_ONCE -> {
                        if (call == 1) {
                            throw new InternalServerException("provider 500");
                        }
                        yield response(TEXT, FinishReason.STOP);
                    }
                    case ERROR_AFTER_TOKENS -> throw new IllegalStateException("malformed body");
                    case LENGTH -> response(TEXT, FinishReason.LENGTH);
                    case CONTENT_FILTER -> response(TEXT, FinishReason.CONTENT_FILTER);
                    case EMPTY -> response("", FinishReason.STOP);
                    case IDLE_AFTER_TOKEN -> throw new RuntimeException("read timed out", new TimeoutException("read timeout"));
                };
            }
        };
    }

    private static List<ChatMessage> messages() {
        return List.of(UserMessage.from("hi"));
    }

    private static Outcome nonStreaming(Behaviour behaviour) {
        var calls = new AtomicInteger();
        try {
            var result = new LegacyChatExecutor().execute(synchronous(behaviour, calls), messages(), task());
            return success(result.response(), result.responseMetadata(), calls.get());
        } catch (LifecycleException e) {
            return new Outcome(false, null, null, calls.get());
        }
    }

    private static Outcome plainStreaming(Behaviour behaviour) {
        var calls = new AtomicInteger();
        try {
            var result = new StreamingLegacyChatExecutor().execute(streaming(behaviour, calls), messages(), mock(ConversationEventSink.class), task(),
                    JsonResponseFormatPolicy.DISABLED, null);
            return success(result.response(), result.metadata(), calls.get());
        } catch (LifecycleException e) {
            return new Outcome(false, null, null, calls.get());
        }
    }

    private static Outcome toolLoopStreaming(Behaviour behaviour) {
        var calls = new AtomicInteger();
        var task = task();
        var bridge = new ToolLoopStreamingChatModel(streaming(behaviour, calls), mock(ConversationEventSink.class),
                new StreamingLegacyChatExecutor().streamPolicy(task, null), "openai", ToolLoopStreamingChatModel.PATH_TOOL_LOOP);
        try {
            ChatResponse response = AgentExecutionHelper.executeWithRetry(() -> bridge.chat(ChatRequest.builder().messages(messages()).build()), task,
                    "Agent execution", new long[1], null);
            Map<String, Object> metadata = new HashMap<>();
            bridge.recordLastFinish(metadata);
            return success(response.aiMessage().text(), metadata, calls.get());
        } catch (LifecycleException e) {
            return new Outcome(false, null, null, calls.get());
        }
    }

    private static Outcome success(String text, Map<String, Object> metadata, int attempts) {
        return new Outcome(true, text != null ? text : "", LlmTask.abnormalFinishReason(metadata), attempts);
    }

    private static void assertSameOutcome(Outcome expected, Outcome actual, String path) {
        assertEquals(expected.success(), actual.success(), path + ": success");
        if (expected.success()) {
            assertEquals(expected.text(), actual.text(), path + ": stored text");
            assertEquals(expected.abnormalFinish(), actual.abnormalFinish(), path + ": finish reason");
        }
        if (expected.attempts() > 0) {
            assertEquals(expected.attempts(), actual.attempts(), path + ": provider calls");
        }
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Behaviour.class)
    @DisplayName("every path ends the same way")
    void sameOutcomeOnEveryPath(Behaviour behaviour) {
        Outcome expected = expected(behaviour);

        assertSameOutcome(expected, nonStreaming(behaviour), "non-streaming");
        assertSameOutcome(expected, plainStreaming(behaviour), "plain streaming");
        assertSameOutcome(expected, toolLoopStreaming(behaviour), "tool-loop streaming");
    }

    @ParameterizedTest(name = "{0}")
    @EnumSource(value = Behaviour.class, names = {"NO_FINISH_REASON", "ERROR_AFTER_TOKENS", "IDLE_AFTER_TOKEN"})
    @DisplayName("a streamed call that failed after text is never retried — the client already has that text")
    void streamedFailureAfterTextIsNotRetried(Behaviour behaviour) {
        assertEquals(1, plainStreaming(behaviour).attempts());
        assertEquals(1, toolLoopStreaming(behaviour).attempts());
    }
}
