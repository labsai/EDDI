/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.secrets.sanitize.SecretRedactionFilter;
import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.output.TokenUsage;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * The single place EDDI reports what an LLM call cost, how long it took and
 * whether it failed.
 *
 * <h2>Why this did not exist</h2>
 *
 * Latency, token and cost meters did exist —
 * {@code eddi.llm.cascade.step.latency}, {@code eddi.llm.cascade.tokens} and
 * {@code eddi.llm.cascade.cost} — but only inside
 * {@code CascadingModelExecutor}, which {@code LlmTask} enters solely under
 * {@code if (cascadeActive)}. An agent that names one model, which is almost
 * every agent, produced no LLM telemetry at all: not a timer, not a token
 * count, not an error counter, and no span below {@code eddi.pipeline.task}. A
 * turn that spent eleven seconds waiting on a provider was indistinguishable
 * from one that spent eleven seconds in EDDI's own code.
 *
 * <h2>Why a listener rather than more code in the decorator</h2>
 *
 * {@code ChatModel.chat(ChatRequest, ChatRequestOptions)} already fires
 * {@code onRequest} / {@code onResponse} / {@code onError} around
 * {@code doChat}, and it does so for <em>every</em> provider, because the
 * dispatch lives on the interface rather than in each binding. Hanging
 * telemetry off that hook means one implementation covers all eleven providers
 * and both the sync and streaming decorators, instead of eleven partial ones.
 * The catch is that the dispatch only happens if something calls the default
 * {@code chat} — see {@link ObservableChatModel}, which had to stop overriding
 * it for this to work at all.
 *
 * <h2>Semantic conventions</h2>
 *
 * Span attributes follow the OpenTelemetry GenAI semantic conventions, using
 * the <em>current</em> names: {@code gen_ai.provider.name} (renamed from
 * {@code gen_ai.system} in semconv v1.37.0) and
 * {@code gen_ai.usage.input_tokens} / {@code output_tokens} (renamed from
 * {@code prompt_tokens} / {@code completion_tokens}).
 * <p>
 * Those conventions are <b>not stable</b>. Every {@code gen_ai.*} attribute
 * still carries Development status, and the whole namespace moved into its own
 * {@code semantic-conventions-genai} repository in v1.42.0 precisely so it can
 * keep changing. {@link #SEMCONV_SCHEMA_VERSION} records which revision these
 * names come from, so the next rename is a diff against a stated baseline
 * rather than an archaeology exercise. Micrometer meter names are EDDI-owned
 * ({@code eddi.llm.*}) and deliberately not derived from the convention, so a
 * rename upstream cannot silently break a dashboard or an alert.
 *
 * <h2>What a span counts</h2>
 *
 * One span and one {@code eddi.llm.request.duration} sample per
 * <em>attempt</em>, not per turn: {@code AgentExecutionHelper.executeWithRetry}
 * re-enters {@code chat} on a retryable failure, and each entry dispatches the
 * listeners again. That is the useful granularity for latency, and it means
 * {@code eddi.llm.request.errors} counts attempts rather than turns.
 * <p>
 * A cascading task reports twice over, once here and once through
 * {@code eddi.llm.cascade.*}. The two are not redundant — the cascade meters
 * are tagged by cascade step — but a dashboard must not add them together.
 */
@ApplicationScoped
public class LlmTelemetryListener implements ChatModelListener {

    private static final Logger LOGGER = Logger.getLogger(LlmTelemetryListener.class);

    /**
     * The semconv revision {@code gen_ai.*} attribute names here were taken from.
     */
    public static final String SEMCONV_SCHEMA_VERSION = "1.37.0";

    private static final String SPAN_NAME = "gen_ai.client.inference";

    // Attribute keys the langchain4j listener contract carries between callbacks.
    private static final String ATTR_SPAN = "eddi.telemetry.span";
    private static final String ATTR_START_NANOS = "eddi.telemetry.startNanos";
    private static final String ATTR_MODEL_TYPE = "eddi.telemetry.modelType";

    private static final String UNKNOWN = "unknown";

    /**
     * The meter tag every model beyond {@link #MAX_MODEL_TAGS} distinct names
     * shares (L1).
     */
    static final String OTHER_MODEL = "other";

    /**
     * Ceiling on distinct {@code model} tag values across the LLM meters.
     * <p>
     * A model name is configuration, but configuration can be templated —
     * {@code "modelName": "{properties.model}"} — and a property can be user input.
     * Every distinct value is a new time series on three meters, and on the
     * duration timer a new set of histogram buckets, so an unbounded tag is a
     * memory leak in the registry and a cardinality bill in Prometheus. Real
     * deployments use a handful of models; the first {@value} seen keep their own
     * series, everything after that is tagged {@value #OTHER_MODEL}. Spans are
     * unaffected — a span attribute creates no series — and keep the real name.
     */
    static final int MAX_MODEL_TAGS = 100;

    /** Longest model name kept verbatim, in tags and span attributes alike. */
    static final int MAX_MODEL_NAME_LENGTH = 128;

    /** Longest provider error text copied onto a span (L4). */
    static final int MAX_ERROR_TEXT_LENGTH = 256;

    private final Set<String> modelTags = ConcurrentHashMap.newKeySet();

    private final MeterRegistry meterRegistry;

    /**
     * Resolved lazily rather than at construction: {@link GlobalOpenTelemetry} is
     * installed by the Quarkus OpenTelemetry extension during startup, and a bean
     * created before that would cache a no-op tracer for the life of the process.
     * Mirrors {@code LifecycleManager.getTracer()}.
     */
    private volatile Tracer tracer;

    @Inject
    public LlmTelemetryListener(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * The listener one model instance registers: this listener, told which EDDI
     * provider type the model was built for.
     * <p>
     * L2: langchain4j's {@link ModelProvider} has no value for Jlama, HuggingFace
     * or Oracle GenAI, so those models report {@code OTHER} and every one of them
     * landed on the same {@code provider="OTHER"} series. The decorator knows the
     * type it built ({@code "jlama"}, {@code "huggingface"}, …); passing it along
     * lets the tag name the provider whenever langchain4j cannot. A listener that
     * is not EDDI's is returned unchanged.
     */
    static ChatModelListener forModelType(ChatModelListener listener, String modelType) {
        if (!(listener instanceof LlmTelemetryListener telemetry) || modelType == null || modelType.isBlank()) {
            return listener;
        }
        return new ChatModelListener() {
            @Override
            public void onRequest(ChatModelRequestContext context) {
                context.attributes().put(ATTR_MODEL_TYPE, modelType);
                telemetry.onRequest(context);
            }

            @Override
            public void onResponse(ChatModelResponseContext context) {
                telemetry.onResponse(context);
            }

            @Override
            public void onError(ChatModelErrorContext context) {
                telemetry.onError(context);
            }
        };
    }

    private Tracer tracer() {
        if (tracer == null) {
            tracer = GlobalOpenTelemetry.getTracer("eddi.llm");
        }
        return tracer;
    }

    @Override
    public void onRequest(ChatModelRequestContext context) {
        guard(() -> {
            Map<Object, Object> attributes = context.attributes();
            attributes.put(ATTR_START_NANOS, System.nanoTime());

            String provider = providerOf(context.modelProvider(), attributes);
            String model = modelOf(context.chatRequest() != null && context.chatRequest().parameters() != null
                    ? context.chatRequest().parameters().modelName()
                    : null);

            Span span = tracer().spanBuilder(SPAN_NAME)
                    .setSpanKind(SpanKind.CLIENT)
                    .setAttribute("gen_ai.operation.name", "chat")
                    .setAttribute("gen_ai.provider.name", provider)
                    .setAttribute("gen_ai.request.model", model)
                    .setAttribute("eddi.semconv.schema_version", SEMCONV_SCHEMA_VERSION)
                    .startSpan();

            attributes.put(ATTR_SPAN, span);
        });
    }

    @Override
    public void onResponse(ChatModelResponseContext context) {
        guard(() -> {
            String provider = providerOf(context.modelProvider(), context.attributes());
            String requestModel = modelOf(context.chatRequest() != null && context.chatRequest().parameters() != null
                    ? context.chatRequest().parameters().modelName()
                    : null);

            TokenUsage usage = context.chatResponse() != null && context.chatResponse().metadata() != null
                    ? context.chatResponse().metadata().tokenUsage()
                    : null;
            String responseModel = modelOf(context.chatResponse() != null && context.chatResponse().metadata() != null
                    ? context.chatResponse().metadata().modelName()
                    : null);

            Span span = span(context.attributes());
            if (span != null) {
                span.setAttribute("gen_ai.response.model", responseModel);
                if (usage != null) {
                    setIfPresent(span, "gen_ai.usage.input_tokens", usage.inputTokenCount());
                    setIfPresent(span, "gen_ai.usage.output_tokens", usage.outputTokenCount());
                }
                span.end();
            }

            recordDuration(context.attributes(), provider, requestModel, "success");
            recordTokens(provider, requestModel, usage);
        });
    }

    @Override
    public void onError(ChatModelErrorContext context) {
        guard(() -> {
            String provider = providerOf(context.modelProvider(), context.attributes());
            String model = modelOf(context.chatRequest() != null && context.chatRequest().parameters() != null
                    ? context.chatRequest().parameters().modelName()
                    : null);

            Throwable error = context.error();

            Span span = span(context.attributes());
            if (span != null) {
                // L4: never the raw provider text. A provider error can echo the
                // request — prompt fragments, a key in a URL, the user's data — and a
                // span is exported to whatever tracing backend is configured, with
                // none of the redaction the logs get. recordException(error) copied
                // the message AND the stack trace (every cause's message included), so
                // the exception event is written by hand: type plus redacted, capped
                // text. The full error stays in the application log.
                String errorText = error != null ? safeErrorText(error.getMessage()) : UNKNOWN;
                span.setStatus(StatusCode.ERROR, errorText);
                if (error != null) {
                    span.addEvent("exception", Attributes.of(
                            AttributeKey.stringKey("exception.type"), error.getClass().getName(),
                            AttributeKey.stringKey("exception.message"), errorText));
                    span.setAttribute("error.type", error.getClass().getName());
                }
                span.end();
            }

            recordDuration(context.attributes(), provider, model, "error");
            meterRegistry.counter("eddi.llm.request.errors",
                    "provider", provider,
                    "model", modelTag(model),
                    "error", error != null ? error.getClass().getSimpleName() : UNKNOWN).increment();
        });
    }

    // ────────────────────────────── helpers ──────────────────────────────

    private void recordDuration(Map<Object, Object> attributes, String provider, String model, String outcome) {
        Object startNanos = attributes.get(ATTR_START_NANOS);
        if (!(startNanos instanceof Long start)) {
            // onResponse/onError without a matching onRequest: possible if a listener
            // added later throws before this one runs. Losing a sample beats an NPE on
            // the response path.
            return;
        }
        Timer.builder("eddi.llm.request.duration")
                .tag("provider", provider)
                .tag("model", modelTag(model))
                .tag("outcome", outcome)
                .description("LLM provider call duration")
                // Without this the timer publishes only _count, _sum and _max: a
                // Prometheus registry emits no _bucket series at all, and the p95
                // dashboard panel that queries
                // histogram_quantile(0.95, ... eddi_llm_request_duration_seconds_bucket ...)
                // renders empty forever, which looks like "no LLM traffic" rather
                // than "this metric was never published".
                //
                // The cost is one series per bucket per provider/model/outcome. That
                // is the same bargain eddi.pipeline.task.duration already makes, and
                // the tag set here is bounded the same way: providers are a fixed
                // list, outcome is success or error, and model names are capped at
                // MAX_MODEL_TAGS distinct values (see modelTag). If a deployment ever
                // does find the cardinality too high, the dashboard-side fallback is
                // to plot _sum / _count as a mean instead and drop this line.
                .publishPercentileHistogram()
                .register(meterRegistry)
                .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
    }

    private void recordTokens(String provider, String model, TokenUsage usage) {
        if (usage == null) {
            return;
        }
        String tag = modelTag(model);
        if (usage.inputTokenCount() != null) {
            meterRegistry.counter("eddi.llm.tokens", "provider", provider, "model", tag, "type", "input")
                    .increment(usage.inputTokenCount());
        }
        if (usage.outputTokenCount() != null) {
            meterRegistry.counter("eddi.llm.tokens", "provider", provider, "model", tag, "type", "output")
                    .increment(usage.outputTokenCount());
        }
    }

    private static void setIfPresent(Span span, String key, Integer value) {
        if (value != null) {
            span.setAttribute(key, value.longValue());
        }
    }

    private static Span span(Map<Object, Object> attributes) {
        return attributes.get(ATTR_SPAN) instanceof Span span ? span : null;
    }

    static String providerOf(ModelProvider provider) {
        return provider == null ? UNKNOWN : provider.name();
    }

    /**
     * The provider tag: langchain4j's own name where it has one, otherwise the EDDI
     * model type the decorator recorded (see {@link #forModelType}).
     */
    static String providerOf(ModelProvider provider, Map<Object, Object> attributes) {
        if ((provider == null || provider == ModelProvider.OTHER) && attributes != null
                && attributes.get(ATTR_MODEL_TYPE) instanceof String modelType) {
            return modelType;
        }
        return providerOf(provider);
    }

    static String modelOf(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return UNKNOWN;
        }
        return modelName.length() > MAX_MODEL_NAME_LENGTH ? modelName.substring(0, MAX_MODEL_NAME_LENGTH) : modelName;
    }

    /** The model as a meter tag, bounded to {@link #MAX_MODEL_TAGS} values. */
    String modelTag(String model) {
        if (modelTags.contains(model)) {
            return model;
        }
        // size() then add() races, so the set can overshoot by a few under
        // contention — the bound is what matters, not its exact value.
        if (modelTags.size() < MAX_MODEL_TAGS && modelTags.add(model)) {
            return model;
        }
        return modelTags.contains(model) ? model : OTHER_MODEL;
    }

    /** Provider error text fit for a span: secret-redacted and capped. */
    static String safeErrorText(String message) {
        if (message == null) {
            return UNKNOWN;
        }
        String redacted = SecretRedactionFilter.redact(message);
        return redacted.length() > MAX_ERROR_TEXT_LENGTH ? redacted.substring(0, MAX_ERROR_TEXT_LENGTH) + "…" : redacted;
    }

    /**
     * Containment for a failing telemetry callback.
     * <p>
     * Deliberately <em>not</em> a silent catch. langchain4j already contains this:
     * {@code ChatModelListenerUtils.onRequest/onResponse/onError} each wrap every
     * listener in {@code try/catch (Exception)} and log a WARN, so a throw from
     * here can never fail the turn. An earlier revision of this class caught and
     * logged at DEBUG "because langchain4j does not guarantee containment", which
     * was doubly wrong: the guarantee exists, and the catch downgraded upstream's
     * WARN to DEBUG — so a misconfigured {@code MeterRegistry} would have lost
     * every LLM meter and span with nothing in the log at default levels.
     * <p>
     * What is kept is the log level. Telemetry that has stopped working is an
     * operational problem, and it should be as visible as upstream would have made
     * it.
     */
    private void guard(Runnable work) {
        try {
            work.run();
        } catch (Exception e) {
            LOGGER.warnf(e, "LLM telemetry failed; the chat call itself is unaffected");
        }
    }
}
