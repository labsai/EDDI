/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ObservableStreamingChatModel} — the decorator that makes
 * {@code logRequests}/{@code logResponses} take effect on the streaming path.
 * The decorator must stay fully transparent: every callback the provider fires
 * has to reach the caller's handler unchanged.
 */
class ObservableStreamingChatModelTest {

    /** Records which listener callbacks fired, in order. */
    private static class RecordingListener implements ChatModelListener {
        final List<String> events = new ArrayList<>();

        @Override
        public void onRequest(ChatModelRequestContext context) {
            events.add("request");
        }

        @Override
        public void onResponse(ChatModelResponseContext context) {
            events.add("response");
        }

        @Override
        public void onError(ChatModelErrorContext context) {
            events.add("error");
        }
    }

    /**
     * The check that was missing, and whose absence let streaming telemetry ship as
     * dead code.
     * <p>
     * {@code StreamingChatModel.chat(ChatRequest, ChatRequestOptions, handler)} is
     * the overload that reads {@code listeners()} and dispatches. An earlier
     * revision of the decorator overrode <em>both</em> {@code chat} overloads, so
     * that default never ran and EDDI's listener never fired on any streaming turn
     * — while the class javadoc claimed it did. Every test here passed, because
     * they all passed {@code null} for the listener and asserted only on tokens.
     */
    @Test
    @DisplayName("EDDI's listener fires on a streaming turn")
    void telemetryListenerFiresOnStreamingTurn() {
        var listener = new RecordingListener();
        var wrapped = ObservableStreamingChatModel.wrap(emitting("a", "b"), "openai", null, null, listener);

        wrapped.chat(ChatRequest.builder().messages(UserMessage.from("hi")).build(),
                new StreamingChatResponseHandler() {
                    @Override
                    public void onPartialResponse(String partialResponse) {
                    }

                    @Override
                    public void onCompleteResponse(ChatResponse completeResponse) {
                    }

                    @Override
                    public void onError(Throwable error) {
                    }
                });

        assertEquals(List.of("request", "response"), listener.events,
                "the decorator must leave the listener-dispatching chat() default in place, or streaming"
                        + " turns produce no span and no meters");
    }

    @Test
    @DisplayName("EDDI's listener sees a streaming failure")
    void telemetryListenerSeesStreamingFailure() {
        var listener = new RecordingListener();
        StreamingChatModel failing = new StreamingChatModel() {
            @Override
            public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                handler.onError(new IllegalStateException("stream broke"));
            }
        };
        var wrapped = ObservableStreamingChatModel.wrap(failing, "openai", null, null, listener);

        var seen = new AtomicReference<Throwable>();
        wrapped.chat(ChatRequest.builder().messages(UserMessage.from("hi")).build(),
                new StreamingChatResponseHandler() {
                    @Override
                    public void onPartialResponse(String partialResponse) {
                    }

                    @Override
                    public void onCompleteResponse(ChatResponse completeResponse) {
                    }

                    @Override
                    public void onError(Throwable error) {
                        seen.set(error);
                    }
                });

        assertEquals(List.of("request", "error"), listener.events);
        assertTrue(seen.get() instanceof IllegalStateException, "the caller still sees the real failure");
    }

    private static StreamingChatModel emitting(String... tokens) {
        return new StreamingChatModel() {
            @Override
            public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                for (String token : tokens) {
                    handler.onPartialResponse(token);
                }
                handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from(String.join("", tokens))).build());
            }
        };
    }

    private static ChatRequest request() {
        return ChatRequest.builder().messages(UserMessage.from("hi")).build();
    }

    @Test
    @DisplayName("wrap always wraps, so there is always somewhere to attach telemetry")
    void wrap_noFlags_stillWraps() {
        StreamingChatModel raw = emitting("a");
        // Previously these came back unwrapped. That is what left the default
        // streaming path with no listener and therefore no telemetry.
        assertInstanceOf(ObservableStreamingChatModel.class, ObservableStreamingChatModel.wrap(raw, "openai", null, null, null));
        assertInstanceOf(ObservableStreamingChatModel.class,
                ObservableStreamingChatModel.wrap(raw, "openai", "false", "false", null));
        assertInstanceOf(ObservableStreamingChatModel.class,
                ObservableStreamingChatModel.wrap(raw, "openai", "not-a-boolean", "", null));
    }

    @Test
    @DisplayName("wrap wraps when either logging flag is set")
    void wrap_eitherFlag_wraps() {
        StreamingChatModel raw = emitting("a");
        assertInstanceOf(ObservableStreamingChatModel.class, ObservableStreamingChatModel.wrap(raw, "openai", "true", null, null));
        assertInstanceOf(ObservableStreamingChatModel.class, ObservableStreamingChatModel.wrap(raw, "openai", null, "true", null));
    }

    @Test
    @DisplayName("every token and the complete response reach the caller's handler when logging responses")
    void wrapped_forwardsAllCallbacks() {
        var wrapped = ObservableStreamingChatModel.wrap(emitting("Hello", " ", "world"), "openai", "true", "true", null);

        List<String> tokens = new ArrayList<>();
        var complete = new AtomicReference<ChatResponse>();
        wrapped.chat(request(), new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                tokens.add(partialResponse);
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                complete.set(completeResponse);
            }

            @Override
            public void onError(Throwable error) {
                throw new IllegalStateException("unexpected", error);
            }
        });

        assertEquals(List.of("Hello", " ", "world"), tokens, "The decorator must not swallow or reorder tokens");
        assertNotNull(complete.get());
        assertEquals("Hello world", complete.get().aiMessage().text());
    }

    @Test
    @DisplayName("tokens still reach the handler when only requests are logged (no response accumulation)")
    void wrapped_logRequestsOnly_forwardsTokens() {
        var wrapped = ObservableStreamingChatModel.wrap(emitting("x", "y"), "openai", "true", "false", null);

        List<String> tokens = new ArrayList<>();
        wrapped.chat(request(), new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                tokens.add(partialResponse);
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
            }

            @Override
            public void onError(Throwable error) {
            }
        });

        assertEquals(List.of("x", "y"), tokens);
    }

    @Test
    @DisplayName("an error reaches the caller's handler unchanged")
    void wrapped_forwardsError() {
        StreamingChatModel failing = new StreamingChatModel() {
            @Override
            public void chat(ChatRequest chatRequest, StreamingChatResponseHandler handler) {
                handler.onPartialResponse("partial");
                handler.onError(new IllegalStateException("boom"));
            }
        };
        var wrapped = ObservableStreamingChatModel.wrap(failing, "openai", "false", "true", null);

        var seen = new AtomicReference<Throwable>();
        wrapped.chat(request(), new StreamingChatResponseHandler() {
            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
            }

            @Override
            public void onError(Throwable error) {
                seen.set(error);
            }
        });

        assertNotNull(seen.get());
        assertEquals("boom", seen.get().getMessage());
    }
}
