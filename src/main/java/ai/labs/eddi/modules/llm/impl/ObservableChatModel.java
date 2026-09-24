/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.*;

/**
 * Decorator that adds provider-agnostic timeout, request/response logging and
 * telemetry to any {@link ChatModel}. Applied by {@link ChatModelRegistry} to
 * every model it builds.
 *
 * <h2>Why this overrides {@code doChat} and not {@code chat}</h2>
 *
 * It used to override {@code chat(ChatRequest)}, and that quietly cost EDDI
 * every per-call hook langchain4j provides. {@code ChatModel.chat(ChatRequest)}
 * delegates to {@code chat(ChatRequest, ChatRequestOptions)}, and <em>that</em>
 * is where the interface merges {@link #defaultRequestParameters()} into the
 * request and fires {@code onRequest} / {@code onResponse} / {@code onError}
 * around {@code doChat}. Overriding the entry point skipped the whole thing at
 * the decorator level: there was nowhere to attach a listener, so there was no
 * LLM span and no LLM metric on the default single-model path.
 * <p>
 * So {@code doChat} is the override, and {@link #defaultRequestParameters()},
 * {@link #provider()} and {@link #supportedCapabilities()} delegate, so the
 * inherited {@code chat} behaves as the provider's own would.
 *
 * <h2>Why {@code doChat} forwards to {@code delegate.chat}, not
 * {@code delegate.doChat}</h2>
 *
 * Forwarding to {@code delegate.doChat} looks tidier and is wrong. A
 * {@link ChatModel} may implement <em>either</em> {@code doChat} or
 * {@code chat(ChatRequest)} directly, and EDDI ships a provider that does the
 * latter: {@code JlamaChatModel} overrides {@code chat}. Calling
 * {@code delegate.doChat} on one of those hits the interface default and throws
 * {@code "Not implemented"} — turning every Jlama turn into a failure.
 * {@link ObservableStreamingChatModel} documents the same hazard for the
 * streaming overloads; this is the synchronous half of it.
 * <p>
 * Forwarding to {@code delegate.chat} means the delegate runs the interface
 * default a second time. That is deliberate and costs nothing:
 * <ul>
 * <li><b>the merge is idempotent</b> — the second
 * {@code defaultRequestParameters().overrideWith(...)} sees parameters that
 * already contain those defaults, so it computes the same request;</li>
 * <li><b>listeners still fire exactly once each.</b> This decorator's
 * {@link #listeners()} returns only EDDI's telemetry listener, <em>not</em> the
 * delegate's. The delegate dispatches its own listeners inside its own
 * {@code chat}, exactly as it did before this decorator existed. Combining the
 * two lists here — the obvious thing to do — would make every provider-
 * registered listener fire twice.</li>
 * </ul>
 *
 * <h2>Two consequences worth knowing</h2>
 *
 * <ul>
 * <li>{@code chatAsync} is not forwarded, so it fails with
 * {@code AsyncNotSupported} even where the delegate supports it natively. EDDI
 * has no caller today; forwarding {@code doChatAsync} is the fix when it
 * does.</li>
 * <li>{@code ChatRequestOptions.listenerAttributes} reach EDDI's listener but
 * not the delegate's, because {@link #doChat} re-enters
 * {@code delegate.chat(request)} with {@code ChatRequestOptions.EMPTY}. EDDI
 * never passes options, so this is latent.</li>
 * </ul>
 */
public class ObservableChatModel implements ChatModel {
    private static final Logger LOGGER = Logger.getLogger(ObservableChatModel.class);

    /**
     * Carrier for the timed provider call.
     * <p>
     * {@link #chatWithTimeout} abandons the worker on timeout —
     * {@code future.cancel(true)} interrupts it, but an interrupt does not abort a
     * socket read, so the thread stays alive until the provider answers or its own
     * HTTP timeout fires. On the previous {@code newCachedThreadPool} that was one
     * leaked <em>platform</em> thread per timeout with no ceiling: a provider that
     * stalls under load grows the pool without limit while every caller has already
     * given up.
     * <p>
     * A fixed pool would cap the threads but reintroduce the failure as
     * head-of-line blocking — once N workers are stuck, the next chat call sits in
     * the queue and times out without ever having been sent. Virtual threads remove
     * the resource that was leaking instead: an abandoned worker parked on a socket
     * read holds no platform thread, so the residue of a timeout burst is heap that
     * the GC reclaims once the call finally returns, and no request is ever blocked
     * behind one.
     */
    private static final ExecutorService EXECUTOR = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("eddi-chat-timeout-", 0).factory());

    private final ChatModel delegate;
    private final Duration timeout;
    private final boolean logRequests;
    private final boolean logResponses;
    private final String modelType;
    private final List<ChatModelListener> listeners;

    ObservableChatModel(ChatModel delegate, String modelType, Duration timeout, boolean logRequests, boolean logResponses,
            ChatModelListener telemetryListener) {
        this.delegate = delegate;
        this.modelType = modelType;
        this.timeout = timeout;
        this.logRequests = logRequests;
        this.logResponses = logResponses;

        // EDDI's listener ONLY. The delegate's listeners are deliberately excluded:
        // doChat forwards to delegate.chat(), which dispatches them itself, so
        // including them here would fire every provider-registered listener twice.
        this.listeners = telemetryListener == null ? List.of() : List.of(telemetryListener);
    }

    @Override
    public ChatResponse doChat(ChatRequest chatRequest) {
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

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return delegate.defaultRequestParameters();
    }

    /**
     * EDDI's telemetry listener, and only that.
     * <p>
     * This is the hook the whole decorator reshuffle exists for: the inherited
     * {@code chat} fires it around {@link #doChat}, so one implementation observes
     * all eleven providers. The delegate's own listeners are <em>not</em> included
     * — see the class javadoc; {@link #doChat} forwards to {@code delegate.chat},
     * which dispatches them itself.
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

    private ChatResponse chatWithTimeout(ChatRequest chatRequest) {
        Future<ChatResponse> future = EXECUTOR.submit(() -> delegate.chat(chatRequest));
        try {
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new ChatTimeoutException(
                    String.format("[%s] Chat request timed out after %dms", modelType, timeout.toMillis()), e);
        } catch (ExecutionException e) {
            // Rethrow the provider's own exception rather than boxing it. Since
            // LlmTelemetryListener tags eddi.llm.request.errors and the span's
            // error.type with the exception CLASS, boxing made every failure on a
            // timeout-configured agent read as a bare RuntimeException — which is
            // exactly the distinction an operator needs. AgentExecutionHelper's
            // retry classifier walks the cause chain either way.
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new RuntimeException(cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Chat request interrupted", e);
        }
    }

    /**
     * Thrown when the wall-clock {@code timeout} elapses before the provider
     * answers.
     * <p>
     * A named type rather than a bare {@link RuntimeException} so that the
     * {@code error} tag on {@code eddi.llm.request.errors} and {@code error.type}
     * on the span say "timeout" instead of naming the most generic class in the
     * JDK.
     */
    public static class ChatTimeoutException extends RuntimeException {
        ChatTimeoutException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static String truncate(String text, int maxLen) {
        if (text == null)
            return "<null>";
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "…";
    }

    /**
     * Wraps a ChatModel with timeout, logging and telemetry.
     * <p>
     * <b>Always wraps.</b> It used to return the bare model when no
     * {@code timeout}, {@code logRequests} or {@code logResponses} was configured —
     * which is the default for essentially every agent — and that was the reason
     * the default path had no telemetry: there was no decorator to hang a listener
     * on. Wrapping unconditionally costs one delegating object per cached model,
     * and the models are cached, so it is one allocation per distinct configuration
     * rather than per turn.
     * <p>
     * The wrapper is behaviour-preserving when nothing is configured: no timeout,
     * no logging, every {@link ChatModel} method delegated, and the listener list
     * differing only by EDDI's own telemetry listener.
     *
     * @param telemetryListener
     *            may be {@code null}, in which case {@link #listeners()} is empty
     *            and this decorator dispatches nothing of its own — that is the
     *            shape unit tests use, since they have no {@code MeterRegistry}.
     *            The delegate still dispatches its own listeners inside its
     *            {@code chat}
     */
    static ChatModel wrap(ChatModel model, String modelType, String timeoutMs, String logReq, String logResp,
                          ChatModelListener telemetryListener) {
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

        return new ObservableChatModel(model, modelType, timeout, logRequests, logResponses, telemetryListener);
    }
}
