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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code pii-redaction} — input and output. Finds e-mail addresses, US social
 * security numbers, payment card numbers (Luhn-checked) and phone numbers and
 * reports a rewrite with each one replaced by {@code [REDACTED:<kind>]}.
 * <p>
 * With action {@code redact} the rewrite is applied; with {@code block},
 * {@code reprompt} or {@code warn} any finding counts as a violation of that
 * kind. Pattern-based: it finds well-formed identifiers, not names or
 * addresses, and is no substitute for a data-loss-prevention service.
 * Parameters:
 * <ul>
 * <li>{@code patterns} — comma-separated subset of {@code email}, {@code ssn},
 * {@code creditcard}, {@code phone} (default: all four);</li>
 * <li>{@code replacement} — the replacement text; {@code {kind}} is replaced by
 * the kind (default {@code [REDACTED:{kind}]}).</li>
 * </ul>
 */
@ApplicationScoped
public class PiiRedactionGuardrail implements ContentGuardrail {

    public static final String TYPE = "pii-redaction";
    static final String DEFAULT_REPLACEMENT = "[REDACTED:{kind}]";

    /**
     * Applied in this order: card before phone, so a card number is not half-eaten
     * as a phone number.
     */
    private static final Map<String, Pattern> KINDS = new LinkedHashMap<>();

    static {
        KINDS.put("email", Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}"));
        KINDS.put("ssn", Pattern.compile("\\b(?!000|666|9\\d\\d)\\d{3}-(?!00)\\d{2}-(?!0000)\\d{4}\\b"));
        KINDS.put("creditcard", Pattern.compile("\\b\\d(?:[ -]?\\d){12,18}\\b"));
        KINDS.put("phone", Pattern.compile("(?<![\\w+(])(?:\\+\\d{1,3}[ -]?)?(?:\\(\\d{2,4}\\)[ -]?|\\d{2,4}[ -])\\d{3,4}[ -]?\\d{3,4}\\b"));
    }

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
        String replacement = context.parameter("replacement", DEFAULT_REPLACEMENT);
        String result = text;
        Set<String> found = new TreeSet<>();
        for (String kind : enabledKinds(context.parameter("patterns", null))) {
            String redacted = redact(result, kind, replacement);
            if (!redacted.equals(result)) {
                found.add(kind);
                result = redacted;
            }
        }
        if (found.isEmpty()) {
            return GuardrailVerdict.allow();
        }
        return GuardrailVerdict.rewrite(result, "pii:" + String.join(",", found));
    }

    private static List<String> enabledKinds(String parameter) {
        if (parameter == null || parameter.isBlank()) {
            return List.copyOf(KINDS.keySet());
        }
        Set<String> wanted = new TreeSet<>();
        for (String part : parameter.split(",")) {
            wanted.add(part.trim().toLowerCase(Locale.ROOT));
        }
        List<String> kinds = new ArrayList<>();
        for (String kind : KINDS.keySet()) {
            if (wanted.contains(kind)) {
                kinds.add(kind);
            }
        }
        return kinds;
    }

    private static String redact(String text, String kind, String replacementTemplate) {
        String replacement = replacementTemplate.replace("{kind}", kind);
        Matcher matcher = KINDS.get(kind).matcher(text);
        StringBuilder out = new StringBuilder();
        boolean any = false;
        while (matcher.find()) {
            if ("creditcard".equals(kind) && !luhn(matcher.group())) {
                continue;
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
            any = true;
        }
        if (!any) {
            return text;
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * The Luhn checksum — keeps order numbers and other long digit runs from
     * matching as cards.
     */
    static boolean luhn(String candidate) {
        int sum = 0;
        boolean doubleIt = false;
        int digits = 0;
        for (int i = candidate.length() - 1; i >= 0; i--) {
            char c = candidate.charAt(i);
            if (c < '0' || c > '9') {
                continue;
            }
            int d = c - '0';
            if (doubleIt) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubleIt = !doubleIt;
            digits++;
        }
        return digits >= 13 && sum % 10 == 0;
    }
}
