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
     * Result of a streaming execution — the full text plus response metadata
     * (finish reason, token usage) captured from the final response.
     */
    record StreamResult(String response, Map<String, Object> responseMetadata) {
    }

    /**
     * Execute a streaming chat completion, emitting tokens via the event sink and
     * capturing the final response metadata (token usage). Used by the cascade to
     * stream the final step live without losing cost/token evidence.
     * <p>
     * Unlike
     * {@link #execute(StreamingChatModel, List, ConversationEventSink, LlmConfiguration.Task)},
     * a mid-stream error is <em>always</em> propagated here, even when tokens were
     * already emitted: the cascade must see the failure to fall back to the best
     * previous step. Salvaging the partial text would let a failed final step be
     * accepted as a successful one.
     */
    StreamResult executeCapturing(StreamingChatModel streamingModel, List<ChatMessage> messages, ConversationEventSink eventSink) {
        var result = execute(streamingModel, messages, eventSink, null, false);
        return new StreamResult(result.response(), result.metadata());
    }

    /**
     * Execute a streaming chat completion with configurable timeout, retry on total
     * failure, and response metadata capture (finish reason and token usage).
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
        return execute(streamingModel, messages, eventSink, task, true);
    }

    /**
     * Core streaming execution.
     *
     * @param salvagePartialOnError
     *            when {@code true}, a mid-stream error that already produced tokens
     *            returns the partial text with a {@code streaming_error_partial}
     *            warning instead of throwing — the turn keeps whatever the model
     *            managed to produce. When {@code false}, any error is propagated so
     *            the caller can treat the step as failed.
     */
    private StreamingResult execute(StreamingChatModel streamingModel, List<ChatMessage> messages,
                                    ConversationEventSink eventSink, LlmConfiguration.Task task, boolean salvagePartialOnError) {

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
            var responseRef = new AtomicReference<ChatResponse>();

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
                    responseRef.set(completeResponse);
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
            metadata.putAll(buildMetadata(responseRef.get()));

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
                if (salvagePartialOnError && !responseText.isEmpty()) {
                    // Error fired but we have partial content — return it with warning
                    metadata.put("warning", "streaming_error_partial");
                    metadata.put("errorMessage", errorRef.get().getMessage());
                    LOGGER.warnf("Streaming error with partial response (%d chars): %s", responseText.length(), errorRef.get().getMessage());
                    return new StreamingResult(responseText, metadata);
                }
                // Nothing salvageable (no content, or the caller wants errors
                // propagated) — retry if possible
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

    private static Map<String, Object> buildMetadata(ChatResponse response) {
        Map<String, Object> metadata = new HashMap<>();
        if (response != null && response.metadata() != null) {
            var meta = response.metadata();
            if (meta.finishReason() != null) {
                metadata.put("finishReason", meta.finishReason().toString());
            }
            if (meta.tokenUsage() != null) {
                var usage = meta.tokenUsage();
                metadata.put("tokenUsage", Map.of("inputTokens", usage.inputTokenCount() != null ? usage.inputTokenCount() : 0, "outputTokens",
                        usage.outputTokenCount() != null ? usage.outputTokenCount() : 0, "totalTokens",
                        usage.totalTokenCount() != null ? usage.totalTokenCount() : 0));
            }
        }
        return metadata;
    }
}
