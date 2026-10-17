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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

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

    // --- request logging (L12 of the 2026-10-15 security review) ---

    @Nested
    class RequestLogging {

        private static final String SECRET = "sk-abcdefghijklmnopqrstuvwxyz0123456789";

        private List<LogRecord> captureLogs(Runnable action, Level level) {
            List<LogRecord> records = new ArrayList<>();
            var handler = new Handler() {
                @Override
                public void publish(LogRecord record) {
                    records.add(record);
                }

                @Override
                public void flush() {
                    // nothing is buffered
                }

                @Override
                public void close() {
                    // nothing to release
                }
            };
            var logger = Logger.getLogger(ObservableChatModel.class.getName());
            var previousLevel = logger.getLevel();
            boolean previousUseParentHandlers = logger.getUseParentHandlers();
            logger.setLevel(level);
            logger.setUseParentHandlers(false);
            logger.addHandler(handler);
            try {
                action.run();
            } finally {
                logger.removeHandler(handler);
                logger.setLevel(previousLevel);
                logger.setUseParentHandlers(previousUseParentHandlers);
            }
            return records;
        }

        private static String rendered(LogRecord record) {
            return record.getParameters() == null ? record.getMessage() : String.format(record.getMessage(), record.getParameters());
        }

        @Test
        void requestLineIsDebugAndRedacted() {
            ChatModel delegate = providerLikeMock();
            var request = ChatRequest.builder().messages(List.of(UserMessage.from("my api_key=" + SECRET + " please"))).build();
            ChatModel wrapped = ObservableChatModel.wrap(delegate, "openai", null, "true", "false", null);

            List<LogRecord> records = captureLogs(() -> wrapped.chat(request), Level.ALL);

            var requestLines = records.stream().filter(r -> rendered(r).contains("Chat request")).toList();
            assertEquals(1, requestLines.size(), "one request line: " + records);
            assertEquals(Level.FINE, requestLines.get(0).getLevel(), "the request line is DEBUG, not INFO");
            assertFalse(rendered(requestLines.get(0)).contains(SECRET), "the credential must be redacted: " + rendered(requestLines.get(0)));
        }

        /**
         * The filter recognises a key only when it sees it whole: truncating first left
         * up to 19 characters of a key straddling the cutoff in the log.
         */
        @Test
        void credentialStraddlingTheRequestCutoffIsRedacted() {
            ChatModel delegate = providerLikeMock();
            // The logged form is UserMessage.toString(), 61 characters of wrapper plus the
            // text, so 15 of the key sit before the 200-character
            // cutoff — more than the 8 the assertion checks for, fewer than the filter
            // needs to recognise the key, so truncating first would leave them in the log.
            // No
            // credential-named key precedes it: the name-bound rule would mask the value
            // whatever its shape and hide exactly the cut this test is about.
            String straddling = "x".repeat(124) + " " + SECRET;
            String unredactedPrefix = UserMessage.from(straddling).toString().substring(0, 200);
            assertTrue(unredactedPrefix.contains(SECRET.substring(0, 8)) && !unredactedPrefix.contains(SECRET),
                    "the fixture must put the cutoff inside the key: " + unredactedPrefix);
            var request = ChatRequest.builder().messages(List.of(UserMessage.from(straddling))).build();
            ChatModel wrapped = ObservableChatModel.wrap(delegate, "openai", null, "true", "false", null);

            List<LogRecord> records = captureLogs(() -> wrapped.chat(request), Level.ALL);

            String line = rendered(records.stream().filter(r -> rendered(r).contains("Chat request")).findFirst().orElseThrow());
            assertFalse(line.contains(SECRET.substring(0, 8)), "no prefix of the key may survive the cutoff: " + line);
        }

        @Test
        void credentialStraddlingTheResponseCutoffIsRedacted() {
            ChatModel delegate = providerLikeMock();
            // 485 characters of prefix: 15 of the key sit before the 500-character cutoff.
            String response = "y".repeat(484) + " " + SECRET;
            String unredactedPrefix = response.substring(0, 500);
            assertTrue(unredactedPrefix.contains(SECRET.substring(0, 8)) && !unredactedPrefix.contains(SECRET),
                    "the fixture must put the cutoff inside the key: " + unredactedPrefix);
            when(delegate.chat(any(ChatRequest.class)))
                    .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from(response)).build());
            var request = ChatRequest.builder().messages(List.of(UserMessage.from("hello"))).build();
            ChatModel wrapped = ObservableChatModel.wrap(delegate, "openai", null, "false", "true", null);

            List<LogRecord> records = captureLogs(() -> wrapped.chat(request), Level.ALL);

            String line = rendered(records.stream().filter(r -> rendered(r).contains("Chat response")).findFirst().orElseThrow());
            assertFalse(line.contains(SECRET.substring(0, 8)), "no prefix of the key may survive the cutoff: " + line);
        }

        @Test
        void requestLineIsNotWrittenAtInfo() {
            ChatModel delegate = providerLikeMock();
            var request = ChatRequest.builder().messages(List.of(UserMessage.from("hello"))).build();
            ChatModel wrapped = ObservableChatModel.wrap(delegate, "openai", null, "true", "false", null);

            List<LogRecord> records = captureLogs(() -> wrapped.chat(request), Level.INFO);

            assertTrue(records.stream().noneMatch(r -> rendered(r).contains("Chat request")), "nothing at INFO: " + records);
        }

        @Test
        void responseLineIsRedacted() {
            ChatModel delegate = providerLikeMock();
            when(delegate.chat(any(ChatRequest.class)))
                    .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("your token=" + SECRET)).build());
            var request = ChatRequest.builder().messages(List.of(UserMessage.from("hello"))).build();
            ChatModel wrapped = ObservableChatModel.wrap(delegate, "openai", null, "false", "true", null);

            List<LogRecord> records = captureLogs(() -> wrapped.chat(request), Level.ALL);

            var responseLines = records.stream().filter(r -> rendered(r).contains("Chat response")).toList();
            assertEquals(1, responseLines.size(), "one response line: " + records);
            assertFalse(rendered(responseLines.get(0)).contains(SECRET), rendered(responseLines.get(0)));
        }
    }

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
