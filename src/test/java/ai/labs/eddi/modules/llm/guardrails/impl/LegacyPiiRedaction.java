/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.guardrails.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A frozen copy of the {@code pii-redaction} rewrite as it was before the
 * guardrail moved onto the shared detectors — the oracle the golden tests hold
 * the new implementation to. Never change it to make a test pass: a difference
 * from this is a behaviour change for every agent that already uses the
 * guardrail.
 */
final class LegacyPiiRedaction {

    private static final Map<String, Pattern> KINDS = new LinkedHashMap<>();

    static {
        KINDS.put("email", Pattern.compile("[A-Za-z0-9._%+-]{1,64}@[A-Za-z0-9-]{1,63}(?:\\.[A-Za-z0-9-]{1,63}){0,8}\\.[A-Za-z]{2,63}"));
        KINDS.put("ssn", Pattern.compile("\\b(?!000|666|9\\d\\d)\\d{3}-(?!00)\\d{2}-(?!0000)\\d{4}\\b"));
        KINDS.put("creditcard", Pattern.compile("\\b\\d(?:[ -]?\\d){12,18}\\b"));
        KINDS.put("phone", Pattern.compile("(?<![\\w+(])(?:\\+\\d{1,3}[ -]?)?(?:\\(\\d{2,4}\\)[ -]?|\\d{2,4}[ -])\\d{3,4}[ -]?\\d{3,4}\\b"
                + "|(?<![\\w+])\\+\\d{8,15}\\b"));
    }

    private LegacyPiiRedaction() {
    }

    /**
     * The legacy rewrite with all four kinds, or {@code text} itself if none
     * matched.
     */
    static String redact(String text, String replacementTemplate) {
        String result = text;
        for (String kind : KINDS.keySet()) {
            result = redact(result, kind, replacementTemplate);
        }
        return result;
    }

    private static String redact(String text, String kind, String replacementTemplate) {
        String replacement = replacementTemplate.replace("{kind}", kind);
        Matcher matcher = KINDS.get(kind).matcher(text);
        StringBuilder out = new StringBuilder();
        int copied = 0;
        int from = 0;
        boolean any = false;
        while (from <= text.length() && matcher.find(from)) {
            int start = matcher.start();
            int end = matcher.end();
            from = end;
            if ("creditcard".equals(kind)) {
                int[] card = cardWithin(text, start, end);
                if (card == null) {
                    int separator = firstSeparator(text, start, end);
                    if (separator >= 0) {
                        from = separator + 1;
                    }
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

    private static int[] cardWithin(String text, int runStart, int runEnd) {
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
                int digits = 0;
                for (int i = s; i < e; i++) {
                    if (Character.isDigit(text.charAt(i))) {
                        digits++;
                    }
                }
                if (digits < 13 || digits > 19 || digits <= bestDigits) {
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

    private static int firstSeparator(String text, int start, int end) {
        for (int i = start; i < end; i++) {
            char c = text.charAt(i);
            if (c == ' ' || c == '-') {
                return i;
            }
        }
        return -1;
    }

    private static boolean luhn(String candidate) {
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
