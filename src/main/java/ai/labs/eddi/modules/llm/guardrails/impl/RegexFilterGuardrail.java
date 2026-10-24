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

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code regex-filter} — input and output. The agent designer's own patterns:
 * any match is a finding, reported as a rewrite with every match replaced. With
 * action {@code redact} the rewrite is applied; with {@code block},
 * {@code reprompt} or {@code warn} a match counts as a violation. Parameters:
 * <ul>
 * <li>{@code patterns} — regular expressions, one per line, case-insensitive
 * (required; without any the guardrail never fires);</li>
 * <li>{@code replacement} — what a match is replaced with (default
 * {@code [REDACTED]}).</li>
 * </ul>
 * The patterns are author configuration and run against untrusted text: keep
 * them free of nested quantifiers. All of them together get
 * {@link #MATCH_BUDGET_MS} per text; past it the text is undecided
 * ({@code pattern_timeout}) and the guardrail's {@code failMode} applies — a
 * {@code block} guardrail refuses the text, it never lets it pass unchecked.
 * The rewrites of the patterns that finished are reported with it, so failing
 * open a {@code redact} keeps them (and a {@code block} that already matched
 * still blocks). A pattern that is not a valid regular expression fails the
 * deploy ({@link #validate}).
 */
@ApplicationScoped
public class RegexFilterGuardrail implements ContentGuardrail {

    private static final Logger LOGGER = Logger.getLogger(RegexFilterGuardrail.class);

    public static final String TYPE = "regex-filter";
    static final String DEFAULT_REPLACEMENT = "[REDACTED]";

    /** The audit reason of a text the patterns could not finish within budget. */
    public static final String REASON_TIMEOUT = "pattern_timeout";

    /** How long all patterns together may run over one text. */
    static final long MATCH_BUDGET_MS = 250;

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Set<GuardrailPhase> phases() {
        return Set.of(GuardrailPhase.INPUT, GuardrailPhase.OUTPUT);
    }

    /**
     * Refuses a deploy whose {@code patterns} holds a line that does not compile.
     */
    @Override
    public String validate(Map<String, String> parameters) {
        String invalid = PatternCache.firstInvalidLine(parameters != null ? parameters.get("patterns") : null);
        return invalid != null ? "regex-filter guardrail: " + invalid : null;
    }

    @Override
    public GuardrailVerdict evaluate(String text, GuardrailContext context) {
        List<Pattern> patterns = PatternCache.compileLines(context.parameter("patterns", null));
        if (patterns.isEmpty()) {
            return GuardrailVerdict.allow();
        }
        String replacement = Matcher.quoteReplacement(context.parameter("replacement", DEFAULT_REPLACEMENT));
        String result = text;
        boolean matched = false;
        long deadline = RegexDeadline.deadlineIn(MATCH_BUDGET_MS);
        try {
            for (Pattern pattern : patterns) {
                Matcher matcher = RegexDeadline.matcher(pattern, result, deadline);
                if (matcher.find()) {
                    String rewritten = matcher.replaceAll(replacement);
                    matched = true;
                    result = rewritten;
                }
            }
        } catch (RegexDeadline.MatchDeadlineExceededException e) {
            LOGGER.warnf("regex-filter patterns of task '%s' exceeded their %d ms budget — the text is undecided", context.taskId(),
                    MATCH_BUDGET_MS);
            throw new GuardrailUndecidedException(REASON_TIMEOUT, 0.0, matched ? GuardrailVerdict.rewrite(result, REASON_TIMEOUT) : null);
        }
        return matched ? GuardrailVerdict.rewrite(result, "pattern_match") : GuardrailVerdict.allow();
    }
}
