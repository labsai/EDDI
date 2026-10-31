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

    /** Fewest and most digits a payment card number has. */
    private static final int CARD_MIN_DIGITS = 13;
    private static final int CARD_MAX_DIGITS = 19;

    /**
     * Applied in this order: card before phone, so a card number is not half-eaten
     * as a phone number.
     * <p>
     * Every repetition in these patterns is bounded, so a scan stays linear in the
     * text: an unbounded local part ({@code [..]+@}) made a long run of local-part
     * characters without an {@code @} cost quadratic time, on input as long as the
     * conversation input limit allows. The bounds are the RFC 5321 ones (64 for the
     * local part, 63 per domain label).
     */
    private static final Map<String, Pattern> KINDS = new LinkedHashMap<>();

    static {
        KINDS.put("email", Pattern.compile("[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9-]{1,63}(?:\\.[A-Za-z0-9-]{1,63}){0,8}\\.[A-Za-z]{2,63}"));
        KINDS.put("ssn", Pattern.compile("\\b(?!000|666|9\\d\\d)\\d{3}-(?!00)\\d{2}-(?!0000)\\d{4}\\b"));
        KINDS.put("creditcard", Pattern.compile("\\b\\d(?:[ -]?\\d){12,18}\\b"));
        // Grouped national and international forms, plus the unspaced international
        // (E.164) form "+15551234567". An unspaced number without "+" is left alone:
        // it cannot be told apart from an order or account number.
        KINDS.put("phone", Pattern.compile("(?<![\\w+(])(?:\\+\\d{1,3}[ -]?)?(?:\\(\\d{2,4}\\)[ -]?|\\d{2,4}[ -])\\d{3,4}[ -]?\\d{3,4}\\b"
                + "|(?<![\\w+])\\+\\d{8,15}\\b"));
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
        int copied = 0;
        boolean any = false;
        while (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();
            if ("creditcard".equals(kind)) {
                int[] card = cardWithin(text, start, end);
                if (card == null) {
                    continue;
                }
                start = card[0];
                end = card[1];
            }
            out.append(text, copied, start).append(replacement);
            copied = end;
            any = true;
        }
        if (!any) {
            return text;
        }
        out.append(text, copied, text.length());
        return out.toString();
    }

    /**
     * The card number inside a digit run the card pattern matched, as
     * {@code [start, end)} in {@code text}, or {@code null} if there is none.
     * <p>
     * The pattern is greedy, so a card followed by its CVV or expiry ("4111 1111
     * 1111 1111 123") matches as one run, which fails the Luhn check as a whole.
     * The card is then looked for among the shorter windows of the run that start
     * at its beginning or just after a separator and end at its end or just before
     * one; the longest Luhn-valid window of 13 to 19 digits wins, the earliest
     * among equals. Windows never split a digit group, so an unseparated long
     * number (an order or account number) is still only ever checked as a whole and
     * cannot become a card by chance.
     */
    static int[] cardWithin(String text, int runStart, int runEnd) {
        if (luhn(text.substring(runStart, runEnd))) {
            return new int[]{runStart, runEnd};
        }
        List<Integer> starts = new ArrayList<>();
        List<Integer> ends = new ArrayList<>();
        starts.add(runStart);
        for (int i = runStart; i < runEnd; i++) {
            char c = text.charAt(i);
            if (c == ' ' || c == '-') {
                ends.add(i);
                starts.add(i + 1);
            }
        }
        ends.add(runEnd);
        int[] best = null;
        int bestDigits = 0;
        for (int s : starts) {
            for (int e : ends) {
                if (e <= s) {
                    continue;
                }
                int digits = countDigits(text, s, e);
                if (digits < CARD_MIN_DIGITS || digits > CARD_MAX_DIGITS || digits <= bestDigits) {
                    continue;
                }
                if (luhn(text.substring(s, e))) {
                    best = new int[]{s, e};
                    bestDigits = digits;
                }
            }
        }
        return best;
    }

    private static int countDigits(String text, int start, int end) {
        int digits = 0;
        for (int i = start; i < end; i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') {
                digits++;
            }
        }
        return digits;
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
        return digits >= CARD_MIN_DIGITS && sum % 10 == 0;
    }
}
