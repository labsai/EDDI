/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.guardrails.impl;

import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.modules.llm.guardrails.ContentGuardrail;
import ai.labs.eddi.modules.llm.guardrails.GuardrailContext;
import ai.labs.eddi.modules.llm.guardrails.GuardrailPhase;
import ai.labs.eddi.modules.llm.guardrails.GuardrailUndecidedException;
import ai.labs.eddi.modules.llm.guardrails.GuardrailVerdict;
import ai.labs.eddi.modules.llm.impl.ModelOutputParser;
import ai.labs.eddi.utils.SafeJsonExpression;
import ai.labs.eddi.utils.SafeJsonExpression.EvaluationException;
import ai.labs.eddi.utils.SafeJsonExpression.SyntaxException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.enterprise.context.ApplicationScoped;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@code expression} — output only. A deterministic check over the model's
 * answer parsed as JSON: the author writes a boolean expression
 * ({@code sum(lines[*].amount) == total}), an issue code and the fix text a
 * reprompt sends when it is false. The evaluator is {@link SafeJsonExpression}
 * — a small sandboxed recursive-descent language (paths, BigDecimal arithmetic,
 * comparisons, logic and a fixed set of functions), no script engine.
 * <h3>Parameters (plain strings, never templated)</h3>
 * <ul>
 * <li>{@code expr} — the expression (required);</li>
 * <li>{@code issue} — the issue code reported when it is false, upper-case
 * letters, digits and underscores (required);</li>
 * <li>{@code fix} — the sentence a reprompt tells the model (required, at most
 * {@value #MAX_FIX_TEXT_CHARS} characters).</li>
 * </ul>
 * All three are checked at deploy time ({@link #validate}); a bad expression is
 * refused with the position of the problem.
 * <h3>Verdicts</h3>
 * <ul>
 * <li>{@code true} — the answer passes;</li>
 * <li>{@code false} — a {@code VIOLATION} with reason
 * {@code expression_failed:<ISSUE>} and a reprompt hint made of EDDI's fixed
 * frame and the author's {@code fix} text, nothing else: no value computed from
 * the answer, nothing the model or the user wrote (closed vocabulary, as for
 * the judge guardrail);</li>
 * <li>an answer that is not JSON (code fence and surrounding prose are
 * tolerated) or is larger than {@value #MAX_ANSWER_CHARS} characters, or one
 * with a duplicated key — could not decide,
 * {@code expression_unparseable};</li>
 * <li>an evaluation error (null or type mismatch in arithmetic, division by
 * zero, a bound exceeded) or a result that is not a boolean — could not decide,
 * {@code expression_undecided:<cause>}, where the cause is one of the
 * evaluator's own codes.</li>
 * </ul>
 * Could not decide is handled by {@code failMode}: closed (block) by default
 * for {@code block} and {@code reprompt}.
 */
@ApplicationScoped
public class ExpressionGuardrail implements ContentGuardrail {

    public static final String TYPE = "expression";

    /** Reason prefix of a false expression; followed by {@code :<ISSUE>}. */
    public static final String REASON_FAILED = "expression_failed";
    public static final String REASON_UNPARSEABLE = "expression_unparseable";
    /** Reason prefix of an evaluation error; followed by {@code :<cause>}. */
    public static final String REASON_UNDECIDED = "expression_undecided";
    /** The parameters are unusable (a config that was never validated). */
    public static final String REASON_INVALID = "expression_invalid";
    static final String CAUSE_NOT_BOOLEAN = "not_boolean";

    static final String PARAM_EXPR = "expr";
    static final String PARAM_ISSUE = "issue";
    static final String PARAM_FIX = "fix";

    static final int MAX_FIX_TEXT_CHARS = 1000;
    /** A reply longer than this is not parsed. */
    static final int MAX_ANSWER_CHARS = 262_144;
    /** Distinct expressions kept compiled. */
    static final int MAX_CACHED = 512;

    static final Pattern CODE = Pattern.compile("[A-Z][A-Z0-9_]{0,39}");

    /**
     * The corrective message's frame — the same wording as the judge guardrail's;
     * the line between is the author's fix text.
     */
    static final String HINT_HEADER = "Your previous reply needs these corrections:";
    static final String HINT_FOOTER = "Write a new reply that applies them. Reply with the answer only.";

    /** An expression compiled, or why it does not compile. */
    record Compiled(SafeJsonExpression expression, String problem) {
    }

    private final ModelOutputParser parser = new ModelOutputParser(new ExactJson(true));

    /**
     * The reading the rest of the task uses — the default mapper: the last of two
     * repeated keys wins, numbers are {@code double}s. See {@link #parseAnswer}.
     */
    private final ModelOutputParser lenientParser = new ModelOutputParser(new ExactJson(false));
    /**
     * Bounded, keyed by the expression text: configuration-derived data only, so
     * the bean stays stateless with respect to conversations.
     */
    private final Cache<String, Compiled> compiled = Caffeine.newBuilder().maximumSize(MAX_CACHED).executor(Runnable::run).build();

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Set<GuardrailPhase> phases() {
        return Set.of(GuardrailPhase.OUTPUT, GuardrailPhase.TOOL_CALL);
    }

    /** Output only; there is no history to screen. */
    @Override
    public boolean screensHistory() {
        return false;
    }

    @Override
    public String validate(Map<String, String> parameters) {
        Map<String, String> p = parameters != null ? parameters : Map.of();
        String expr = p.get(PARAM_EXPR);
        if (expr == null || expr.isBlank()) {
            return "expression guardrail: parameter 'expr' is required";
        }
        Compiled c = compile(expr);
        if (c.problem() != null) {
            return "expression guardrail: parameter 'expr' " + c.problem();
        }
        String issue = p.get(PARAM_ISSUE);
        if (issue == null || !CODE.matcher(issue).matches()) {
            return "expression guardrail: parameter 'issue' is required: an issue code of upper-case letters, digits and underscores, starting with a letter, at most 40 characters";
        }
        String fix = p.get(PARAM_FIX);
        if (fix == null || fix.isBlank()) {
            return "expression guardrail: parameter 'fix' is required: the sentence a reprompt tells the model";
        }
        if (fix.length() > MAX_FIX_TEXT_CHARS) {
            return "expression guardrail: parameter 'fix' is longer than " + MAX_FIX_TEXT_CHARS + " characters";
        }
        return null;
    }

    @Override
    public GuardrailVerdict evaluate(String text, GuardrailContext context) {
        Map<String, String> p = context.parameters();
        if (validate(p) != null) {
            throw new GuardrailUndecidedException(REASON_INVALID, 0.0);
        }
        SafeJsonExpression expression = compile(p.get(PARAM_EXPR)).expression();
        Object root = parseAnswer(text);
        Object result;
        try {
            result = expression.evaluate(root);
        } catch (EvaluationException e) {
            throw new GuardrailUndecidedException(REASON_UNDECIDED + ":" + e.code(), 0.0);
        }
        if (!(result instanceof Boolean passed)) {
            throw new GuardrailUndecidedException(REASON_UNDECIDED + ":" + CAUSE_NOT_BOOLEAN, 0.0);
        }
        if (passed) {
            return GuardrailVerdict.allow();
        }
        return GuardrailVerdict.violation(REASON_FAILED + ":" + p.get(PARAM_ISSUE), repromptHint(p.get(PARAM_FIX)));
    }

    /**
     * The corrective message: EDDI's frame around the author's fix text — a closed
     * vocabulary, never the answer's or the user's words, never a computed value.
     */
    static String repromptHint(String fix) {
        return HINT_HEADER + "\n- " + fix.strip() + "\n" + HINT_FOOTER;
    }

    /** The compiled form of an expression, from the bounded cache. */
    Compiled compile(String expr) {
        return compiled.get(expr, ExpressionGuardrail::compileUncached);
    }

    private static Compiled compileUncached(String expr) {
        try {
            return new Compiled(SafeJsonExpression.compile(expr), null);
        } catch (SyntaxException e) {
            return new Compiled(null, "is not valid at position " + e.position() + ": " + e.getMessage());
        }
    }

    /** For tests: how many compiled expressions the cache holds. */
    long cachedExpressions() {
        compiled.cleanUp();
        return compiled.estimatedSize();
    }

    private Object parseAnswer(String text) {
        if (text == null || text.isBlank() || text.length() > MAX_ANSWER_CHARS) {
            throw new GuardrailUndecidedException(REASON_UNPARSEABLE, 0.0);
        }
        ModelOutputParser.JsonOutcome outcome = parser.parse(text, true);
        if (outcome.kind() != ModelOutputParser.Kind.VALID || !(outcome.value() instanceof Map || outcome.value() instanceof List)) {
            throw new GuardrailUndecidedException(REASON_UNPARSEABLE, 0.0);
        }
        // The value judged must be the value the task goes on to use. Extraction
        // takes the first candidate that parses, so a strict reading skips an
        // earlier object with a repeated key ({"total":1,"total":999} {"total":1})
        // and approves the later one — while the task's own conversion, which keeps
        // the last of two repeated keys, takes the earlier object and stores 999.
        // Read the answer the tolerant way too: two readings that disagree are an
        // ambiguous answer, undecided, never an approval. The tolerant reading is the
        // downstream one in its number handling as well: with exact numbers on both
        // sides, a value only exact parsing refuses (1e2147483648, Infinity to a
        // double) made both readings skip the same earlier object and agree on a
        // later one, while the task's conversion took the earlier.
        ModelOutputParser.JsonOutcome lenient = lenientParser.parse(text, true);
        if (lenient.kind() != ModelOutputParser.Kind.VALID || !sameJson(outcome.value(), lenient.value())) {
            throw new GuardrailUndecidedException(REASON_UNPARSEABLE, 0.0);
        }
        return outcome.value();
    }

    /**
     * Whether an exact reading and a {@code double} reading of an answer are the
     * same JSON value: same structure and keys, integers equal exactly, and each
     * decimal exactly the value of the {@code double} the downstream reading holds
     * — which must be finite. A decimal that underflows or loses digits on the way
     * to a double is not the value the task uses, so the answer is ambiguous.
     */
    static boolean sameJson(Object exact, Object downstream) {
        if (exact instanceof Map<?, ?> a && downstream instanceof Map<?, ?> b) {
            if (!a.keySet().equals(b.keySet())) {
                return false;
            }
            for (Map.Entry<?, ?> entry : a.entrySet()) {
                if (!sameJson(entry.getValue(), b.get(entry.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        if (exact instanceof List<?> a && downstream instanceof List<?> b) {
            if (a.size() != b.size()) {
                return false;
            }
            for (int i = 0; i < a.size(); i++) {
                if (!sameJson(a.get(i), b.get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (exact instanceof BigInteger a && downstream instanceof Number b && !(b instanceof Double || b instanceof Float)) {
            return a.equals(new BigInteger(b.toString()));
        }
        if (exact instanceof BigDecimal a && (downstream instanceof Double || downstream instanceof Float)) {
            double d = ((Number) downstream).doubleValue();
            // Against the double's own decimal value, not a.doubleValue(): 1e-400 and 0.0
            // are the same double, but the guardrail would judge a positive number the
            // task then reads as zero.
            return Double.isFinite(d) && a.compareTo(BigDecimal.valueOf(d)) == 0;
        }
        if (exact instanceof Number || downstream instanceof Number) {
            return false;
        }
        return Objects.equals(exact, downstream);
    }

    /**
     * The JSON reading for the answer: decimals as {@code BigDecimal} and integers
     * as {@code BigInteger}, so a money amount is compared exactly, and a repeated
     * key is an error — a reply whose {@code total} reads one way here and another
     * way downstream must not pass.
     */
    static final class ExactJson implements IJsonSerialization {
        private static final ObjectMapper STRICT = JsonMapper.builder()
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION).build();
        private static final ObjectMapper LENIENT = JsonMapper.builder().build();

        private final ObjectMapper mapper;

        /**
         * @param strictDuplicates
         *            whether this is the guardrail's own reading (exact numbers, a
         *            repeated key is an error) or the reading the rest of the task uses
         *            (the default mapper: doubles, the last repeated key wins)
         */
        ExactJson(boolean strictDuplicates) {
            this.mapper = strictDuplicates ? STRICT : LENIENT;
        }

        ExactJson() {
            this(true);
        }

        @Override
        public String serialize(Object model) throws IOException {
            return mapper.writeValueAsString(model);
        }

        @Override
        public <T> T deserialize(String json) throws IOException {
            @SuppressWarnings("unchecked")
            T value = (T) mapper.readValue(json, Object.class);
            return value;
        }

        @Override
        public <T> T deserialize(String json, Class<T> type) throws IOException {
            return mapper.readValue(json, type);
        }
    }
}
