/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.concurrent.*;

/**
 * Decorator that adds provider-agnostic timeout and request/response logging to
 * any {@link ChatModel}. Applied automatically by {@link ChatModelRegistry}
 * when {@code timeout}, {@code logRequests}, or {@code logResponses} parameters
 * are set in the langchain configuration.
 */
public class ObservableChatModel implements ChatModel {
    private static final Logger LOGGER = Logger.getLogger(ObservableChatModel.class);
    private static final ExecutorService EXECUTOR = Executors.newCachedThreadPool(r -> {
        var t = new Thread(r, "eddi-chat-timeout");
        t.setDaemon(true);
        return t;
    });

    private final ChatModel delegate;
    private final Duration timeout;
    private final boolean logRequests;
    private final boolean logResponses;
    private final String modelType;

    ObservableChatModel(ChatModel delegate, String modelType, Duration timeout, boolean logRequests, boolean logResponses) {
        this.delegate = delegate;
        this.modelType = modelType;
        this.timeout = timeout;
        this.logRequests = logRequests;
        this.logResponses = logResponses;
    }

    @Override
    public ChatResponse chat(ChatRequest chatRequest) {
        if (logRequests) {
            var messages = chatRequest.messages();
            var lastMsg = messages.isEmpty() ? "<empty>" : messages.getLast().toString();
            LOGGER.infof("[%s] Chat request: %d messages, last: \"%s\"", modelType, messages.size(), truncate(lastMsg, 200));
        }

        long startMs = System.currentTimeMillis();
        ChatResponse response;

        if (timeout != null && !timeout.isZero()) {
            response = chatWithTimeout(chatRequest);
        } else {
            response = delegate.chat(chatRequest);
        }

        long elapsedMs = System.currentTimeMillis() - startMs;

        if (logResponses) {
            var text = response.aiMessage() != null ? response.aiMessage().text() : "<null>";
            LOGGER.infof("[%s] Chat response (%dms): \"%s\"", modelType, elapsedMs, truncate(text, 500));
        }

        return response;
    }

    private ChatResponse chatWithTimeout(ChatRequest chatRequest) {
        Future<ChatResponse> future = EXECUTOR.submit(() -> delegate.chat(chatRequest));
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new RuntimeException(String.format("[%s] Chat request timed out after %dms", modelType, timeout.toMillis()), e);
        } catch (ExecutionException e) {
            throw new RuntimeException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Chat request interrupted", e);
        }
    }

    private static String truncate(String text, int maxLen) {
        if (text == null)
            return "<null>";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "…";
    }

    /**
     * Wraps a ChatModel with timeout and logging if any observability params are
     * set. Returns the original model unwrapped if no observability is configured.
     */
    static ChatModel wrapIfNeeded(ChatModel model, String modelType, String timeoutMs, String logReq, String logResp) {
        Duration timeout = null;
        if (timeoutMs != null && !timeoutMs.isBlank()) {
            try {
                var parsed = Duration.ofMillis(Long.parseLong(timeoutMs));
                if (!parsed.isZero() && !parsed.isNegative()) {
                    timeout = parsed;
                }
            } catch (NumberFormatException ignored) {
                // invalid timeout value, skip
            }
        }

        boolean logRequests = Boolean.parseBoolean(logReq);
        boolean logResponses = Boolean.parseBoolean(logResp);

        if (timeout == null && !logRequests && !logResponses) {
            return model; // no wrapping needed
        }

        return new ObservableChatModel(model, modelType, timeout, logRequests, logResponses);
    }
}
