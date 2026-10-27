/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.guardrails.impl;

import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.modules.llm.capability.JsonResponseFormatPolicy;
import ai.labs.eddi.modules.llm.guardrails.ContentGuardrail;
import ai.labs.eddi.modules.llm.guardrails.GuardrailContext;
import ai.labs.eddi.modules.llm.guardrails.GuardrailPhase;
import ai.labs.eddi.modules.llm.guardrails.GuardrailUndecidedException;
import ai.labs.eddi.modules.llm.guardrails.GuardrailVerdict;
import ai.labs.eddi.modules.llm.impl.ChatModelRegistry;
import ai.labs.eddi.modules.llm.impl.ModelOutputParser;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ResponseFormat;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * {@code judge} — output only. Asks a model to grade the answer against the
 * author's criteria and to name what is wrong with it in a <b>closed
 * vocabulary</b>: the issue codes the guardrail declares in {@code issues},
 * each with the author's text telling the producing model how to fix it.
 * <h3>Machinery</h3> Shared with {@code classifier} through
 * {@link GuardrailModelClient}: the provider is built by the registry with the
 * provider parameters passed through (vault and {@code ${vars}} resolved),
 * {@code timeoutMs} bounded by the turn deadline, the author's
 * {@code promptTemplate} rendered with the screened text as data only
 * ({@code {input}}, {@code {output}}, {@code {context}}), the call priced, and
 * {@code failMode} deciding what "could not decide" does.
 * <h3>The answer format is EDDI's</h3> After the rendered template EDDI appends
 * a fixed instruction naming the allowed codes and the reply shape
 * {@code {"issues": ["CODE", ...]}} (an empty list means the answer passes).
 * The request asks for JSON per request where the provider supports it, with
 * the codes as an {@code enum} where the provider enforces a schema — decided
 * per request by {@link JsonResponseFormatPolicy}, never baked into the cached
 * model. The reply is read tolerantly (a code fence or prose around the object,
 * via {@link ModelOutputParser}).
 * <h3>Safe is a positive finding</h3> Only a parsed, well-formed, empty list
 * passes. A reply that is not that shape, or names a code the guardrail did not
 * declare, is could-not-decide ({@code judge_unparseable}), as is an
 * unreachable or slow judge ({@code judge_unavailable}); {@code failMode}
 * applies.
 * <h3>Feedback without injection</h3> A finding is a {@code VIOLATION} with
 * reason {@code judge_failed:<CODE>[,<CODE>…]} — the codes are author-declared,
 * so they are safe in audit records and metrics — and a reprompt hint made only
 * of the author's fix texts for those codes. Nothing the judge wrote other than
 * the codes, and nothing the user or the producing model wrote, ever reaches
 * the corrective message: the judge reads untrusted content (retrieved
 * documents, tool results, user input), so its free text is an injection path
 * into the producer.
 */
@ApplicationScoped
public class JudgeGuardrail implements ContentGuardrail {

    private static final Logger LOGGER = Logger.getLogger(JudgeGuardrail.class);

    public static final String TYPE = "judge";

    /** Timer of every judge call; tags {@code provider, outcome}. */
    public static final String METRIC_DURATION = "eddi.guardrail.judge.duration";

    /** Reason prefix of a finding; followed by {@code :<CODE>[,<CODE>…]}. */
    public static final String REASON_FAILED = "judge_failed";
    public static final String REASON_UNAVAILABLE = "judge_unavailable";
    public static final String REASON_UNPARSEABLE = "judge_unparseable";

    static final String PARAM_ISSUES = "issues";
    /** Per-guardrail override of the JSON request format: on, off or auto. */
    static final String PARAM_JSON_RESPONSE_FORMAT = "jsonResponseFormat";

    static final int DEFAULT_TIMEOUT_MS = 5000;
    static final int MAX_ISSUES = 20;
    static final int MAX_FIX_TEXT_CHARS = 1000;
    /** A judge reply longer than this is not a verdict; it is not parsed. */
    static final int MAX_ANSWER_CHARS = 16_384;
    /** The key of the reply object. */
    static final String ISSUES_FIELD = "issues";

    static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,39}");

    static final Set<String> OWN_PARAMETERS = Stream
            .concat(GuardrailModelClient.COMMON_PARAMETERS.stream(), Stream.of(PARAM_ISSUES, PARAM_JSON_RESPONSE_FORMAT))
            .collect(Collectors.toUnmodifiableSet());

    /** The corrective message's frame; every line between is author fix text. */
    static final String HINT_HEADER = "Your previous reply needs these corrections:";
    static final String HINT_FOOTER = "Write a new reply that applies them. Reply with the answer only.";

    private static final ObjectMapper STRICT = JsonMapper.builder().enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).build();
    private static final int MAX_CACHED = 256;
    /** {@code issues} text to its parsed form; unusable values are cached too. */
    private static final Map<String, Optional<Map<String, String>>> ISSUES_CACHE = new ConcurrentHashMap<>();

    private final GuardrailModelClient client;
    private final ModelOutputParser parser;

    @Inject
    public JudgeGuardrail(ChatModelRegistry chatModelRegistry, ITemplatingEngine templatingEngine, IJsonSerialization jsonSerialization,
            MeterRegistry meterRegistry) {
        this.client = new GuardrailModelClient("Judge guardrail", "eddi-guardrail-judge-", () -> Timer.builder(METRIC_DURATION), REASON_UNAVAILABLE,
                REASON_UNPARSEABLE,
                chatModelRegistry, templatingEngine, meterRegistry);
        this.parser = new ModelOutputParser(jsonSerialization);
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Set<GuardrailPhase> phases() {
        return Set.of(GuardrailPhase.OUTPUT);
    }

    /** Output only; and a model call per earlier message would be too expensive. */
    @Override
    public boolean screensHistory() {
        return false;
    }

    @Override
    public String validate(Map<String, String> parameters) {
        Map<String, String> p = parameters != null ? parameters : Map.of();
        if (GuardrailModelClient.isBlank(p.get(GuardrailModelClient.PARAM_PROVIDER))) {
            return "judge guardrail: parameter 'provider' is required (an LLM provider type such as 'openai' or 'gemini')";
        }
        if (GuardrailModelClient.isBlank(p.get(GuardrailModelClient.PARAM_PROMPT_TEMPLATE))) {
            return "judge guardrail: parameter 'promptTemplate' is required — it tells the judge what to check, with {input}, {output} and {context}";
        }
        String raw = p.get(PARAM_ISSUES);
        if (GuardrailModelClient.isBlank(raw)) {
            return "judge guardrail: parameter 'issues' is required — a JSON object of issue code to fix text";
        }
        return issuesProblem(raw);
    }

    @Override
    public List<String> warnings(Map<String, String> parameters) {
        if (!GuardrailModelClient.priced(parameters)) {
            return List.of("judge guardrail has no inputPricePer1M/outputPricePer1M — its calls are not counted in the turn's cost or its ceilings");
        }
        return List.of();
    }

    @Override
    public GuardrailVerdict evaluate(String text, GuardrailContext context) {
        Optional<Map<String, String>> parsedIssues = issues(context.parameter(PARAM_ISSUES, null));
        if (parsedIssues.isEmpty()) {
            LOGGER.warnf("Judge guardrail of task '%s' has no usable 'issues' parameter", context.taskId());
            throw new GuardrailUndecidedException(REASON_UNAVAILABLE, 0.0);
        }
        Map<String, String> issues = parsedIssues.get();
        return client.evaluate(context, new GuardrailModelClient.Call(OWN_PARAMETERS, DEFAULT_TIMEOUT_MS, () -> buildMessages(text, context, issues),
                (provider, modelParameters) -> responseFormat(provider, modelParameters, issues, context), (answer, cost) -> {
                    List<String> found = readIssues(answer, issues);
                    if (found == null) {
                        return GuardrailModelClient.Interpretation.unparseable();
                    }
                    if (found.isEmpty()) {
                        return new GuardrailModelClient.Interpretation("pass", GuardrailVerdict.allow().withCost(cost));
                    }
                    return new GuardrailModelClient.Interpretation("fail",
                            GuardrailVerdict.issues(found, REASON_FAILED + ":" + String.join(",", found), repromptHint(found, issues))
                                    .withCost(cost));
                }));
    }

    // === Request ===

    /**
     * One user message: the author's template rendered with the screened text as
     * data, then EDDI's own instruction. The instruction is appended after
     * rendering, so the author's fix texts are never template source either.
     */
    List<ChatMessage> buildMessages(String text, GuardrailContext context, Map<String, String> issues) {
        String template = context.parameter(GuardrailModelClient.PARAM_PROMPT_TEMPLATE, null);
        if (template == null) {
            throw new IllegalStateException("judge has no promptTemplate");
        }
        String prompt = client.render(template, GuardrailModelClient.templateData(text, context), context);
        return List.of(UserMessage.from(prompt + "\n\n" + instruction(issues)));
    }

    /** The fixed answer-format instruction EDDI appends to every judge prompt. */
    static String instruction(Map<String, String> issues) {
        StringBuilder text = new StringBuilder(
                "Grade the answer only with these issue codes. Each code applies when the answer needs the fix shown:\n");
        issues.forEach((code, fix) -> text.append("- ").append(code).append(": ").append(fix.strip()).append('\n'));
        text.append("Reply with only a JSON object of the form {\"issues\": [\"CODE\", ...]} listing every code above that applies, ")
                .append("and no other codes. If none applies, reply {\"issues\": []}.");
        return text.toString();
    }

    /**
     * JSON per request where the provider takes it, with the declared codes as an
     * enum where it enforces a schema; nothing where it does not (the instruction
     * still asks for JSON, and the reply is read tolerantly).
     * {@code jsonResponseFormat} ({@code on}/{@code off}/{@code auto}) overrides
     * the built-in provider matrix, as the task setting of that name does.
     */
    static ResponseFormat responseFormat(String provider, Map<String, String> modelParameters, Map<String, String> issues, GuardrailContext context) {
        return JsonResponseFormatPolicy
                .of(true, provider, context.parameter(PARAM_JSON_RESPONSE_FORMAT, null), responseSchema(issues), modelParameters).resolve(false);
    }

    /** {@code {"issues": [one of the codes, ...]}} as a JSON schema. */
    static String responseSchema(Map<String, String> issues) {
        ObjectNode schema = STRICT.createObjectNode();
        schema.put("type", "object");
        ObjectNode items = STRICT.createObjectNode();
        items.put("type", "string");
        ArrayNode codes = items.putArray("enum");
        issues.keySet().forEach(codes::add);
        ObjectNode list = STRICT.createObjectNode();
        list.put("type", "array");
        list.set("items", items);
        schema.putObject("properties").set(ISSUES_FIELD, list);
        schema.putArray("required").add(ISSUES_FIELD);
        schema.put("additionalProperties", false);
        return schema.toString();
    }

    // === Verdict ===

    /**
     * The declared codes the reply names, deduplicated, in declaration order; an
     * empty list for a pass; null when the reply is not the agreed shape or names a
     * code that was not declared. A code is matched case-insensitively and recorded
     * as declared.
     */
    List<String> readIssues(String answer, Map<String, String> issues) {
        if (answer == null || answer.isBlank() || answer.length() > MAX_ANSWER_CHARS) {
            return null;
        }
        ModelOutputParser.JsonOutcome outcome = parser.parse(answer, true);
        if (outcome.kind() != ModelOutputParser.Kind.VALID || !(outcome.value() instanceof Map<?, ?> reply)) {
            return null;
        }
        if (!(reply.get(ISSUES_FIELD) instanceof List<?> named)) {
            return null;
        }
        Map<String, String> byUpper = new LinkedHashMap<>();
        issues.keySet().forEach(code -> byUpper.put(code.toUpperCase(Locale.ROOT), code));
        Set<String> found = new LinkedHashSet<>();
        for (Object item : named) {
            if (!(item instanceof String code)) {
                return null;
            }
            String declared = byUpper.get(code.trim().toUpperCase(Locale.ROOT));
            if (declared == null) {
                // Not a code this guardrail declared: the judge did not answer within the
                // vocabulary, so it could not decide (Pillar 4) — never a pass.
                return null;
            }
            found.add(declared);
        }
        List<String> ordered = new ArrayList<>();
        issues.keySet().forEach(code -> {
            if (found.contains(code)) {
                ordered.add(code);
            }
        });
        return ordered;
    }

    /**
     * The corrective message: EDDI's frame around the author's fix texts for the
     * codes found — a closed vocabulary, never the judge's or the user's words.
     */
    static String repromptHint(List<String> found, Map<String, String> issues) {
        StringBuilder hint = new StringBuilder(HINT_HEADER).append('\n');
        for (String code : found) {
            hint.append("- ").append(issues.get(code).strip()).append('\n');
        }
        return hint.append(HINT_FOOTER).toString();
    }

    // === The issues parameter ===

    /**
     * {@code issues} as an ordered code → fix text map, or empty when it is
     * unusable ({@link #issuesProblem} says why).
     */
    static Optional<Map<String, String>> issues(String raw) {
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        Optional<Map<String, String>> cached = ISSUES_CACHE.get(raw);
        if (cached != null) {
            return cached;
        }
        if (ISSUES_CACHE.size() >= MAX_CACHED) {
            ISSUES_CACHE.clear();
        }
        Optional<Map<String, String>> parsed = issuesProblem(raw) == null ? Optional.of(parseIssues(raw)) : Optional.empty();
        ISSUES_CACHE.put(raw, parsed);
        return parsed;
    }

    /** What is wrong with an {@code issues} value, or null when it is usable. */
    static String issuesProblem(String raw) {
        JsonNode node;
        try {
            node = STRICT.readTree(raw);
        } catch (JsonProcessingException e) {
            return "judge guardrail: parameter 'issues' is not a JSON object (or repeats a code)";
        }
        if (node == null || !node.isObject()) {
            return "judge guardrail: parameter 'issues' must be a JSON object of issue code to fix text";
        }
        if (node.isEmpty() || node.size() > MAX_ISSUES) {
            return "judge guardrail: parameter 'issues' must declare between 1 and " + MAX_ISSUES + " codes";
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext();) {
            Map.Entry<String, JsonNode> entry = it.next();
            if (!CODE.matcher(entry.getKey()).matches()) {
                return "judge guardrail: issue code '" + shown(entry.getKey())
                        + "' must be upper-case letters, digits and underscores, start with a letter, at most 40 characters";
            }
            JsonNode fix = entry.getValue();
            if (!fix.isTextual() || fix.asText().isBlank()) {
                return "judge guardrail: issue code '" + entry.getKey() + "' needs a non-blank fix text";
            }
            if (fix.asText().length() > MAX_FIX_TEXT_CHARS) {
                return "judge guardrail: the fix text of issue code '" + entry.getKey() + "' is longer than " + MAX_FIX_TEXT_CHARS + " characters";
            }
        }
        return null;
    }

    private static Map<String, String> parseIssues(String raw) {
        try {
            JsonNode node = STRICT.readTree(raw);
            Map<String, String> issues = new LinkedHashMap<>();
            node.fields().forEachRemaining(entry -> issues.put(entry.getKey(), entry.getValue().asText()));
            return Collections.unmodifiableMap(issues);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("issues validated but does not parse", e);
        }
    }

    private static String shown(String code) {
        String cut = code.length() > 40 ? code.substring(0, 40) : code;
        return cut.replaceAll("[\\r\\n\\t]", "_");
    }
}
