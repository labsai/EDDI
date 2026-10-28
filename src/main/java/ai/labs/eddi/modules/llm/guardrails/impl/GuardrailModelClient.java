/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.guardrails.impl;

import ai.labs.eddi.configs.shared.TurnDeadline;
import ai.labs.eddi.modules.llm.guardrails.GuardrailContext;
import ai.labs.eddi.modules.llm.guardrails.GuardrailPhase;
import ai.labs.eddi.modules.llm.guardrails.GuardrailUndecidedException;
import ai.labs.eddi.modules.llm.guardrails.GuardrailVerdict;
import ai.labs.eddi.modules.llm.guardrails.PaidCallGate;
import ai.labs.eddi.modules.llm.impl.ChatModelRegistry;
import ai.labs.eddi.modules.llm.impl.TokenPricing;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * The model-call machinery shared by the guardrail types that ask a model for a
 * verdict ({@code classifier}, {@code judge}) — one implementation, so the two
 * cannot drift apart on anything that matters for safety or cost:
 * <ul>
 * <li><b>Transport</b>: the model is built by {@link ChatModelRegistry} with
 * the provider type named by {@code provider} and every parameter that is not
 * the guardrail's own passed through unchanged; the registry resolves vault and
 * {@code ${vars}} references, as for a task.</li>
 * <li><b>Time</b>: {@code timeoutMs}, capped at {@link #MAX_TIMEOUT_MS}, is
 * both the provider client's timeout and the wait here; the turn deadline wins
 * when it is closer, and a turn already out of time makes no call. The call
 * runs on a virtual thread so the wait can be cut short.</li>
 * <li><b>Prompt</b>: an author's {@code promptTemplate} is rendered with the
 * screened text as data only ({@code {input}}, {@code {output}},
 * {@code {context}}, plus whatever the type adds) — never as template
 * source.</li>
 * <li><b>Cost</b>: priced from the reported token usage with
 * {@code inputPricePer1M} / {@code outputPricePer1M}, and still counted when
 * the answer cannot be interpreted.</li>
 * <li><b>Could not decide</b>: an unsupported provider, an unreachable or slow
 * model or a template that does not render becomes a
 * {@link GuardrailUndecidedException} with the type's "unavailable" reason; an
 * answer the type cannot read, with its "unparseable" reason. The provider's
 * error message is never propagated — it can echo the screened text.</li>
 * <li><b>Metrics</b>: one timer sample per call, tagged {@code provider} and
 * {@code outcome}.</li>
 * </ul>
 * Not a CDI bean: each guardrail type owns one instance, built from its own
 * injected dependencies, so a type's constructor stays what its tests call.
 */
final class GuardrailModelClient {

    private static final Logger LOGGER = Logger.getLogger(GuardrailModelClient.class);

    static final String PARAM_PROVIDER = "provider";
    static final String PARAM_PROMPT_TEMPLATE = "promptTemplate";
    static final String PARAM_TIMEOUT_MS = "timeoutMs";
    static final String PARAM_INCLUDE_CONTEXT = "includeRetrievedContext";
    static final String PARAM_INPUT_PRICE = "inputPricePer1M";
    static final String PARAM_OUTPUT_PRICE = "outputPricePer1M";
    /** The provider builder's own timeout key, derived from {@code timeoutMs}. */
    static final String PARAM_TIMEOUT = "timeout";

    /**
     * Ceiling on {@code timeoutMs}: a model-backed guardrail sits on a turn's path.
     */
    static final int MAX_TIMEOUT_MS = 60_000;

    /**
     * Keys every model-backed guardrail owns (and the generic ones every guardrail
     * understands); a type adds its own. None of them reaches the provider builder.
     */
    static final Set<String> COMMON_PARAMETERS = Set.of(PARAM_PROVIDER, PARAM_PROMPT_TEMPLATE, PARAM_TIMEOUT_MS, PARAM_INCLUDE_CONTEXT,
            PARAM_INPUT_PRICE, PARAM_OUTPUT_PRICE, "failMode", "repromptMessage");

    /**
     * What a type made of the model's answer: the timer's {@code outcome} tag and
     * the verdict, or no verdict for an answer the type could not read.
     */
    record Interpretation(String outcome, GuardrailVerdict verdict) {
        /** An answer the type could not read: undecided, with the cost counted. */
        static Interpretation unparseable() {
            return new Interpretation("unparseable", null);
        }
    }

    /** Reads one answer; attach {@code costUsd} to the verdict returned. */
    @FunctionalInterface
    interface Interpreter {
        Interpretation interpret(String answer, double costUsd);
    }

    /** The response format of one request, by provider type. */
    @FunctionalInterface
    interface ResponseFormatChooser {
        ResponseFormat choose(String provider, Map<String, String> modelParameters);
    }

    /**
     * One call.
     *
     * @param ownParameters
     *            the type's keys, withheld from the provider builder
     * @param defaultTimeoutMs
     *            the type's {@code timeoutMs} default
     * @param messages
     *            builds the request's messages; an {@link IllegalStateException} (a
     *            template that does not render) is "unavailable"
     * @param responseFormat
     *            the request's response format, or null for none — decided per
     *            request, never baked into the cached model
     * @param interpreter
     *            reads the answer
     */
    record Call(Set<String> ownParameters, int defaultTimeoutMs, Supplier<List<ChatMessage>> messages, ResponseFormatChooser responseFormat,
            Interpreter interpreter) {
    }

    private final String name;
    private final Supplier<Timer.Builder> timer;
    private final String reasonUnavailable;
    private final String reasonUnparseable;
    private final ChatModelRegistry chatModelRegistry;
    private final ITemplatingEngine templatingEngine;
    private final MeterRegistry meterRegistry;
    /** Virtual threads: an abandoned call holds heap, not a platform thread. */
    private final ExecutorService executor;

    /**
     * @param name
     *            how log lines name the guardrail ({@code Classifier guardrail})
     * @param threadPrefix
     *            the call threads' name prefix
     * @param timer
     *            a fresh builder of the type's duration timer — written at the type
     *            as {@code Timer.builder(CONSTANT)}, so the meter is found where it
     *            is named
     */
    GuardrailModelClient(String name, String threadPrefix, Supplier<Timer.Builder> timer, String reasonUnavailable, String reasonUnparseable,
            ChatModelRegistry chatModelRegistry, ITemplatingEngine templatingEngine, MeterRegistry meterRegistry) {
        this.name = name;
        this.timer = timer;
        this.reasonUnavailable = reasonUnavailable;
        this.reasonUnparseable = reasonUnparseable;
        this.chatModelRegistry = chatModelRegistry;
        this.templatingEngine = templatingEngine;
        this.meterRegistry = meterRegistry;
        this.executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name(threadPrefix, 0).factory());
    }

    /**
     * Makes the call and returns the type's verdict, or throws
     * {@link GuardrailUndecidedException}.
     */
    GuardrailVerdict evaluate(GuardrailContext context, Call call) {
        String provider = context.parameter(PARAM_PROVIDER, null);
        if (provider == null) {
            LOGGER.warnf("%s of task '%s' has no 'provider' parameter", name, context.taskId());
            throw new GuardrailUndecidedException(reasonUnavailable, 0.0);
        }
        long started = System.nanoTime();
        String outcome = "unavailable";
        String providerTag = "unknown";
        try {
            List<ChatMessage> messages = call.messages().get();
            Map<String, String> modelParameters = modelParameters(context, call.ownParameters(), call.defaultTimeoutMs());
            ChatModel model = chatModelRegistry.getOrCreate(provider.trim(), modelParameters);
            providerTag = provider.trim();
            ChatRequest.Builder request = ChatRequest.builder().messages(messages);
            ResponseFormat format = call.responseFormat() != null ? call.responseFormat().choose(providerTag, modelParameters) : null;
            if (format != null) {
                request.responseFormat(format);
            }
            // The call is checked against the conversation's cost ceiling and the tenant
            // budget before it starts, like the task's own model call — for every
            // model-backed guardrail type. Refused, it is not made: "could not decide",
            // so failMode applies.
            try (PaidCallGate.PaidCall paid = context.spend().begin(expectedCost(messages, context))) {
                if (!paid.admitted()) {
                    outcome = PaidCallGate.REASON_SPEND_REFUSED;
                    LOGGER.infof("%s of task '%s' skipped: %s", name, context.taskId(), paid.refusalReason());
                    throw new GuardrailUndecidedException(PaidCallGate.REASON_SPEND_REFUSED, 0.0);
                }
                ChatResponse response = send(model, request.build(), context, call.defaultTimeoutMs());
                double cost = cost(response, context);
                // Recorded whether or not the answer turns out to be readable.
                paid.spent(cost);
                String answer = response != null && response.aiMessage() != null ? response.aiMessage().text() : null;
                Interpretation interpretation = call.interpreter().interpret(answer, cost);
                outcome = interpretation.outcome();
                if (interpretation.verdict() == null) {
                    throw new GuardrailUndecidedException(reasonUnparseable, cost);
                }
                return interpretation.verdict();
            }
        } catch (ChatModelRegistry.UnsupportedLlmTaskException e) {
            providerTag = "unsupported";
            LOGGER.warnf("%s of task '%s' names an unsupported provider", name, context.taskId());
            throw new GuardrailUndecidedException(reasonUnavailable, 0.0);
        } catch (CallTimeoutException e) {
            outcome = "timeout";
            throw new GuardrailUndecidedException(reasonUnavailable, 0.0);
        } catch (GuardrailUndecidedException e) {
            throw e;
        } catch (RuntimeException e) {
            // Never the provider's message: it can echo the request, i.e. the screened
            // text.
            LOGGER.warnf("%s of task '%s' could not reach its model (%s)", name, context.taskId(), e.getClass().getSimpleName());
            throw new GuardrailUndecidedException(reasonUnavailable, 0.0);
        } finally {
            if (meterRegistry != null) {
                timer.get().tag("provider", providerTag).tag("outcome", outcome).register(meterRegistry)
                        .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
            }
        }
    }

    // === Request ===

    /**
     * Renders the author's {@code promptTemplate} with {@code data} as template
     * data — the screened text is never part of the template source. Throws
     * {@link IllegalStateException} for a template that does not render or renders
     * to nothing (an author error: could not decide).
     */
    String render(String template, Map<String, Object> data, GuardrailContext context) {
        try {
            String prompt = templatingEngine.processTemplate(template, data);
            if (prompt == null || prompt.isBlank()) {
                throw new IllegalStateException("empty guardrail prompt");
            }
            return prompt;
        } catch (ITemplatingEngine.TemplateEngineException e) {
            LOGGER.warnf("%s of task '%s': promptTemplate does not render", name, context.taskId());
            throw new IllegalStateException("guardrail promptTemplate does not render");
        }
    }

    /**
     * The template data every model-backed guardrail offers: {@code input} (the
     * user's message; in the output phase, the user message the model answered),
     * {@code output} (the answer, output phase) and {@code context} (the turn's
     * retrieved context, output phase, only with {@code includeRetrievedContext}).
     */
    static Map<String, Object> templateData(String text, GuardrailContext context) {
        String screened = text != null ? text : "";
        boolean output = context.phase() == GuardrailPhase.OUTPUT;
        Map<String, Object> data = new HashMap<>();
        data.put("input", output ? lastUserText(context.messages()) : screened);
        data.put("output", output ? screened : "");
        data.put("context", output && includeContext(context) && context.retrievedContext() != null ? context.retrievedContext() : "");
        return data;
    }

    /**
     * The provider parameters: every parameter that is not the guardrail's own,
     * plus {@code timeout} from {@code timeoutMs} so the provider's HTTP client
     * gives up as well. Stable per configuration, so the registry caches one model.
     */
    static Map<String, String> modelParameters(GuardrailContext context, Set<String> ownParameters, int defaultTimeoutMs) {
        Map<String, String> params = new HashMap<>();
        context.parameters().forEach((key, value) -> {
            if (!ownParameters.contains(key)) {
                params.put(key, value);
            }
        });
        params.put(PARAM_TIMEOUT, Long.toString(timeoutMs(context, defaultTimeoutMs)));
        return params;
    }

    static long timeoutMs(GuardrailContext context, int defaultTimeoutMs) {
        int configured = context.intParameter(PARAM_TIMEOUT_MS, defaultTimeoutMs);
        if (configured <= 0) {
            configured = defaultTimeoutMs;
        }
        return Math.min(configured, MAX_TIMEOUT_MS);
    }

    /**
     * How long this call may take: {@code timeoutMs}, or less when the turn's
     * deadline (after its reserve) is closer.
     */
    static long budgetMs(GuardrailContext context, int defaultTimeoutMs) {
        long budget = timeoutMs(context, defaultTimeoutMs);
        TurnDeadline deadline = context.deadline();
        if (deadline != null) {
            budget = Math.min(budget, deadline.remainingAfterReserveMs());
        }
        return budget;
    }

    private ChatResponse send(ChatModel model, ChatRequest request, GuardrailContext context, int defaultTimeoutMs) {
        long budget = budgetMs(context, defaultTimeoutMs);
        if (budget <= 0) {
            LOGGER.infof("%s of task '%s' skipped: the turn is out of time", name, context.taskId());
            throw new CallTimeoutException();
        }
        Future<ChatResponse> future = executor.submit(() -> model.chat(request));
        try {
            return future.get(budget, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            LOGGER.infof("%s of task '%s' timed out after %dms", name, context.taskId(), budget);
            throw new CallTimeoutException();
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new CallTimeoutException();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("guardrail model call failed", cause);
        }
    }

    // === Cost ===

    static double cost(ChatResponse response, GuardrailContext context) {
        Double inputPrice = price(context, PARAM_INPUT_PRICE);
        Double outputPrice = price(context, PARAM_OUTPUT_PRICE);
        TokenUsage usage = response != null ? response.tokenUsage() : null;
        if (usage == null) {
            return 0.0;
        }
        Map<String, Object> tokenUsage = new HashMap<>();
        tokenUsage.put("inputTokens", usage.inputTokenCount() != null ? usage.inputTokenCount() : 0);
        tokenUsage.put("outputTokens", usage.outputTokenCount() != null ? usage.outputTokenCount() : 0);
        double cost = TokenPricing.cost(inputPrice, outputPrice, tokenUsage);
        return Double.isFinite(cost) && cost > 0.0 ? cost : 0.0;
    }

    /**
     * What a call over {@code messages} is expected to cost, for the spend
     * reservation: the prompt at about four characters per token and the input
     * price. The answer is a label or a short verdict, so it is left out. 0 when
     * the guardrail is unpriced.
     */
    static double expectedCost(List<ChatMessage> messages, GuardrailContext context) {
        Double inputPrice = price(context, PARAM_INPUT_PRICE);
        if (inputPrice == null || messages == null) {
            return 0.0;
        }
        long chars = 0;
        for (ChatMessage message : messages) {
            if (message instanceof UserMessage user) {
                chars += userText(user).length();
            } else if (message instanceof AiMessage ai && ai.text() != null) {
                chars += ai.text().length();
            }
        }
        Map<String, Object> tokenUsage = new HashMap<>();
        tokenUsage.put("inputTokens", chars / 4);
        tokenUsage.put("outputTokens", 0);
        double cost = TokenPricing.cost(inputPrice, null, tokenUsage);
        return Double.isFinite(cost) && cost > 0.0 ? cost : 0.0;
    }

    /**
     * Whether either price is set — an unpriced model call is invisible to the
     * turn's cost and so to cost ceilings.
     */
    static boolean priced(Map<String, String> parameters) {
        return parameters != null && (!isBlank(parameters.get(PARAM_INPUT_PRICE)) || !isBlank(parameters.get(PARAM_OUTPUT_PRICE)));
    }

    private static Double price(GuardrailContext context, String name) {
        String raw = context.parameter(name, null);
        if (raw == null) {
            return null;
        }
        try {
            double value = Double.parseDouble(raw.trim());
            return Double.isFinite(value) && value >= 0.0 ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // === Helpers ===

    static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    static boolean includeContext(GuardrailContext context) {
        return Boolean.parseBoolean(context.parameter(PARAM_INCLUDE_CONTEXT, "false").trim());
    }

    static String lastUserText(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage user) {
                return userText(user);
            }
        }
        return "";
    }

    private static String userText(UserMessage message) {
        if (message.hasSingleText()) {
            return message.singleText();
        }
        StringBuilder text = new StringBuilder();
        message.contents().forEach(content -> {
            if (content instanceof TextContent part) {
                if (!text.isEmpty()) {
                    text.append('\n');
                }
                text.append(part.text());
            }
        });
        return text.toString();
    }

    /** The call ran out of time — its own or the turn's. */
    private static final class CallTimeoutException extends RuntimeException {
        CallTimeoutException() {
            super("guardrail model call timed out", null, false, false);
        }
    }
}
