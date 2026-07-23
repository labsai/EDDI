/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatRequestOptions;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Set;

/**
 * Streaming counterpart to {@link ObservableChatModel}: adds provider-agnostic
 * request/response logging to any {@link StreamingChatModel}. Applied
 * automatically by {@link ChatModelRegistry} when {@code logRequests} or
 * {@code logResponses} are set in the langchain configuration.
 * <p>
 * <strong>Deliberately no timeout here.</strong> {@link ObservableChatModel}
 * bounds a synchronous call with {@code Future.get}, which is the right shape
 * for a single request/response. It is the wrong shape for a stream: an overall
 * wall-clock bound kills a healthy long answer, and cancelling the awaiting
 * thread does not stop the provider's callback thread. A streaming task is
 * bounded at two other, streaming-appropriate places instead:
 * <ul>
 * <li>the {@code timeout} parameter, which every streaming builder passes to
 * its HTTP client as the request/read timeout — for the JDK client that is the
 * time to the first response headers, so it detects a provider that never
 * answers without truncating one that answers slowly;</li>
 * <li>{@code streamingTimeoutSeconds}, the overall backstop
 * {@code StreamingLegacyChatExecutor} applies to the whole stream, which
 * defaults so that it never fires before the configured {@code timeout}.</li>
 * </ul>
 */
class ObservableStreamingChatModel implements StreamingChatModel {
    private static final Logger LOGGER = Logger.getLogger(ObservableStreamingChatModel.class);

    private final StreamingChatModel delegate;
    private final String modelType;
    private final boolean logRequests;
    private final boolean logResponses;

    ObservableStreamingChatModel(StreamingChatModel delegate, String modelType, boolean logRequests, boolean logResponses) {
        this.delegate = delegate;
        this.modelType = modelType;
        this.logRequests = logRequests;
        this.logResponses = logResponses;
    }

    /**
     * Each overload forwards to the <em>same</em> overload on the delegate rather
     * than funnelling through one of them. A {@link StreamingChatModel} may
     * implement either {@code doChat} or the two-argument {@code chat} directly;
     * re-dispatching to a different overload would hit the interface default and
     * blow up with "Not implemented" for the latter kind.
     */
    @Override
    public void chat(ChatRequest request, StreamingChatResponseHandler handler) {
        logRequest(request);
        delegate.chat(request, observing(handler));
    }

    @Override
    public void chat(ChatRequest request, ChatRequestOptions options, StreamingChatResponseHandler handler) {
        logRequest(request);
        delegate.chat(request, options, observing(handler));
    }

    private void logRequest(ChatRequest request) {
        if (logRequests) {
            var messages = request.messages();
            var lastMsg = messages.isEmpty() ? "<empty>" : messages.getLast().toString();
            LOGGER.infof("[%s] Streaming chat request: %d messages, last: \"%s\"", modelType, messages.size(), truncate(lastMsg, 200));
        }
    }

    private StreamingChatResponseHandler observing(StreamingChatResponseHandler handler) {
        if (!logResponses) {
            return handler;
        }

        long startMs = System.currentTimeMillis();
        var accumulated = new StringBuilder();

        return new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                if (partialResponse != null) {
                    synchronized (accumulated) {
                        accumulated.append(partialResponse);
                    }
                }
                handler.onPartialResponse(partialResponse);
            }

            @Override
            public void onCompleteResponse(ChatResponse completeResponse) {
                LOGGER.infof("[%s] Streaming chat response (%dms): \"%s\"", modelType,
                        System.currentTimeMillis() - startMs, truncate(snapshot(), 500));
                handler.onCompleteResponse(completeResponse);
            }

            @Override
            public void onError(Throwable error) {
                LOGGER.infof("[%s] Streaming chat error after %dms (%d chars streamed): %s", modelType,
                        System.currentTimeMillis() - startMs, snapshot().length(), error != null ? error.getMessage() : "<null>");
                handler.onError(error);
            }

            private String snapshot() {
                synchronized (accumulated) {
                    return accumulated.toString();
                }
            }
        };
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return delegate.defaultRequestParameters();
    }

    @Override
    public List<ChatModelListener> listeners() {
        return delegate.listeners();
    }

    @Override
    public ModelProvider provider() {
        return delegate.provider();
    }

    @Override
    public Set<Capability> supportedCapabilities() {
        return delegate.supportedCapabilities();
    }

    private static String truncate(String text, int maxLen) {
        if (text == null)
            return "<null>";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "…";
    }

    /**
     * Wraps a StreamingChatModel with request/response logging if either logging
     * flag is set. Returns the original model unwrapped otherwise.
     * <p>
     * {@code timeout} deliberately does not trigger wrapping — see the class
     * javadoc; it is honoured by the provider's own streaming HTTP client and by
     * the executor's overall backstop.
     */
    static StreamingChatModel wrapIfNeeded(StreamingChatModel model, String modelType, String logReq, String logResp) {
        boolean logRequests = Boolean.parseBoolean(logReq);
        boolean logResponses = Boolean.parseBoolean(logResp);

        if (!logRequests && !logResponses) {
            return model;
        }

        return new ObservableStreamingChatModel(model, modelType, logRequests, logResponses);
    }
}
