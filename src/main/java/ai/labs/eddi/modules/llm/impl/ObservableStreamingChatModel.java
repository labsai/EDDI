/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
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
 * request/response logging and telemetry to any {@link StreamingChatModel}.
 * Applied by {@link ChatModelRegistry} to every streaming model it builds.
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
    private final List<ChatModelListener> listeners;

    ObservableStreamingChatModel(StreamingChatModel delegate, String modelType, boolean logRequests, boolean logResponses,
            ChatModelListener telemetryListener) {
        this.delegate = delegate;
        this.modelType = modelType;
        this.logRequests = logRequests;
        this.logResponses = logResponses;

        // EDDI's listener only, for the same reason as ObservableChatModel: doChat
        // forwards to delegate.chat(), so the delegate dispatches its own listeners.
        this.listeners = telemetryListener == null ? List.of() : List.of(LlmTelemetryListener.forModelType(telemetryListener, modelType));
    }

    /**
     * {@code doChat} is the override, not either {@code chat} overload, and that
     * placement is load-bearing.
     * <p>
     * {@code StreamingChatModel.chat(ChatRequest, ChatRequestOptions, handler)} is
     * where the interface reads {@link #listeners()} and fires
     * {@code onRequest}/{@code onResponse}/{@code onError} around {@code doChat}.
     * An earlier revision of this class overrode <em>both</em> {@code chat}
     * overloads, which meant that default never ran, this decorator's
     * {@code listeners()} was never consulted, and EDDI's telemetry listener never
     * fired on a streaming turn at all — silently, because nothing asserted a
     * callback.
     * <p>
     * Forwarding to {@code delegate.chat(...)} rather than
     * {@code delegate.doChat(...)} is the same constraint the synchronous decorator
     * documents: a {@link StreamingChatModel} may implement either, and
     * re-dispatching to the wrong one hits the interface default and blows up with
     * "Not implemented". Jlama and Vertex Gemini both override {@code chat}. The
     * delegate therefore dispatches its own listeners, which is why
     * {@link #listeners()} carries only EDDI's.
     */
    @Override
    public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
        logRequest(request);
        delegate.chat(request, observing(handler));
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

    /**
     * EDDI's telemetry listener, and only that — mirroring
     * {@link ObservableChatModel#listeners()}, and for the same reason:
     * {@link #doChat} forwards to {@code delegate.chat}, so the delegate dispatches
     * its own listeners. Carrying them here as well would fire each of them twice.
     */
    @Override
    public List<ChatModelListener> listeners() {
        return listeners;
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
     * Wraps a StreamingChatModel with request/response logging and telemetry.
     * <p>
     * <b>Always wraps</b>, for the same reason as {@link ObservableChatModel#wrap}:
     * returning the bare model when no logging flag is set left the default path
     * with nowhere to attach a listener, and so with no telemetry at all.
     * <p>
     * {@code timeout} deliberately does not trigger wrapping — see the class
     * javadoc; it is honoured by the provider's own streaming HTTP client and by
     * the executor's overall backstop.
     */
    static StreamingChatModel wrap(StreamingChatModel model, String modelType, String logReq, String logResp,
                                   ChatModelListener telemetryListener) {
        boolean logRequests = Boolean.parseBoolean(logReq);
        boolean logResponses = Boolean.parseBoolean(logResp);

        return new ObservableStreamingChatModel(model, modelType, logRequests, logResponses, telemetryListener);
    }
}
