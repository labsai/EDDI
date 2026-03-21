package ai.labs.eddi.modules.llm.impl;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Comprehensive tests for {@link ObservableChatModel} — the provider-agnostic
 * timeout + logging wrapper.
 */
class ObservableChatModelTest {

    private static final ChatResponse MOCK_RESPONSE = ChatResponse.builder()
            .aiMessage(AiMessage.from("Hello!"))
            .build();

    // --- wrapIfNeeded() factory method ---

    @Nested
    class WrapIfNeeded {

        @Test
        void noObservabilityParams_returnsOriginalModel() {
            ChatModel original = mock(ChatModel.class);

            ChatModel result = ObservableChatModel.wrapIfNeeded(original, "openai", null, null, null);

            assertSame(original, result, "Should return unwrapped model when no observability params set");
        }

        @Test
        void emptyStringParams_returnsOriginalModel() {
            ChatModel original = mock(ChatModel.class);

            ChatModel result = ObservableChatModel.wrapIfNeeded(original, "openai", "", "", "");

            assertSame(original, result, "Empty strings should be treated as no params");
        }

        @Test
        void blankTimeout_returnsOriginalModel() {
            ChatModel original = mock(ChatModel.class);

            ChatModel result = ObservableChatModel.wrapIfNeeded(original, "openai", "   ", null, null);

            assertSame(original, result, "Blank timeout should be ignored");
        }

        @Test
        void invalidTimeout_returnsOriginalModel() {
            ChatModel original = mock(ChatModel.class);

            ChatModel result = ObservableChatModel.wrapIfNeeded(original, "openai", "notanumber", null, null);

            assertSame(original, result, "Invalid timeout should be ignored, no wrapping");
        }

        @Test
        void withTimeout_returnsWrappedModel() {
            ChatModel original = mock(ChatModel.class);

            ChatModel result = ObservableChatModel.wrapIfNeeded(original, "openai", "5000", null, null);

            assertNotSame(original, result);
            assertInstanceOf(ObservableChatModel.class, result);
        }

        @Test
        void withLogRequestsOnly_returnsWrappedModel() {
            ChatModel original = mock(ChatModel.class);

            ChatModel result = ObservableChatModel.wrapIfNeeded(original, "openai", null, "true", null);

            assertInstanceOf(ObservableChatModel.class, result);
        }

        @Test
        void withLogResponsesOnly_returnsWrappedModel() {
            ChatModel original = mock(ChatModel.class);

            ChatModel result = ObservableChatModel.wrapIfNeeded(original, "openai", null, null, "true");

            assertInstanceOf(ObservableChatModel.class, result);
        }

        @Test
        void withAllObservabilityParams_returnsWrappedModel() {
            ChatModel original = mock(ChatModel.class);

            ChatModel result = ObservableChatModel.wrapIfNeeded(original, "openai", "5000", "true", "true");

            assertInstanceOf(ObservableChatModel.class, result);
        }

        @Test
        void logRequestsFalse_noWrapping() {
            ChatModel original = mock(ChatModel.class);

            ChatModel result = ObservableChatModel.wrapIfNeeded(original, "openai", null, "false", "false");

            assertSame(original, result, "logRequests=false and logResponses=false should not wrap");
        }

        @Test
        void zeroTimeout_noWrapping() {
            ChatModel original = mock(ChatModel.class);

            ChatModel result = ObservableChatModel.wrapIfNeeded(original, "openai", "0", null, null);

            // zero-duration timeout is treated as "no timeout"
            assertSame(original, result, "Zero timeout should not wrap (Duration.isZero() check)");
        }
    }

    // --- chat() delegation ---

    @Nested
    class ChatDelegation {

        @Test
        void delegatesToUnderlyingModel() {
            ChatModel delegate = mock(ChatModel.class);
            var request = ChatRequest.builder()
                    .messages(List.of(UserMessage.from("Hi")))
                    .build();
            when(delegate.chat(request)).thenReturn(MOCK_RESPONSE);

            ChatModel wrapped = ObservableChatModel.wrapIfNeeded(delegate, "openai", null, "true", "true");
            ChatResponse response = wrapped.chat(request);

            assertEquals("Hello!", response.aiMessage().text());
            verify(delegate).chat(request);
        }

        @Test
        void withMultipleMessages_delegatesCorrectly() {
            ChatModel delegate = mock(ChatModel.class);
            var request = ChatRequest.builder()
                    .messages(List.of(
                            SystemMessage.from("You are helpful"),
                            UserMessage.from("Hello"),
                            UserMessage.from("How are you?")
                    ))
                    .build();
            when(delegate.chat(request)).thenReturn(MOCK_RESPONSE);

            ChatModel wrapped = ObservableChatModel.wrapIfNeeded(delegate, "anthropic", null, "true", "true");
            ChatResponse response = wrapped.chat(request);

            assertEquals("Hello!", response.aiMessage().text());
            verify(delegate).chat(request);
        }

        @Test
        void delegateThrowsException_propagates() {
            ChatModel delegate = mock(ChatModel.class);
            var request = ChatRequest.builder()
                    .messages(List.of(UserMessage.from("Hi")))
                    .build();
            when(delegate.chat(request)).thenThrow(new RuntimeException("API error"));

            ChatModel wrapped = ObservableChatModel.wrapIfNeeded(delegate, "openai", null, "true", "true");

            RuntimeException ex = assertThrows(RuntimeException.class, () -> wrapped.chat(request));
            assertEquals("API error", ex.getMessage());
        }

        @Test
        void loggingOnlyMode_doesNotAlterResponse() {
            ChatModel delegate = mock(ChatModel.class);
            var request = ChatRequest.builder()
                    .messages(List.of(UserMessage.from("Hi")))
                    .build();
            when(delegate.chat(request)).thenReturn(MOCK_RESPONSE);

            // Only logging, no timeout
            ChatModel wrapped = ObservableChatModel.wrapIfNeeded(delegate, "ollama", null, "true", "true");
            ChatResponse response = wrapped.chat(request);

            assertSame(MOCK_RESPONSE, response, "Logging wrapper should not alter the response object");
        }
    }

    // --- Timeout behavior ---

    @Nested
    class TimeoutBehavior {

        @Test
        void withTimeout_delegatesSuccessfully() {
            ChatModel delegate = mock(ChatModel.class);
            var request = ChatRequest.builder()
                    .messages(List.of(UserMessage.from("Hi")))
                    .build();
            when(delegate.chat(request)).thenReturn(MOCK_RESPONSE);

            // 10 second timeout — more than enough for a mock
            ChatModel wrapped = ObservableChatModel.wrapIfNeeded(delegate, "openai", "10000", null, null);
            ChatResponse response = wrapped.chat(request);

            assertEquals("Hello!", response.aiMessage().text());
            verify(delegate).chat(request);
        }

        @Test
        void withTimeout_throwsOnSlow() {
            ChatModel delegate = mock(ChatModel.class);
            var request = ChatRequest.builder()
                    .messages(List.of(UserMessage.from("Hi")))
                    .build();
            when(delegate.chat(request)).thenAnswer(inv -> {
                Thread.sleep(5000); // simulate slow model
                return MOCK_RESPONSE;
            });

            // Very short timeout
            ChatModel wrapped = ObservableChatModel.wrapIfNeeded(delegate, "openai", "50", null, null);

            RuntimeException ex = assertThrows(RuntimeException.class, () -> wrapped.chat(request));
            assertTrue(ex.getMessage().contains("timed out"), "Exception message should mention timeout");
            assertTrue(ex.getMessage().contains("openai"), "Exception message should mention model type");
        }

        @Test
        void withTimeout_delegateThrowsException_propagatesUnwrapped() {
            ChatModel delegate = mock(ChatModel.class);
            var request = ChatRequest.builder()
                    .messages(List.of(UserMessage.from("Hi")))
                    .build();
            when(delegate.chat(request)).thenThrow(new IllegalArgumentException("Bad input"));

            ChatModel wrapped = ObservableChatModel.wrapIfNeeded(delegate, "openai", "10000", null, null);

            RuntimeException ex = assertThrows(RuntimeException.class, () -> wrapped.chat(request));
            // The original exception should be the cause (unwrapped from ExecutionException)
            assertInstanceOf(IllegalArgumentException.class, ex.getCause());
            assertEquals("Bad input", ex.getCause().getMessage());
        }

        @Test
        void withTimeoutAndLogging_combinedBehavior() {
            ChatModel delegate = mock(ChatModel.class);
            var request = ChatRequest.builder()
                    .messages(List.of(UserMessage.from("Hi")))
                    .build();
            when(delegate.chat(request)).thenReturn(MOCK_RESPONSE);

            // Both timeout and logging
            ChatModel wrapped = ObservableChatModel.wrapIfNeeded(delegate, "vertex-ai", "10000", "true", "true");
            ChatResponse response = wrapped.chat(request);

            assertEquals("Hello!", response.aiMessage().text());
            verify(delegate).chat(request);
        }
    }

    // --- Multiple invocations ---

    @Nested
    class MultipleInvocations {

        @Test
        void multipleCallsToSameWrapper_allDelegateCorrectly() {
            ChatModel delegate = mock(ChatModel.class);
            when(delegate.chat(any(ChatRequest.class))).thenReturn(MOCK_RESPONSE);

            ChatModel wrapped = ObservableChatModel.wrapIfNeeded(delegate, "openai", null, "true", "true");

            for (int i = 0; i < 5; i++) {
                var request = ChatRequest.builder()
                        .messages(List.of(UserMessage.from("Message " + i)))
                        .build();
                ChatResponse response = wrapped.chat(request);
                assertEquals("Hello!", response.aiMessage().text());
            }

            verify(delegate, times(5)).chat(any(ChatRequest.class));
        }
    }
}
