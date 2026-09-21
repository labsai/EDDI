/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
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

/**
 * Tests for {@link ObservableStreamingChatModel} — the decorator that makes
 * {@code logRequests}/{@code logResponses} take effect on the streaming path.
 * The decorator must stay fully transparent: every callback the provider fires
 * has to reach the caller's handler unchanged.
 */
class ObservableStreamingChatModelTest {

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
    @DisplayName("wrapIfNeeded returns the raw model when neither logging flag is set")
    void wrapIfNeeded_noFlags_returnsRaw() {
        StreamingChatModel raw = emitting("a");
        assertSame(raw, ObservableStreamingChatModel.wrapIfNeeded(raw, "openai", null, null));
        assertSame(raw, ObservableStreamingChatModel.wrapIfNeeded(raw, "openai", "false", "false"));
        assertSame(raw, ObservableStreamingChatModel.wrapIfNeeded(raw, "openai", "not-a-boolean", ""));
    }

    @Test
    @DisplayName("wrapIfNeeded wraps when either logging flag is set")
    void wrapIfNeeded_eitherFlag_wraps() {
        StreamingChatModel raw = emitting("a");
        assertInstanceOf(ObservableStreamingChatModel.class, ObservableStreamingChatModel.wrapIfNeeded(raw, "openai", "true", null));
        assertInstanceOf(ObservableStreamingChatModel.class, ObservableStreamingChatModel.wrapIfNeeded(raw, "openai", null, "true"));
    }

    @Test
    @DisplayName("every token and the complete response reach the caller's handler when logging responses")
    void wrapped_forwardsAllCallbacks() {
        var wrapped = ObservableStreamingChatModel.wrapIfNeeded(emitting("Hello", " ", "world"), "openai", "true", "true");

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
        var wrapped = ObservableStreamingChatModel.wrapIfNeeded(emitting("x", "y"), "openai", "true", "false");

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
        var wrapped = ObservableStreamingChatModel.wrapIfNeeded(failing, "openai", "false", "true");

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
