/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.testing;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.internal.ExceptionMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A scripted {@link ChatModel} for resilience tests: the n-th call to the model
 * does whatever the n-th {@link Step} says, so a test can say "503, 503, then a
 * valid answer" or "prose, then JSON" and assert what the engine did about it.
 * <p>
 * <b>Test scope only.</b> It is deliberately not registered as a model type in
 * {@code ChatModelRegistry} and nothing in {@code src/main} knows about it.
 * Hand it to code that takes a {@link ChatModel} (or stub
 * {@code ChatModelRegistry.getOrCreate} to return it).
 *
 * <h2>Script</h2>
 *
 * <pre>{@code
 * var model = FaultInjectingChatModel.script(
 *         Step.status(503), // call 1: provider overloaded
 *         Step.status(503), // call 2: still overloaded
 *         Step.text("{\"answer\":\"hi\"}")); // call 3: fine
 * }</pre>
 *
 * Calls beyond the script throw {@link IllegalStateException} so a test that
 * triggers an unexpected extra attempt fails loudly; call {@link #repeatLast()}
 * for "keeps failing" scenarios.
 *
 * <h2>Simulated HTTP errors</h2> {@link Step#status(int)} throws exactly what a
 * real langchain4j provider client throws: the raw {@link HttpException} is run
 * through langchain4j's own {@link ExceptionMapper#DEFAULT}, which yields the
 * typed {@code RateLimitException} (429), {@code InternalServerException}
 * (5xx), {@code InvalidRequestException} (400), {@code AuthenticationException}
 * (401/403) and {@code ModelNotFoundException} (404) that
 * {@code RetryConfiguration.isRetryableError} classifies on. The original
 * {@link HttpException} is the cause, so status-code matching works too.
 */
public final class FaultInjectingChatModel implements ChatModel {

    /** One scripted behaviour. Immutable; build with the static factories. */
    public sealed interface Step permits Text, FinishWith, Status, Throws, Delayed {

        /** Answer with this text and finish reason STOP. */
        static Step text(String text) {
            return new Text(text);
        }

        /** Answer with an empty (non-null, zero-length) text and finish reason STOP. */
        static Step empty() {
            return new Text("");
        }

        /**
         * Answer with this text and the given finish reason (LENGTH, CONTENT_FILTER).
         */
        static Step finishReason(String text, FinishReason reason) {
            return new FinishWith(text, reason);
        }

        /** Fail as a provider answering with HTTP status {@code code} would. */
        static Step status(int code) {
            return new Status(code, "simulated HTTP " + code);
        }

        /** Fail with an arbitrary exception, e.g. a socket timeout. */
        static Step fail(RuntimeException e) {
            return new Throws(e);
        }

        /** Sleep {@code millis} (interruptibly), then behave as {@code then}. */
        static Step delayed(long millis, Step then) {
            return new Delayed(millis, then);
        }
    }

    public record Text(String text) implements Step {
    }

    public record FinishWith(String text, FinishReason reason) implements Step {
    }

    public record Status(int code, String message) implements Step {
    }

    public record Throws(RuntimeException exception) implements Step {
    }

    public record Delayed(long millis, Step then) implements Step {
    }

    private final List<Step> script;
    private final List<ChatRequest> requests = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger calls = new AtomicInteger();
    private volatile boolean repeatLast;

    private FaultInjectingChatModel(List<Step> script) {
        if (script.isEmpty()) {
            throw new IllegalArgumentException("a fault-injection script needs at least one step");
        }
        this.script = List.copyOf(script);
    }

    public static FaultInjectingChatModel script(Step... steps) {
        return new FaultInjectingChatModel(List.of(steps));
    }

    /** After the script ends, keep replaying its last step instead of failing. */
    public FaultInjectingChatModel repeatLast() {
        this.repeatLast = true;
        return this;
    }

    /** Number of calls the model has received so far. */
    public int callCount() {
        return calls.get();
    }

    /** Every request received, in order. */
    public List<ChatRequest> requests() {
        synchronized (requests) {
            return List.copyOf(requests);
        }
    }

    @Override
    public ChatResponse doChat(ChatRequest request) {
        requests.add(request);
        int index = calls.getAndIncrement();
        if (index >= script.size() && !repeatLast) {
            throw new IllegalStateException("fault-injection script exhausted: call " + (index + 1) + " but only " + script.size() + " step(s)");
        }
        return perform(script.get(Math.min(index, script.size() - 1)));
    }

    private ChatResponse perform(Step step) {
        return switch (step) {
            case Text t -> response(t.text(), FinishReason.STOP);
            case FinishWith f -> response(f.text(), f.reason());
            case Status s -> throw ExceptionMapper.DEFAULT.mapException(new HttpException(s.code(), s.message()));
            case Throws t -> throw t.exception();
            case Delayed d -> {
                try {
                    Thread.sleep(d.millis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("interrupted while simulating a slow provider", e);
                }
                yield perform(d.then());
            }
        };
    }

    private static ChatResponse response(String text, FinishReason reason) {
        return ChatResponse.builder().aiMessage(AiMessage.from(text)).finishReason(reason).build();
    }
}
