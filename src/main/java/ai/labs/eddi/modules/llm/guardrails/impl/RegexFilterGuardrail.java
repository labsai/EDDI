/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.guardrails.impl;

import ai.labs.eddi.modules.llm.guardrails.ContentGuardrail;
import ai.labs.eddi.modules.llm.guardrails.GuardrailContext;
import ai.labs.eddi.modules.llm.guardrails.GuardrailPhase;
import ai.labs.eddi.modules.llm.guardrails.GuardrailVerdict;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
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
 * them free of nested quantifiers. A pattern that is not valid is ignored with
 * a WARN.
 */
@ApplicationScoped
public class RegexFilterGuardrail implements ContentGuardrail {

    public static final String TYPE = "regex-filter";
    static final String DEFAULT_REPLACEMENT = "[REDACTED]";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Set<GuardrailPhase> phases() {
        return Set.of(GuardrailPhase.INPUT, GuardrailPhase.OUTPUT);
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
        for (Pattern pattern : patterns) {
            Matcher matcher = pattern.matcher(result);
            if (matcher.find()) {
                matched = true;
                result = matcher.replaceAll(replacement);
            }
        }
        return matched ? GuardrailVerdict.rewrite(result, "pattern_match") : GuardrailVerdict.allow();
    }
}
