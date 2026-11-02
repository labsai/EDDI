/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.guardrails.impl;

import ai.labs.eddi.modules.llm.guardrails.ContentGuardrail;
import ai.labs.eddi.modules.llm.guardrails.GuardrailContext;
import ai.labs.eddi.modules.llm.guardrails.GuardrailPhase;
import ai.labs.eddi.modules.llm.guardrails.GuardrailUndecidedException;
import ai.labs.eddi.modules.llm.guardrails.GuardrailVerdict;
import ai.labs.eddi.utils.RegexDeadline;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * {@code prompt-injection} — input only. Flags a message that matches one of a
 * list of instruction-override phrasings ("ignore all previous instructions",
 * "reveal your system prompt", "you are now in developer mode", ...).
 * <p>
 * A heuristic, not a classifier: it catches the common verbatim attacks and
 * nothing paraphrased or encoded. The list is data, not code — parameters:
 * <ul>
 * <li>{@code patterns} — extra regular expressions, one per line,
 * case-insensitive;</li>
 * <li>{@code mode} — {@code append} (default: the built-in list plus
 * {@code patterns}) or {@code replace} (only {@code patterns}).</li>
 * </ul>
 * All patterns together get {@link #MATCH_BUDGET_MS} per text; past it the text
 * is undecided ({@code pattern_timeout}) and {@code failMode} applies — closed
 * for {@code block}, so an author pattern that backtracks refuses the text
 * rather than letting it through. An invalid pattern fails the deploy.
 */
@ApplicationScoped
public class PromptInjectionGuardrail implements ContentGuardrail {

    private static final Logger LOGGER = Logger.getLogger(PromptInjectionGuardrail.class);

    public static final String TYPE = "prompt-injection";

    /** How long all patterns together may run over one text. */
    static final long MATCH_BUDGET_MS = 250;

    /**
     * The built-in list. Each needs an imperative override shape, so ordinary text
     * that merely mentions instructions or prompts does not match.
     * <p>
     * Whitespace runs are possessive ({@code \s++}, {@code \s*+}). These patterns
     * screen raw user text, and two greedy runs with nothing but optional words
     * between them can divide one long whitespace run between them in every way:
     * {@code ignore} followed by 100 000 spaces and no instruction word was
     * quadratic, as was {@code <} followed by spaces. Possessive changes no match
     * here: whatever follows each run starts with a non-whitespace character or is
     * itself an optional whitespace run, so a run never has to give anything back.
     */
    static final List<String> DEFAULT_PATTERNS = List.of(
            "\\b(ignore|disregard|forget|override|bypass)\\s++(all\\s++|any\\s++|the\\s++|your\\s++)?(of\\s++)?(the\\s++|your\\s++)?(previous|prior|above|earlier|preceding|system|original|initial)?\\s*+(instructions?|prompts?|rules|directives|guidelines)\\b",
            "\\b(reveal|print|show|repeat|output|display|leak)\\b[^.\\n]{0,30}\\b(your|the)\\b[^.\\n]{0,20}\\b(system prompt|system message|initial instructions|hidden instructions)\\b",
            "\\byou are now\\b[^.\\n]{0,40}\\b(dan|developer mode|jailbroken|unrestricted|unfiltered|no longer bound)\\b",
            "\\b(enter|enable|activate|switch to)\\b[^.\\n]{0,20}\\b(developer mode|dan mode|jailbreak mode|god mode)\\b",
            "\\bpretend\\b[^.\\n]{0,40}\\b(no|without)\\b[^.\\n]{0,20}\\b(restrictions|rules|guidelines|filters)\\b",
            "\\bnew instructions?\\s*:",
            "<\\s*+/?\\s*+(system|im_start|im_end)\\s*+>",
            "\\[\\s*+(system|inst)\\s*+\\]");

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Set<GuardrailPhase> phases() {
        return Set.of(GuardrailPhase.INPUT);
    }

    /**
     * Refuses a deploy whose {@code patterns} holds a line that does not compile.
     */
    @Override
    public String validate(Map<String, String> parameters) {
        String invalid = PatternCache.firstInvalidLine(parameters != null ? parameters.get("patterns") : null);
        return invalid != null ? "prompt-injection guardrail: " + invalid : null;
    }

    @Override
    public GuardrailVerdict evaluate(String text, GuardrailContext context) {
        long deadline = RegexDeadline.deadlineIn(MATCH_BUDGET_MS);
        try {
            for (Pattern pattern : patterns(context)) {
                if (RegexDeadline.matcher(pattern, text, deadline).find()) {
                    return GuardrailVerdict.violation("prompt_injection_pattern");
                }
            }
        } catch (RegexDeadline.MatchDeadlineExceededException e) {
            LOGGER.warnf("prompt-injection patterns of task '%s' exceeded their %d ms budget — the text is undecided", context.taskId(),
                    MATCH_BUDGET_MS);
            throw new GuardrailUndecidedException(RegexFilterGuardrail.REASON_TIMEOUT, 0.0);
        }
        return GuardrailVerdict.allow();
    }

    private static List<Pattern> patterns(GuardrailContext context) {
        List<Pattern> patterns = new ArrayList<>();
        if (!"replace".equalsIgnoreCase(context.parameter("mode", "append"))) {
            for (String source : DEFAULT_PATTERNS) {
                PatternCache.compile(source).ifPresent(patterns::add);
            }
        }
        patterns.addAll(PatternCache.compileLines(context.parameter("patterns", null)));
        return patterns;
    }
}
