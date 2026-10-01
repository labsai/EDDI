/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Nested;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Comprehensive tests for {@link ObservableChatModel} — the provider-agnostic
 * timeout + logging wrapper.
 */
class ObservableChatModelTest {

    private static final ChatResponse MOCK_RESPONSE = ChatResponse.builder().aiMessage(AiMessage.from("Hello!")).build();

    /**
     * A {@link ChatModel} mock the decorator can actually drive.
     * <p>
     * {@code chat} is stubbed because {@link ObservableChatModel#doChat} forwards
     * to {@code delegate.chat(...)} — see that class for why it must, rather than
     * to {@code doChat}. {@code defaultRequestParameters} has to return a real
     * object too: the decorator's own inherited {@code chat} merges it into every
     * request, and a Mockito mock returns {@code null} for it by default.
     */
    private static ChatModel providerLikeMock() {
        ChatModel model = mock(ChatModel.class);
        when(model.chat(any(ChatRequest.class))).thenReturn(MOCK_RESPONSE);
        when(model.defaultRequestParameters()).thenReturn(ChatRequestParameters.builder().build());
        when(model.listeners()).thenReturn(List.of());
        return model;
    }

    // --- wrap() factory method ---
    //
    // Previously a ten-test group here. Six asserted that a model configured with
    // no timeout and no logging comes back UNWRAPPED — the contract this change
    // deliberately reverses, because "unwrapped" meant "no place to attach a
    // listener", which is why the default single-model path had no telemetry at
    // all. The other four asserted the wrapping cases, which are now subsumed.
    //
    // Both directions are covered by ObservableChatModelDecoratorTest:
    // wrapAlwaysWraps and wrappingWithNothingConfiguredIsBehaviourPreserving.
    // That class also pins the properties this one never could, because it uses a
    // delegate shaped like a real provider binding rather than a mock that stubs
    // chat(): single listener dispatch, and delegate.chat() never re-entered.

    // --- chat() delegation ---

    @Nested
    class ChatDelegation {

        @Test
        void delegatesToUnderlyingModel() {
            ChatModel delegate = providerLikeMock();
            var request = ChatRequest.builder().messages(List.of(UserMessage.from("Hi"))).build();
            when(delegate.chat(any(ChatRequest.class))).thenReturn(MOCK_RESPONSE);

            ChatModel wrapped = ObservableChatModel.wrap(delegate, "openai", null, "true", "true", null);
            ChatResponse response = wrapped.chat(request);

            assertEquals("Hello!", response.aiMessage().text());
            verify(delegate).chat(any(ChatRequest.class));
        }

        @Test
        void withMultipleMessages_delegatesCorrectly() {
            ChatModel delegate = providerLikeMock();
            var request = ChatRequest.builder()
                    .messages(List.of(SystemMessage.from("You are helpful"), UserMessage.from("Hello"), UserMessage.from("How are you?"))).build();
            when(delegate.chat(any(ChatRequest.class))).thenReturn(MOCK_RESPONSE);

            ChatModel wrapped = ObservableChatModel.wrap(delegate, "anthropic", null, "true", "true", null);
            ChatResponse response = wrapped.chat(request);

            assertEquals("Hello!", response.aiMessage().text());
            verify(delegate).chat(any(ChatRequest.class));
        }

        @Test
        void delegateThrowsException_propagates() {
            ChatModel delegate = providerLikeMock();
            var request = ChatRequest.builder().messages(List.of(UserMessage.from("Hi"))).build();
            when(delegate.chat(any(ChatRequest.class))).thenThrow(new RuntimeException("API error"));

            ChatModel wrapped = ObservableChatModel.wrap(delegate, "openai", null, "true", "true", null);

            RuntimeException ex = assertThrows(RuntimeException.class, () -> wrapped.chat(request));
            assertEquals("API error", ex.getMessage());
        }

        @Test
        void loggingOnlyMode_doesNotAlterResponse() {
            ChatModel delegate = providerLikeMock();
            var request = ChatRequest.builder().messages(List.of(UserMessage.from("Hi"))).build();
            when(delegate.chat(any(ChatRequest.class))).thenReturn(MOCK_RESPONSE);

            // Only logging, no timeout
            ChatModel wrapped = ObservableChatModel.wrap(delegate, "ollama", null, "true", "true", null);
            ChatResponse response = wrapped.chat(request);

            assertSame(MOCK_RESPONSE, response, "Logging wrapper should not alter the response object");
        }
    }

    // --- Timeout behavior ---

    @Nested
    class TimeoutBehavior {

        @Test
        void withTimeout_delegatesSuccessfully() {
            ChatModel delegate = providerLikeMock();
            var request = ChatRequest.builder().messages(List.of(UserMessage.from("Hi"))).build();
            when(delegate.chat(any(ChatRequest.class))).thenReturn(MOCK_RESPONSE);

            // 10 second timeout — more than enough for a mock
            ChatModel wrapped = ObservableChatModel.wrap(delegate, "openai", "10000", null, null, null);
            ChatResponse response = wrapped.chat(request);

            assertEquals("Hello!", response.aiMessage().text());
            verify(delegate).chat(any(ChatRequest.class));
        }

        @Test
        void withTimeout_throwsOnSlow() {
            ChatModel delegate = providerLikeMock();
            var request = ChatRequest.builder().messages(List.of(UserMessage.from("Hi"))).build();
            when(delegate.chat(any(ChatRequest.class))).thenAnswer(inv -> {
                Thread.sleep(5000); // simulate slow model
                return MOCK_RESPONSE;
            });

            // Very short timeout
            ChatModel wrapped = ObservableChatModel.wrap(delegate, "openai", "50", null, null, null);

            RuntimeException ex = assertThrows(RuntimeException.class, () -> wrapped.chat(request));
            assertTrue(ex.getMessage().contains("timed out"), "Exception message should mention timeout");
            assertTrue(ex.getMessage().contains("openai"), "Exception message should mention model type");
        }

        @Test
        void withTimeout_delegateThrowsException_propagatesUnwrapped() {
            ChatModel delegate = providerLikeMock();
            var request = ChatRequest.builder().messages(List.of(UserMessage.from("Hi"))).build();
            when(delegate.chat(any(ChatRequest.class))).thenThrow(new IllegalArgumentException("Bad input"));

            ChatModel wrapped = ObservableChatModel.wrap(delegate, "openai", "10000", null, null, null);

            // Genuinely unwrapped now, as the method name always claimed: the provider's
            // own exception is rethrown rather than boxed in a RuntimeException. That
            // matters because LlmTelemetryListener tags eddi.llm.request.errors and the
            // span's error.type with the exception CLASS — boxing made every failure on
            // a timeout-configured agent read as a bare RuntimeException.
            var ex = assertThrows(IllegalArgumentException.class, () -> wrapped.chat(request));
            assertEquals("Bad input", ex.getMessage());
        }

        /**
         * {@code chatWithTimeout} abandons its worker: {@code future.cancel(true)}
         * interrupts the thread, but an interrupt cannot abort a socket read, so the
         * worker lives on until the provider answers. On the previous
         * {@code newCachedThreadPool} that leaked one <em>platform</em> thread per
         * timeout with no ceiling. Asserting the worker is a virtual thread is what
         * pins the fix: it fails the moment the executor goes back to platform threads.
         */
        @Test
        void timedOutCallDoesNotLeakAPlatformThread() throws Exception {
            var workerStarted = new CountDownLatch(1);
            var releaseWorker = new CountDownLatch(1);
            var workerWasVirtual = new AtomicBoolean();

            ChatModel delegate = providerLikeMock();
            var request = ChatRequest.builder().messages(List.of(UserMessage.from("Hi"))).build();
            when(delegate.chat(any(ChatRequest.class))).thenAnswer(inv -> {
                workerWasVirtual.set(Thread.currentThread().isVirtual());
                workerStarted.countDown();
                releaseWorker.await(); // the provider that has not answered yet
                return MOCK_RESPONSE;
            });

            ChatModel wrapped = ObservableChatModel.wrap(delegate, "openai", "50", null, null, null);
            try {
                assertThrows(RuntimeException.class, () -> wrapped.chat(request));

                assertTrue(workerStarted.await(5, TimeUnit.SECONDS),
                        "the provider call must actually have been dispatched to the executor");
                assertTrue(workerWasVirtual.get(),
                        "the abandoned worker must be a virtual thread — a platform-thread pool leaks one OS "
                                + "thread per timeout, unbounded, for as long as the provider stays silent");
            } finally {
                releaseWorker.countDown();
            }
        }

        @Test
        void withTimeoutAndLogging_combinedBehavior() {
            ChatModel delegate = providerLikeMock();
            var request = ChatRequest.builder().messages(List.of(UserMessage.from("Hi"))).build();
            when(delegate.chat(any(ChatRequest.class))).thenReturn(MOCK_RESPONSE);

            // both timeout and logging
            ChatModel wrapped = ObservableChatModel.wrap(delegate, "vertex-ai", "10000", "true", "true", null);
            ChatResponse response = wrapped.chat(request);

            assertEquals("Hello!", response.aiMessage().text());
            verify(delegate).chat(any(ChatRequest.class));
        }
    }

    // --- Multiple invocations ---

    @Nested
    class MultipleInvocations {

        @Test
        void multipleCallsToSameWrapper_allDelegateCorrectly() {
            ChatModel delegate = providerLikeMock();
            when(delegate.chat(any(ChatRequest.class))).thenReturn(MOCK_RESPONSE);
            when(delegate.defaultRequestParameters()).thenReturn(ChatRequestParameters.builder().build());

            ChatModel wrapped = ObservableChatModel.wrap(delegate, "openai", null, "true", "true", null);

            for (int i = 0; i < 5; i++) {
                var request = ChatRequest.builder().messages(List.of(UserMessage.from("Message " + i))).build();
                ChatResponse response = wrapped.chat(request);
                assertEquals("Hello!", response.aiMessage().text());
            }

            verify(delegate, times(5)).chat(any(ChatRequest.class));
        }
    }
}
