/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets.sanitize;

import java.util.List;
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
     * Anchored to the START of the value, deliberately. A "does the marker appear
     * anywhere in this value" guard reads as more thorough and is strictly worse:
     * it skips {@code "secret":"the key sk-ant-<REDACTED> is here"}, leaving the
     * text around the marker unredacted under a key that says it is a secret — and
     * it hands anyone who knows the marker a bypass, since a value of
     * {@code my<REDACTED>pass} then passes through untouched. Beginning with a
     * redacted form is the condition actually meant, and it is the narrow one.
     * <p>
     * The optional prefix is the one non-possessive quantifier in this file: it has
     * to be free to try {@code sk-ant-}, then {@code sk-}, then nothing, and it is
     * bounded by three short literal alternatives, so it is no ReDoS surface.
     */
    private static final String NOT_ALREADY_REDACTED = "(?!(?:sk-ant-|sk-|Bearer\\s)?" + REDACTED + ")";

    /**
     * Ordered list of redaction patterns. Each pattern has a compiled regex and a
     * replacement strategy.
     */
    // Quantifiers below are POSSESSIVE (++, *+, {n,}+) to eliminate ReDoS
    // backtracking. This is behavior-preserving here: every quantified class is
    // followed by a literal that is NOT a member of the class (a '.', '}', a quote,
    // or the string end), so a correct match never needs to give characters back —
    // the possessive form yields identical results while running in linear time on
    // adversarial inputs (e.g. long repetitions of "${vault:"). The single
    // exception is the optional prefix inside NOT_ALREADY_REDACTED, documented
    // there.
    private static final List<RedactionRule> RULES = List.of(
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
                    "Bearer " + REDACTED),

            // A credential named by its key, with a QUOTED value — the JSON shape,
            // including JSON nested as an ESCAPED string ("apiKey\": \"..."), which
            // is what a tool call whose requestBody argument is itself a JSON
            // document looks like. The backslash-tolerant quote groups are what see
            // through that escaping; without them the separator never matches inside
            // escaped bodies.
            //
            // Only the VALUE is replaced. The rule this one replaces matched the
            // key's closing quote, the colon and the value's opening quote and threw
            // all three away ("$1=" + REDACTED), so `{"apiKey":"sk-…"}` came back as
            // `{"apiKey=<REDACTED>"}` — a bare string where a key/value pair was, and
            // not parseable JSON. Both readers of a redacted body then failed
            // silently on it: the Manager's approval diff fell back to comparing raw
            // text and reported the whole stored document as deleted, and its
            // capability-grant checks sit behind a JSON.parse, so a request that
            // embedded a credential AND granted dynamic agent creation warned about
            // the credential alone.
            //
            // The value runs to its closing quote rather than to the first
            // delimiter, which also closes a leak: the loose rule's class stops at
            // ',', whitespace, ';', '{', '}' and ']', so a secret containing any of
            // those was only redacted up to that character and the tail survived.
            //
            // The closing quote is a BACKREFERENCE to the opening one (\4), not
            // "any quote". Accepting either let the wrong quote end the value:
            // `"password":"abcdefgh'xyz"` terminated at the apostrophe and published
            // the rest, and `"password":"it's-a-secret"` fell under the 8-character
            // floor at that same apostrophe and was not redacted at all. Tying the
            // pair together also lets the value class admit the OTHER quote
            // character, which is what makes both of those whole again.
            //
            // Known limit: an escaped quote INSIDE a value ends it early
            // (`"he said \"x\" done"` redacts up to `\"`). That is the same
            // ambiguity the backslash tolerance exists for — `\"` is a terminator in
            // an escaped-JSON body and a literal in a plain one — and it is resolved
            // in favour of the escaped body, because reading it the other way runs
            // the match past the real end of the field and eats the document.
            new RedactionRule(Pattern.compile(
                    "(?i)(api[_-]?key|token|secret|password|authorization)((?:\\\\*+[\"'])?+\\s*+[=:]\\s*+)(\\\\*+)([\"'])"
                            + "(?!\\$\\{(?:vault|eddivault):)" + NOT_ALREADY_REDACTED
                            + "(?:(?!\\4)[^\\\\]|\\\\(?!\\4)){8,}+(\\\\*+)\\4"),
                    "$1$2$3$4" + REDACTED + "$5$4"),

            // The same, for a JSON value that carries no quotes of its own — a
            // number, or a bare literal. The marker is a string, so it is given the
            // key's own quote style (group 2 and 3, escaped or not) to keep the
            // document parseable: `{"token":12345678}` → `{"token":"<REDACTED>"}`.
            new RedactionRule(Pattern.compile(
                    "(?i)(api[_-]?key|token|secret|password|authorization)(\\\\*+)([\"'])(\\s*+:\\s*+)"
                            + "(?!\\\\*+[\"'])" + NOT_ALREADY_REDACTED
                            + "[^\\s,;}{\\]\"'\\\\]{8,}+"),
                    "$1$2$3$4$2$3" + REDACTED + "$2$3"),

            // Everything that is not JSON: query strings (?api_key=…), log lines
            // ("password: …"). No quotes to preserve, so the separator is put back
            // and the value replaced.
            new RedactionRule(Pattern.compile(
                    "(?i)(api[_-]?key|token|secret|password|authorization)((?:\\\\*+[\"'])?+\\s*+[=:]\\s*+(?:\\\\*+[\"'])?+)"
                            + NOT_ALREADY_REDACTED
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
    // Two of the rules above are kept off it by their value class, which excludes
    // `{`/`}` so `apiKey: "${vault:x}"` never reaches the 8-char minimum. The
    // quoted-value rule runs to the closing quote instead and would happily consume
    // a reference, so it carries an explicit `(?!\$\{(?:vault|eddivault):)` — the
    // carve-out is a stated rule there rather than a side effect of a character
    // class, which is also what keeps it from being lost the next time that class
    // is tuned.

    private SecretRedactionFilter() {
        // Utility class
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
        for (RedactionRule rule : RULES) {
            result = rule.pattern.matcher(result).replaceAll(rule.replacement);
        }
        return result;
    }

    private record RedactionRule(Pattern pattern, String replacement) {
    }
}
