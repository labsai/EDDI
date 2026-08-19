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
     * Ordered list of redaction patterns. Each pattern has a compiled regex and a
     * replacement strategy.
     */
    // Quantifiers below are POSSESSIVE (++, *+, {n,}+) to eliminate ReDoS
    // backtracking. This is behavior-preserving here: every quantified class is
    // followed by a literal that is NOT a member of the class (a '.', '}', or the
    // string end), so a correct match never needs to give characters back — the
    // possessive form yields identical results while running in linear time on
    // adversarial inputs (e.g. long repetitions of "${vault:").
    //
    // The two negative lookaheads are the deliberate exception: they must be free
    // to backtrack, because they ask "does the marker appear ANYWHERE before the
    // closing quote". A possessive class there consumes the marker itself and then
    // finds nothing left to match, so the lookahead never fires and the guard is
    // silently inert. They stay bounded by the quote they cannot cross.
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
            new RedactionRule(Pattern.compile(
                    "(?i)(api[_-]?key|token|secret|password|authorization)((?:\\\\*+[\"'])?+\\s*+[=:]\\s*+)(\\\\*+[\"'])"
                            + "(?!\\$\\{(?:vault|eddivault):)(?![^\"']*" + REDACTED + ")"
                            + "(?:[^\"'\\\\]|\\\\(?![\"'])){8,}+(\\\\*+[\"'])"),
                    "$1$2$3" + REDACTED + "$4"),

            // The same, for a JSON value that carries no quotes of its own — a
            // number, or a bare literal. The marker is a string, so it is given the
            // key's own quote style (group 2 and 3, escaped or not) to keep the
            // document parseable: `{"token":12345678}` → `{"token":"<REDACTED>"}`.
            new RedactionRule(Pattern.compile(
                    "(?i)(api[_-]?key|token|secret|password|authorization)(\\\\*+)([\"'])(\\s*+:\\s*+)"
                            + "(?!\\\\*+[\"'])(?![^\\s,;}{\\]\"']*" + REDACTED + ")"
                            + "[^\\s,;}{\\]\"'\\\\]{8,}+"),
                    "$1$2$3$4$2$3" + REDACTED + "$2$3"),

            // Everything that is not JSON: query strings (?api_key=…), log lines
            // ("password: …"). No quotes to preserve, so the separator is put back
            // and the value replaced.
            new RedactionRule(Pattern.compile(
                    "(?i)(api[_-]?key|token|secret|password|authorization)((?:\\\\*+[\"'])?+\\s*+[=:]\\s*+(?:\\\\*+[\"'])?+)"
                            + "(?![^\\s,;}{\\]\"']*" + REDACTED + ")"
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
