/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.sanitize;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pre-compiled regex filter for redacting secrets from log messages. Applied in
 * the log capture workflow to prevent API keys and tokens from appearing in
 * logs, ring buffer, and database.
 */
public final class SecretRedactionFilter {

    private static final String REDACTED = "<REDACTED>";

    /**
     * Do not re-redact a value an earlier rule has already replaced.
     * <p>
     * Those rules say WHAT KIND of credential was found —
     * {@code sk-ant-<REDACTED>}, {@code Bearer <REDACTED>} — and an approver uses
     * that. Without this guard the name-based rules below strip the prefix straight
     * back off, which is what the rule they replace did to every named field.
     * <p>
     * The redacted form has to be the WHOLE value, which is why the caller supplies
     * the lookahead that ends it. Both looser readings leak:
     * <ul>
     * <li>"the marker appears anywhere in the value" skips
     * {@code "secret":"the key sk-ant-<REDACTED> is here"}, leaving the text around
     * the marker unredacted under a key that says it is a secret — and it hands
     * anyone who knows the marker a bypass via
     * {@code "password":"my<REDACTED>pass"};
     * <li>"the value begins with a redacted form" skips
     * {@code "apiKey":"sk-ant-<REDACTED>,SECRET-TAIL"}. Not hypothetical: the
     * {@code sk-ant-} rule's own class stops at a delimiter, so it produces exactly
     * that shape from {@code sk-ant-abcdefghijklmnopqrst,SECRET-TAIL} — and the
     * tail is real secret material.
     * </ul>
     * A value only PARTLY redacted is therefore taken over by the rules below and
     * replaced whole, which loses the {@code sk-ant-} hint. That is the intended
     * trade: the hint is worth having, and it is not worth a leak.
     * <p>
     * The optional prefix is the one non-possessive quantifier in this file — it
     * has to be free to try {@code sk-ant-}, then {@code sk-}, then nothing — and
     * it is bounded by three short literal alternatives, so it is no ReDoS surface.
     *
     * @param endOfValue
     *            zero-width lookahead matching where the calling rule's value ends
     */
    private static String notAlreadyRedacted(String endOfValue) {
        return "(?!(?:sk-ant-|sk-|Bearer\\s)?" + REDACTED + endOfValue + ")";
    }

    /**
     * Ordered list of redaction patterns. Each pattern has a compiled regex and a
     * replacement strategy.
     */
    // Quantifiers below are POSSESSIVE (++, *+, {n,}+) to eliminate ReDoS
    // backtracking. This is behavior-preserving here: every quantified class is
    // followed by a literal that is NOT a member of the class (a '.', '}', a quote,
    // or the string end), so a correct match never needs to give characters back —
    // the possessive form yields identical results while running in linear time on
    // adversarial inputs (e.g. long repetitions of "${vault:").
    //
    // The one exception is the optional prefix inside notAlreadyRedacted(),
    // documented there.
    //
    // Nothing here quantifies a GROUP. Java matches `(?:A|B){n,}` by recursion —
    // one stack frame per repetition — so a group quantifier over a value-length
    // run overflows the stack on input a user can send. An earlier draft of the
    // quoted-value rule did exactly that and died on a 200 000-character value
    // with no escapes in it at all. That rule is now a linear scan; see
    // redactQuotedValues.
    private static final List<RedactionRule> SHAPE_RULES = List.of(
            // Anthropic API keys: sk-ant-... — underscores INCLUDED. Real keys carry
            // them, and a class without '_' stopped matching at the first one: a full
            // key inside a gated tool call's arguments sailed through "redacted" and
            // rendered clear-text on the approval card. Ordered before the generic
            // sk- rule so the replacement keeps the sk-ant- prefix.
            new RedactionRule(Pattern.compile("sk-ant-[a-zA-Z0-9_\\-]{20,}+"), "sk-ant-" + REDACTED),

            // OpenAI API keys: sk-... (at least 20 chars). Underscores and hyphens
            // included — sk-proj-... keys carry both.
            new RedactionRule(Pattern.compile("sk-[a-zA-Z0-9_\\-]{20,}+"), "sk-" + REDACTED),

            // Bearer tokens — JWTs AND opaque tokens. A single possessive character
            // class (no mandatory '.') so 'Bearer <opaque>' is redacted too, not only
            // dotted JWTs; min length 20 avoids redacting short benign words.
            new RedactionRule(Pattern.compile("Bearer\\s++[A-Za-z0-9\\-_.+/=]{20,}+"),
                    "Bearer " + REDACTED));

    /**
     * Rules applied after {@link #redactQuotedValues}, for values that are not a
     * quoted string.
     */
    private static final List<RedactionRule> RULES = List.of(
            // A JSON value that carries no quotes of its own — a
            // number, or a bare literal. The marker is a string, so it is given the
            // key's own quote style (group 2 and 3, escaped or not) to keep the
            // document parseable: `{"token":12345678}` → `{"token":"<REDACTED>"}`.
            new RedactionRule(Pattern.compile(
                    "(?i)(api[_-]?key|token|secret|password|authorization)(\\\\*+)([\"'])(\\s*+:\\s*+)"
                            + "(?!\\\\*+[\"'])" + notAlreadyRedacted("(?=[\\s,;}{\\]\"']|$)")
                            + "[^\\s,;}{\\]\"'\\\\]{8,}+"),
                    "$1$2$3$4$2$3" + REDACTED + "$2$3"),

            // Everything that is not JSON: query strings (?api_key=…), log lines
            // ("password: …"). No quotes to preserve, so the separator is put back
            // and the value replaced.
            new RedactionRule(Pattern.compile(
                    "(?i)(api[_-]?key|token|secret|password|authorization)((?:\\\\*+[\"'])?+\\s*+[=:]\\s*+(?:\\\\*+[\"'])?+)"
                            + notAlreadyRedacted("(?=['\"\\\\\\s,;}{\\]]|$)")
                            + "[^'\"\\\\\\s,;}{\\]]{8,}+"),
                    "$1$2" + REDACTED));

    // A `${vault:...}` reference is DELIBERATELY NOT redacted. It is a pointer to
    // a secret — the correct, encouraged alternative to writing one down — and the
    // key NAME it carries is ordinary configuration an admin reads in the agent
    // document anyway. Masking it cost real information and bought nothing:
    //
    // - on an approval card it hid WHICH credential a request uses, which is
    // exactly what an approver needs to judge it ("is this agent about to use
    // the production key?");
    // - it made every correct, vault-referencing request display a `<REDACTED>`
    // marker, training approvers to read that marker as normal — and the marker
    // is precisely the signal the Manager uses to warn that a request embedded a
    // secret LITERAL. A control that fires on the safe case is a control people
    // learn to ignore.
    //
    // A resolved secret does not look like a vault reference (it is the raw value,
    // caught by the rules above), so nothing is weakened by leaving the pointer
    // legible.
    //
    // The rules above are kept off it by their value class, which excludes `{`/`}`
    // so `apiKey: "${vault:x}"` never reaches the 8-char minimum. The quoted-value
    // scan runs to the closing quote instead and would happily consume a reference,
    // so shouldRedact() states the carve-out outright — a rule of its own rather
    // than a side effect of a character class, which is what keeps it from being
    // lost the next time that class is tuned.

    /**
     * A credential named by its key, up to and including its value's opening quote.
     * <p>
     * Everything after that is found by {@link #redactQuotedValues} rather than by
     * the regex, so nothing here is quantified over a value-length run.
     * <p>
     * The backslash-tolerant quote groups are what see an ESCAPED JSON body
     * ({@code "apiKey\": \"…"}), which is what a tool call whose requestBody
     * argument is itself a JSON document looks like; without them the separator
     * never matches inside one.
     */
    private static final Pattern QUOTED_VALUE_START = Pattern.compile(
            "(?i)(api[_-]?key|token|secret|password|authorization)"
                    + "(?:\\\\*+[\"'])?+\\s*+[=:]\\s*+\\\\*+[\"']");

    /**
     * Below this many characters a value is left legible — benign values, mostly.
     */
    private static final int MINIMUM_SECRET_LENGTH = 8;

    /**
     * Exactly what an earlier rule leaves behind when it redacted a WHOLE value.
     */
    private static final Pattern FULLY_REDACTED_VALUE = Pattern.compile(
            "(?:sk-ant-|sk-|Bearer\\s)?" + Pattern.quote(REDACTED));

    private SecretRedactionFilter() {
        // Utility class
    }

    /**
     * Replace the VALUE of every quoted credential field, keeping both quotes.
     * <p>
     * The rule this replaces matched the key's closing quote, the colon and the
     * value's opening quote and threw all three away, so {@code {"apiKey":"sk-…"}}
     * came back as {@code {"apiKey=<REDACTED>"}} — a bare string where a key/value
     * pair was, and not parseable JSON. Both readers of a redacted body then failed
     * silently on it: the Manager's approval diff fell back to comparing raw text
     * and reported the whole stored document as deleted, and its capability-grant
     * checks sit behind a {@code JSON.parse}, so a request that embedded a
     * credential AND granted dynamic agent creation warned about the credential
     * alone.
     * <p>
     * Written as a scan rather than a pattern because the value cannot safely be
     * expressed as one. It has to run to the closing quote (stopping at the first
     * delimiter left the tail of any secret containing one in the output), that
     * quote has to be the one that opened it (accepting either let an apostrophe
     * close a double-quoted value and publish the rest), and an escaped quote
     * inside the value must not end it. Every regex for that quantifies a GROUP,
     * and Java matches those by recursion — the draft that did overflowed the stack
     * on a 200 000-character value with no escapes in it at all. A scan has none of
     * that.
     */
    private static String redactQuotedValues(String message) {
        Matcher matcher = QUOTED_VALUE_START.matcher(message);
        StringBuilder out = new StringBuilder(message.length());
        int copiedUpTo = 0;
        int searchFrom = 0;

        while (searchFrom <= message.length() && matcher.find(searchFrom)) {
            char quote = message.charAt(matcher.end() - 1);
            int valueStart = matcher.end();
            int closingQuote = findClosingQuote(message, valueStart, quote);
            if (closingQuote < 0) {
                // No terminator — a truncated body. Left to the loose rule, which
                // needs no closing quote and still redacts what it can see.
                break;
            }
            searchFrom = closingQuote + 1;

            // Backslashes immediately before the closing quote belong to the closing
            // token (`\"` in an escaped body), not to the value.
            int valueEnd = closingQuote;
            while (valueEnd > valueStart && message.charAt(valueEnd - 1) == '\\') {
                valueEnd--;
            }
            String value = message.substring(valueStart, valueEnd);
            if (!shouldRedact(value)) {
                continue;
            }

            out.append(message, copiedUpTo, valueStart).append(REDACTED);
            copiedUpTo = valueEnd;
        }

        return copiedUpTo == 0
                ? message
                : out.append(message, copiedUpTo, message.length()).toString();
    }

    /**
     * The first quote that both matches the opening one and actually ends a JSON
     * value, or -1 when the value never closes.
     * <p>
     * {@code \"} is genuinely ambiguous — a terminator in an escaped-JSON body, an
     * escaped quote INSIDE the value in a plain one — and this class cannot know
     * which document it is reading. What separates them is what FOLLOWS. Closing at
     * the first candidate leaks the rest of the value
     * ({@code "he said \"x\" SECRET"} kept {@code x\" SECRET}); closing at the last
     * runs past the real end of the field and eats the document. Requiring the
     * candidate to be followed by something that ends a JSON value picks correctly
     * in both readings: the escaped body closes at its {@code \"} because a brace
     * follows, the plain one carries on past {@code \"x\"} because a letter does.
     */
    private static int findClosingQuote(String text, int from, char quote) {
        for (int i = from; i < text.length(); i++) {
            if (text.charAt(i) == quote && endsAValue(text, i + 1)) {
                return i;
            }
        }
        return -1;
    }

    /** Whether {@code i} is where a JSON value legitimately ends. */
    private static boolean endsAValue(String text, int i) {
        int j = i;
        while (j < text.length() && Character.isWhitespace(text.charAt(j))) {
            j++;
        }
        if (j == text.length()) {
            return true;
        }
        char c = text.charAt(j);
        return c == ',' || c == '}' || c == ']';
    }

    /**
     * Whether a quoted value is one this filter should replace.
     * <p>
     * The length floor keeps benign values legible. A {@code ${vault:…}} reference
     * is a POINTER to a secret and stays legible on purpose — see the note above.
     * And a value an earlier rule has ALREADY redacted is left alone so its
     * {@code sk-ant-} / {@code Bearer } prefix survives — but only when that rule
     * consumed the WHOLE value. {@code sk-ant-}'s own class stops at a delimiter,
     * so {@code sk-ant-<REDACTED>,SECRET-TAIL} is a half-finished job this has to
     * complete, losing the prefix. That is the right trade against publishing the
     * tail, and treating a redacted PREFIX as a redacted value is how the tail got
     * published in the first place.
     */
    private static boolean shouldRedact(String value) {
        return value.length() >= MINIMUM_SECRET_LENGTH
                && !value.startsWith("${vault:")
                && !value.startsWith("${eddivault:")
                && !FULLY_REDACTED_VALUE.matcher(value).matches();
    }

    /**
     * Redact potential secret values from a log message.
     *
     * @param message
     *            the raw log message
     * @return the message with secrets replaced by {@code <REDACTED>}
     */
    public static String redact(String message) {
        if (message == null || message.isEmpty()) {
            return message;
        }

        String result = message;
        for (RedactionRule rule : SHAPE_RULES) {
            result = rule.pattern.matcher(result).replaceAll(rule.replacement);
        }
        result = redactQuotedValues(result);
        for (RedactionRule rule : RULES) {
            result = rule.pattern.matcher(result).replaceAll(rule.replacement);
        }
        return result;
    }

    private record RedactionRule(Pattern pattern, String replacement) {
    }
}
