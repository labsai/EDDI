/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.sanitize;

import ai.labs.eddi.secrets.model.SecretReference;

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
     * A whole vault reference, as an OPTIONAL, possessive prefix on an unquoted
     * value.
     * <p>
     * The unquoted rules stop their value at {@code {}, which is what keeps them
     * off a reference — and is also a bypass: {@code password: ${vault:key}SECRET}
     * matched nothing, because the value was one character long at the brace.
     * Letting the value BEGIN with a reference means reference-plus-tail is matched
     * and replaced as a whole, while a bare reference still falls short of the
     * length floor that follows it and survives. Possessive on both counts, so a
     * reference is never given back to be re-read as value material.
     */
    private static final String OPTIONAL_VAULT_REFERENCE = "(?:\\$\\{(?:vault|eddivault):[^}]*+\\})?+";

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
                            + OPTIONAL_VAULT_REFERENCE + "[^\\s,;}{\\]\"'\\\\]{8,}+"),
                    "$1$2$3$4$2$3" + REDACTED + "$2$3"),

            // Everything that is not JSON: query strings (?api_key=…), log lines
            // ("password: …"). No quotes to preserve, so the separator is put back
            // and the value replaced.
            new RedactionRule(Pattern.compile(
                    "(?i)(api[_-]?key|token|secret|password|authorization)((?:\\\\*+[\"'])?+\\s*+[=:]\\s*+(?:\\\\*+[\"'])?+)"
                            + notAlreadyRedacted("(?=['\"\\\\\\s,;}{\\]]|$)")
                            + OPTIONAL_VAULT_REFERENCE + "[^'\"\\\\\\s,;}{\\]]{8,}+"),
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
    // Every exemption for it is a WHOLE-REFERENCE match, never a prefix check. A
    // prefix check is a bypass — `${vault:key}SECRET-TAIL` is a secret wearing a
    // pointer as a hat — and AgentSetupService moved off the contains-style
    // isVaultReference for the same reason. shouldRedact() uses the repository's
    // canonical SecretReference pattern, and the two unquoted rules carry
    // OPTIONAL_VAULT_REFERENCE so that reference-plus-tail is redacted as a whole
    // while a bare reference still falls short of the length floor and survives.

    /**
     * A credential named by its key, up to and including its value's opening quote.
     * <p>
     * Everything after that is found by {@link #redactQuotedValues} rather than by
     * the regex, so nothing here is quantified over a value-length run.
     * <p>
     * The backslash-tolerant quote groups are what see an ESCAPED JSON body
     * ({@code "apiKey\": \"…"}), which is what a tool call whose requestBody
     * argument is itself a JSON document looks like; without them the separator
     * never matches inside one. Group 2 — the backslashes in front of the value's
     * opening quote — is how deep that nesting goes, and the scan needs it.
     */
    private static final Pattern QUOTED_VALUE_START = Pattern.compile(
            "(?i)(api[_-]?key|token|secret|password|authorization)"
                    + "(?:\\\\*+[\"'])?+\\s*+[=:]\\s*+(\\\\*+)([\"'])");

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
     * <p>
     * A value that never closes — a body cut short by the preview cap — is redacted
     * to the end of the input. Everything after its opening quote IS the secret,
     * and a truncated body is exactly where a leak goes unnoticed.
     */
    private static String redactQuotedValues(String message) {
        Matcher matcher = QUOTED_VALUE_START.matcher(message);
        StringBuilder out = new StringBuilder(message.length());
        int copiedUpTo = 0;
        int searchFrom = 0;

        while (searchFrom <= message.length() && matcher.find(searchFrom)) {
            int openingBackslashes = matcher.group(2).length();
            char quote = matcher.group(3).charAt(0);
            int valueStart = matcher.end();

            int closingQuote = findClosingQuote(message, valueStart, quote, openingBackslashes);
            int valueEnd;
            if (closingQuote < 0) {
                valueEnd = message.length();
                searchFrom = message.length() + 1;
            } else {
                // The closing token is the quote plus the backslashes that escape
                // it at this depth; any further backslashes belong to the value.
                int escaping = Math.min(openingBackslashes, backslashesBefore(message, closingQuote));
                valueEnd = closingQuote - escaping;
                searchFrom = closingQuote + 1;
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
     * The quote that closes a value opened at a given nesting depth, or -1 when the
     * value never closes.
     * <p>
     * The depth is the number of backslashes in front of the opening quote: none
     * for a plain document, one for a JSON body carried inside a string field,
     * three for one carried inside THAT, and so on — each level of nesting escapes
     * every backslash and quote once more. A quote with {@code b} backslashes in
     * front of it is then:
     * <ul>
     * <li>an unescaped quote at this depth — the terminator — when
     * {@code (b + 1) / (opening + 1)} is a whole ODD number. At depth 0 that is an
     * even count of backslashes; at depth 1 it is 1, 5, 9, …;
     * <li>an escaped quote INSIDE the value when that quotient is a whole EVEN
     * number: {@code \"} at depth 0, {@code \\\"} at depth 1. Skipped, whatever
     * follows it;
     * <li>a quote from a SHALLOWER level when it does not divide — the enclosing
     * string has closed before this value did, so the value ends here.
     * </ul>
     * Deciding by escaping rather than by what FOLLOWS the quote is what closes
     * three leaks at once: an escaped quote followed by a comma
     * ({@code "ab\",cd-SECRET"}) no longer ends the value early; a free-text value
     * closes at its own quote rather than eating the rest of the line up to some
     * later one ({@code apiKey: "x" to host "y"}); and a pretty-printed nested body
     * closes at its {@code \"} even though what follows is {@code \r\n} rather than
     * a brace.
     */
    private static int findClosingQuote(String text, int from, char quote, int openingBackslashes) {
        int depthUnit = openingBackslashes + 1;
        int backslashes = 0;
        for (int i = from; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\\') {
                backslashes++;
                continue;
            }
            if (c == quote) {
                boolean sameDepth = (backslashes + 1) % depthUnit == 0;
                boolean escapedAtThisDepth = sameDepth && ((backslashes + 1) / depthUnit) % 2 == 0;
                if (!escapedAtThisDepth) {
                    return i;
                }
            }
            backslashes = 0;
        }
        return -1;
    }

    /** How many backslashes sit immediately in front of position {@code i}. */
    private static int backslashesBefore(String text, int i) {
        int count = 0;
        while (i - count - 1 >= 0 && text.charAt(i - count - 1) == '\\') {
            count++;
        }
        return count;
    }

    /**
     * Whether a quoted value is one this filter should replace.
     * <p>
     * The length floor keeps benign values legible. The two exemptions both ask the
     * same question — "is this value, IN FULL, something that is not a secret" —
     * and both have to be whole-value matches, because a prefix check is a bypass:
     * <ul>
     * <li>A {@code ${vault:…}} reference is a POINTER to a secret and stays legible
     * on purpose — see the note above. But {@code ${vault:key}SECRET-TAIL} is a
     * secret wearing a pointer as a hat, and {@code startsWith} would have waved it
     * through. {@link SecretReference#compiledPattern()} is the repository's
     * canonical whole-reference match, adopted for exactly this reason in
     * {@code AgentSetupService} over the contains-style {@code isVaultReference}.
     * <li>A value an earlier rule has ALREADY redacted is left alone so its
     * {@code sk-ant-} / {@code Bearer } prefix survives — but only when that rule
     * consumed the WHOLE value. {@code sk-ant-}'s own class stops at a delimiter,
     * so {@code sk-ant-<REDACTED>,SECRET-TAIL} is a half-finished job this has to
     * complete, losing the prefix. That is the right trade against publishing the
     * tail; treating a redacted PREFIX as a redacted value is how the tail got
     * published in the first place.
     * </ul>
     */
    private static boolean shouldRedact(String value) {
        return value.length() >= MINIMUM_SECRET_LENGTH
                && !SecretReference.compiledPattern().matcher(value).matches()
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
