/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.guardrails.impl;

import ai.labs.eddi.configs.shared.TurnDeadline;
import ai.labs.eddi.modules.llm.guardrails.ContentGuardrail;
import ai.labs.eddi.modules.llm.guardrails.GuardrailContext;
import ai.labs.eddi.modules.llm.guardrails.GuardrailPhase;
import ai.labs.eddi.modules.llm.guardrails.GuardrailUndecidedException;
import ai.labs.eddi.modules.llm.guardrails.GuardrailVerdict;
import ai.labs.eddi.modules.llm.impl.ChatModelRegistry;
import ai.labs.eddi.modules.llm.impl.TokenPricing;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code classifier} — input and output. Asks a safety-classifier model for a
 * verdict on the text. The model is whatever the agent designer points it at
 * (Granite Guardian on vLLM, ShieldGemma on Ollama, a hosted moderation
 * endpoint, ...): EDDI ships and endorses none, and every model-specific detail
 * — prompt, labels, how to read the answer — is configuration.
 * <h3>Transport</h3> The call goes through {@link ChatModelRegistry} with the
 * same provider type and parameter vocabulary as an LLM task ({@code provider}
 * names the type; {@code baseUrl}, {@code apiKey}, {@code modelName} /
 * {@code model}, {@code customParameters}, ... pass through to the provider
 * builder unchanged). A classifier endpoint is the same trust class as an
 * author-configured LLM {@code baseUrl} and is typically in-cluster, which is
 * why this is not {@code SafeHttpClient}. Vault and global-variable references
 * in the parameters are resolved by the registry, as for a task.
 * <h3>Prompt</h3> Without {@code promptTemplate} the text is sent as the
 * model's native conversation — input: one user message; output: the user's
 * question and the answer as an assistant message — and the model's own chat
 * template builds its classification prompt. With {@code promptTemplate} the
 * template is rendered and sent as one user message. The template is author
 * configuration; the screened text is passed to it only as the data variables
 * {@code {input}}, {@code {output}}, {@code {context}} and {@code {risk}} — it
 * is never part of the template source, so braces in a user's message are text,
 * not expressions.
 * <h3>Verdict</h3> The label is the first capture group of the last
 * {@code labelPattern} match when that is set, otherwise the first word of the
 * answer. It is compared case-insensitively with {@code unsafeLabel} (default
 * {@code yes}) and {@code safeLabel} (default {@code no} when
 * {@code unsafeLabel} is the default; required otherwise). A text is safe only
 * when the label positively matches {@code safeLabel}; any other answer is
 * "could not decide".
 *
 * <h3>Could not decide</h3> An unreachable or slow classifier
 * ({@code classifier_unavailable}) or an answer that is neither label
 * ({@code classifier_unparseable}) is reported as a
 * {@link GuardrailUndecidedException}, so the guardrail's {@code failMode}
 * applies: {@code closed} — the default for a {@code block} or {@code reprompt}
 * guardrail — refuses the text, {@code open} records a warning and lets it
 * through.
 * <h3>History</h3> {@link #screensHistory()} is false: one model call per
 * earlier message of the window on every turn would multiply the cost by the
 * window size. Each message was classified on its own turn.
 */
@ApplicationScoped
public class ClassifierGuardrail implements ContentGuardrail {

    private static final Logger LOGGER = Logger.getLogger(ClassifierGuardrail.class);

    public static final String TYPE = "classifier";

    /** Timer of every classifier call; tags {@code provider, outcome}. */
    public static final String METRIC_DURATION = "eddi.guardrail.classifier.duration";

    public static final String REASON_FLAGGED = "classifier_flagged";
    public static final String REASON_UNAVAILABLE = "classifier_unavailable";
    public static final String REASON_UNPARSEABLE = "classifier_unparseable";

    static final String PARAM_PROVIDER = "provider";
    static final String PARAM_RISK = "risk";
    static final String PARAM_PROMPT_TEMPLATE = "promptTemplate";
    static final String PARAM_UNSAFE_LABEL = "unsafeLabel";
    static final String PARAM_SAFE_LABEL = "safeLabel";
    static final String PARAM_LABEL_PATTERN = "labelPattern";
    static final String PARAM_TIMEOUT_MS = "timeoutMs";
    static final String PARAM_INCLUDE_CONTEXT = "includeRetrievedContext";
    static final String PARAM_INPUT_PRICE = "inputPricePer1M";
    static final String PARAM_OUTPUT_PRICE = "outputPricePer1M";
    static final String PARAM_TIMEOUT = "timeout";

    static final String DEFAULT_UNSAFE_LABEL = "yes";
    /** The safe label assumed when {@code unsafeLabel} is left at its default. */
    static final String DEFAULT_SAFE_LABEL = "no";
    /**
     * How much of the classifier's answer label extraction looks at: the last
     * {@value} characters for a {@code labelPattern} (the verdict comes last), the
     * first {@value} for the first word. An author regex never runs over an
     * unexpectedly huge reply.
     */
    static final int MAX_LABEL_SCAN_CHARS = 4096;
    static final int DEFAULT_TIMEOUT_MS = 3000;
    /** Ceiling on {@code timeoutMs}: a classifier sits on every turn's path. */
    static final int MAX_TIMEOUT_MS = 60_000;

    /**
     * The guardrail's own keys (and the generic ones of every guardrail), which are
     * not passed to the provider builder.
     */
    static final Set<String> OWN_PARAMETERS = Set.of(PARAM_PROVIDER, PARAM_RISK, PARAM_PROMPT_TEMPLATE, PARAM_UNSAFE_LABEL, PARAM_SAFE_LABEL,
            PARAM_LABEL_PATTERN, PARAM_TIMEOUT_MS, PARAM_INCLUDE_CONTEXT, PARAM_INPUT_PRICE, PARAM_OUTPUT_PRICE, "failMode", "repromptMessage");

    /** A risk name short and plain enough to put into an audit reason. */
    private static final Pattern PLAIN_RISK = Pattern.compile("[A-Za-z0-9_.-]{1,40}");
    /**
     * Characters trimmed off a first-word label ({@code "Yes,"}, {@code "**No**"}).
     */
    private static final Pattern LABEL_EDGES = Pattern.compile("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    /**
     * Runs the call so the turn deadline can cut it short when it is tighter than
     * the provider timeout. Virtual threads: an abandoned call holds heap, not a
     * platform thread.
     */
    private static final ExecutorService EXECUTOR = Executors
            .newThreadPerTaskExecutor(Thread.ofVirtual().name("eddi-guardrail-classifier-", 0).factory());

    private final ChatModelRegistry chatModelRegistry;
    private final ITemplatingEngine templatingEngine;
    private final MeterRegistry meterRegistry;

    @Inject
    public ClassifierGuardrail(ChatModelRegistry chatModelRegistry, ITemplatingEngine templatingEngine, MeterRegistry meterRegistry) {
        this.chatModelRegistry = chatModelRegistry;
        this.templatingEngine = templatingEngine;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Set<GuardrailPhase> phases() {
        return Set.of(GuardrailPhase.INPUT, GuardrailPhase.OUTPUT);
    }

    /** A model call per earlier message on every turn is too expensive. */
    @Override
    public boolean screensHistory() {
        return false;
    }

    /**
     * A classifier must be able to tell "safe" apart from "anything else": with a
     * custom {@code unsafeLabel} and no {@code safeLabel} there is no answer that
     * means safe, so every turn would be undecided. Refused at deploy time.
     */
    @Override
    public String validate(Map<String, String> parameters) {
        Map<String, String> p = parameters != null ? parameters : Map.of();
        if (isBlank(p.get(PARAM_PROVIDER))) {
            return "classifier guardrail: parameter 'provider' is required (an LLM provider type such as 'openai' or 'ollama')";
        }
        if (!isBlank(p.get(PARAM_UNSAFE_LABEL)) && !DEFAULT_UNSAFE_LABEL.equalsIgnoreCase(p.get(PARAM_UNSAFE_LABEL).trim())
                && isBlank(p.get(PARAM_SAFE_LABEL))) {
            return "classifier guardrail: parameter 'safeLabel' is required when 'unsafeLabel' is set to anything but 'yes' — "
                    + "an answer is only treated as safe when it matches safeLabel";
        }
        return null;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @Override
    public GuardrailVerdict evaluate(String text, GuardrailContext context) {
        String provider = context.parameter(PARAM_PROVIDER, null);
        if (provider == null) {
            LOGGER.warnf("Classifier guardrail of task '%s' has no 'provider' parameter", context.taskId());
            throw new GuardrailUndecidedException(REASON_UNAVAILABLE, 0.0);
        }
        long started = System.nanoTime();
        String outcome = "unavailable";
        String providerTag = "unknown";
        try {
            List<ChatMessage> messages = buildMessages(text, context);
            ChatModel model = chatModelRegistry.getOrCreate(provider.trim(), modelParameters(context));
            providerTag = provider.trim();
            ChatResponse response = call(model, ChatRequest.builder().messages(messages).build(), context);
            double cost = cost(response, context);
            String answer = response != null && response.aiMessage() != null ? response.aiMessage().text() : null;
            Label label = classify(answer, context);
            outcome = label.name().toLowerCase(Locale.ROOT);
            return switch (label) {
                case UNSAFE -> GuardrailVerdict.violation(flaggedReason(context)).withCost(cost);
                case SAFE -> GuardrailVerdict.allow().withCost(cost);
                case UNPARSEABLE -> throw new GuardrailUndecidedException(REASON_UNPARSEABLE, cost);
            };
        } catch (ChatModelRegistry.UnsupportedLlmTaskException e) {
            providerTag = "unsupported";
            LOGGER.warnf("Classifier guardrail of task '%s' names an unsupported provider", context.taskId());
            throw new GuardrailUndecidedException(REASON_UNAVAILABLE, 0.0);
        } catch (ClassifierTimeoutException e) {
            outcome = "timeout";
            throw new GuardrailUndecidedException(REASON_UNAVAILABLE, 0.0);
        } catch (GuardrailUndecidedException e) {
            throw e;
        } catch (RuntimeException e) {
            // Never the provider's message: it can echo the request, i.e. the screened
            // text.
            LOGGER.warnf("Classifier guardrail of task '%s' could not reach its model (%s)", context.taskId(), e.getClass().getSimpleName());
            throw new GuardrailUndecidedException(REASON_UNAVAILABLE, 0.0);
        } finally {
            if (meterRegistry != null) {
                Timer.builder(METRIC_DURATION).tag("provider", providerTag).tag("outcome", outcome).register(meterRegistry)
                        .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
            }
        }
    }

    // === Request ===

    /**
     * The messages sent to the classifier. Throws {@link IllegalStateException} for
     * a template that does not render (an author error: could not decide).
     */
    List<ChatMessage> buildMessages(String text, GuardrailContext context) {
        String screened = text != null ? text : "";
        boolean output = context.phase() == GuardrailPhase.OUTPUT;
        String question = output ? lastUserText(context.messages()) : screened;
        String template = context.parameter(PARAM_PROMPT_TEMPLATE, null);
        if (template == null) {
            List<ChatMessage> messages = new ArrayList<>();
            if (output) {
                if (!question.isEmpty()) {
                    messages.add(UserMessage.from(question));
                }
                messages.add(AiMessage.from(screened));
            } else {
                messages.add(UserMessage.from(screened));
            }
            return messages;
        }
        Map<String, Object> data = new HashMap<>();
        data.put("input", question);
        data.put("output", output ? screened : "");
        data.put("risk", context.parameter(PARAM_RISK, ""));
        data.put("context", output && includeContext(context) && context.retrievedContext() != null ? context.retrievedContext() : "");
        try {
            String prompt = templatingEngine.processTemplate(template, data);
            if (prompt == null || prompt.isBlank()) {
                throw new IllegalStateException("empty classifier prompt");
            }
            return List.of(UserMessage.from(prompt));
        } catch (ITemplatingEngine.TemplateEngineException e) {
            LOGGER.warnf("Classifier guardrail of task '%s': promptTemplate does not render", context.taskId());
            throw new IllegalStateException("classifier promptTemplate does not render");
        }
    }

    /**
     * The provider parameters: every parameter that is not the guardrail's own,
     * plus {@code timeout} from {@code timeoutMs} so the provider's HTTP client
     * gives up as well. Stable per configuration, so the registry caches one model.
     */
    static Map<String, String> modelParameters(GuardrailContext context) {
        Map<String, String> params = new HashMap<>();
        context.parameters().forEach((key, value) -> {
            if (!OWN_PARAMETERS.contains(key)) {
                params.put(key, value);
            }
        });
        params.put(PARAM_TIMEOUT, Long.toString(timeoutMs(context)));
        return params;
    }

    static long timeoutMs(GuardrailContext context) {
        int configured = context.intParameter(PARAM_TIMEOUT_MS, DEFAULT_TIMEOUT_MS);
        if (configured <= 0) {
            configured = DEFAULT_TIMEOUT_MS;
        }
        return Math.min(configured, MAX_TIMEOUT_MS);
    }

    /**
     * How long this call may take: {@code timeoutMs}, or less when the turn's
     * deadline (after its reserve) is closer.
     */
    static long budgetMs(GuardrailContext context) {
        long budget = timeoutMs(context);
        TurnDeadline deadline = context.deadline();
        if (deadline != null) {
            budget = Math.min(budget, deadline.remainingAfterReserveMs());
        }
        return budget;
    }

    private ChatResponse call(ChatModel model, ChatRequest request, GuardrailContext context) {
        long budget = budgetMs(context);
        if (budget <= 0) {
            LOGGER.infof("Classifier guardrail of task '%s' skipped: the turn is out of time", context.taskId());
            throw new ClassifierTimeoutException();
        }
        Future<ChatResponse> future = EXECUTOR.submit(() -> model.chat(request));
        try {
            return future.get(budget, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            LOGGER.infof("Classifier guardrail of task '%s' timed out after %dms", context.taskId(), budget);
            throw new ClassifierTimeoutException();
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new ClassifierTimeoutException();
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("classifier call failed", cause);
        }
    }

    // === Verdict ===

    enum Label {
        SAFE, UNSAFE, UNPARSEABLE
    }

    /** Reads the label out of the classifier's answer. */
    static Label classify(String answer, GuardrailContext context) {
        String label = extractLabel(answer, context.parameter(PARAM_LABEL_PATTERN, null));
        if (label == null || label.isEmpty()) {
            return Label.UNPARSEABLE;
        }
        String unsafe = context.parameter(PARAM_UNSAFE_LABEL, DEFAULT_UNSAFE_LABEL).trim();
        if (label.equalsIgnoreCase(unsafe)) {
            return Label.UNSAFE;
        }
        // Safe only on a positive match (fail closed, Pillar 4): a refusal, an error
        // page, "maybe" or a truncated reply is undecided, and failMode applies.
        String safe = safeLabel(context, unsafe);
        return safe != null && label.equalsIgnoreCase(safe) ? Label.SAFE : Label.UNPARSEABLE;
    }

    /**
     * The configured {@code safeLabel}; {@code no} when {@code unsafeLabel} is the
     * default {@code yes}; otherwise null — nothing counts as safe (and
     * {@link #validate} refuses such a configuration at deploy time).
     */
    static String safeLabel(GuardrailContext context, String unsafe) {
        String safe = context.parameter(PARAM_SAFE_LABEL, null);
        if (safe != null) {
            return safe.trim();
        }
        return DEFAULT_UNSAFE_LABEL.equalsIgnoreCase(unsafe) ? DEFAULT_SAFE_LABEL : null;
    }

    /**
     * The label: the first group (or the whole match) of the LAST
     * {@code labelPattern} match — a reasoning trace before the verdict may mention
     * a label itself — or the first word of the answer with surrounding punctuation
     * removed. Null when nothing is found.
     */
    static String extractLabel(String answer, String labelPattern) {
        if (answer == null || answer.isBlank()) {
            return null;
        }
        if (labelPattern != null) {
            Optional<Pattern> pattern = PatternCache.compile(labelPattern);
            if (pattern.isEmpty()) {
                return null;
            }
            String tail = answer.length() > MAX_LABEL_SCAN_CHARS ? answer.substring(answer.length() - MAX_LABEL_SCAN_CHARS) : answer;
            Matcher matcher = pattern.get().matcher(tail);
            String found = null;
            while (matcher.find()) {
                found = matcher.groupCount() >= 1 && matcher.group(1) != null ? matcher.group(1) : matcher.group();
            }
            return found != null ? found.trim() : null;
        }
        String head = answer.length() > MAX_LABEL_SCAN_CHARS ? answer.substring(0, MAX_LABEL_SCAN_CHARS) : answer;
        String first = WHITESPACE.split(head.strip(), 2)[0];
        return LABEL_EDGES.matcher(first).replaceAll("");
    }

    private static String flaggedReason(GuardrailContext context) {
        String risk = context.parameter(PARAM_RISK, null);
        return risk != null && PLAIN_RISK.matcher(risk.trim()).matches() ? REASON_FLAGGED + ":" + risk.trim() : REASON_FLAGGED;
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

    private static boolean includeContext(GuardrailContext context) {
        return Boolean.parseBoolean(context.parameter(PARAM_INCLUDE_CONTEXT, "false").trim());
    }

    private static String lastUserText(List<ChatMessage> messages) {
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
    private static final class ClassifierTimeoutException extends RuntimeException {
        ClassifierTimeoutException() {
            super("classifier timed out", null, false, false);
        }
    }
}
