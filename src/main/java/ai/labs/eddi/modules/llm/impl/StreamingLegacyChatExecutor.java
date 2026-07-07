/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.configs.shared.RetryConfiguration;
import ai.labs.eddi.engine.lifecycle.ConversationEventSink;
import ai.labs.eddi.modules.llm.model.LlmConfiguration;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Executes streaming chat completion — tokens are emitted in real-time via the
 * {@link ConversationEventSink}.
 * <p>
 * This class blocks until the full response is received (via CountDownLatch) so
 * that the lifecycle workflow can proceed synchronously while tokens stream to
 * the client.
 */
class StreamingLegacyChatExecutor {
    private static final Logger LOGGER = Logger.getLogger(StreamingLegacyChatExecutor.class);
    private static final long DEFAULT_TIMEOUT_SECONDS = 120;

    /**
     * Result of a streaming chat execution, including response text and metadata.
     *
     * @param response
     *            the full accumulated response text
     * @param metadata
     *            metadata about the streaming execution (finishReason, warnings)
     */
    record StreamingResult(String response, Map<String, Object> metadata) {
    }

    /**
     * Execute a streaming chat completion, emitting tokens via the event sink.
     * <p>
     * Backward-compatible wrapper that delegates to the task-aware overload with a
     * null task (uses default timeout, no retry).
     *
     * @param streamingModel
     *            the streaming-capable chat model
     * @param messages
     *            the full message list (system + history + user)
     * @param eventSink
     *            the sink to emit token events to
     * @return the full accumulated response text (for memory storage)
     */
    String execute(StreamingChatModel streamingModel, List<ChatMessage> messages, ConversationEventSink eventSink) {
        return execute(streamingModel, messages, eventSink, null).response();
    }

    /**
     * Execute a streaming chat completion with configurable timeout, retry on total
     * failure, and finishReason metadata capture.
     *
     * @param streamingModel
     *            the streaming-capable chat model
     * @param messages
     *            the full message list (system + history + user)
     * @param eventSink
     *            the sink to emit token events to
     * @param task
     *            task configuration (for timeout and retry settings, may be null)
     * @return a {@link StreamingResult} with the response text and metadata
     */
    StreamingResult execute(StreamingChatModel streamingModel, List<ChatMessage> messages,
                            ConversationEventSink eventSink, LlmConfiguration.Task task) {

        LOGGER.debug("Executing with streaming (legacy mode)");

        long timeoutSeconds = DEFAULT_TIMEOUT_SECONDS;
        if (task != null && task.getStreamingTimeoutSeconds() != null && task.getStreamingTimeoutSeconds() > 0) {
            timeoutSeconds = task.getStreamingTimeoutSeconds();
        }

        RetryConfiguration retryConfig = task != null ? task.getRetry() : null;
        int maxAttempts = retryConfig != null && retryConfig.getMaxAttempts() != null ? retryConfig.getMaxAttempts() : 1;

        Map<String, Object> metadata = new HashMap<>();
        String responseText = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            var latch = new CountDownLatch(1);
            var fullResponse = new StringBuilder();
            var errorRef = new AtomicReference<Throwable>();
            var finishReasonRef = new AtomicReference<String>();

            var chatRequest = ChatRequest.builder().messages(messages).build();

            streamingModel.chat(chatRequest, new StreamingChatResponseHandler() {
                @Override
                public void onPartialResponse(String partialResponse) {
                    fullResponse.append(partialResponse);
                    try {
                        eventSink.onToken(partialResponse);
                    } catch (Exception e) {
                        LOGGER.warnf("Error sending token event: %s", e.getMessage());
                    }
                }

                @Override
                public void onCompleteResponse(ChatResponse completeResponse) {
                    if (completeResponse != null && completeResponse.metadata() != null
                            && completeResponse.metadata().finishReason() != null) {
                        finishReasonRef.set(completeResponse.metadata().finishReason().toString());
                    }
                    latch.countDown();
                }

                @Override
                public void onError(Throwable error) {
                    errorRef.set(error);
                    latch.countDown();
                }
            });

            boolean timedOut = false;
            try {
                if (!latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
                    LOGGER.warn("Streaming chat timed out");
                    timedOut = true;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                LOGGER.warn("Streaming chat was interrupted");
            }

            responseText = fullResponse.toString();

            if (finishReasonRef.get() != null) {
                metadata.put("finishReason", finishReasonRef.get());
            }

            if (timedOut) {
                metadata.put("streamingTimeout", true);
                if (!responseText.isEmpty()) {
                    // Partial response available — return it with a warning
                    metadata.put("warning", "streaming_timeout_partial");
                    LOGGER.warnf("Streaming timed out after %ds with partial response (%d chars)", timeoutSeconds, responseText.length());
                    return new StreamingResult(responseText, metadata);
                }
                // No response at all — treat as retryable failure
                if (attempt < maxAttempts) {
                    LOGGER.warnf("Streaming timed out with empty response, retrying (attempt %d/%d)", attempt, maxAttempts);
                    RetryConfiguration.backoff(attempt, retryConfig);
                    continue;
                }
                LOGGER.errorf("Streaming timed out with empty response after %d attempts", maxAttempts);
                return new StreamingResult("", metadata);
            }

            if (errorRef.get() != null) {
                if (!responseText.isEmpty()) {
                    // Error fired but we have partial content — return it with warning
                    metadata.put("warning", "streaming_error_partial");
                    metadata.put("errorMessage", errorRef.get().getMessage());
                    LOGGER.warnf("Streaming error with partial response (%d chars): %s", responseText.length(), errorRef.get().getMessage());
                    return new StreamingResult(responseText, metadata);
                }
                // Total failure with no content — retry if possible
                if (attempt < maxAttempts) {
                    LOGGER.warnf("Streaming error with empty response, retrying (attempt %d/%d): %s",
                            attempt, maxAttempts, errorRef.get().getMessage());
                    RetryConfiguration.backoff(attempt, retryConfig);
                    continue;
                }
                LOGGER.errorf("Streaming chat error after %d attempts: %s", maxAttempts, errorRef.get().getMessage());
                throw new RuntimeException("Streaming chat failed", errorRef.get());
            }

            // Success — break out of retry loop
            break;
        }

        return new StreamingResult(responseText, metadata);
    }
}
