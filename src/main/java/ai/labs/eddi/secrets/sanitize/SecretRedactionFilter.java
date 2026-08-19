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
            //
            // Group 2 is the key quote's ESCAPING and is mirrored onto the marker's
            // quotes, so it admits only a genuine escaping depth — none, one, three
            // or seven backslashes. Four backslashes in front of a quote are two
            // escaped backslashes of content plus a bare quote; mirroring those
            // onto the marker put content backslashes inside the new value, which a
            // second pass then read as unredacted and redacted again. A fuzzer found
            // that as an idempotency failure. A key quote with any other count is
            // not a JSON key at all, and the loose rule below still sees the value.
            new RedactionRule(Pattern.compile(
                    "(?i)(api[_-]?key|token|secret|password|authorization)((?:\\\\{7}|\\\\{3}|\\\\)?+)([\"'])(\\s*+:\\s*+)"
                            + "(?!\\\\*+[\"'])" + notAlreadyRedacted("(?=[\\s,;}{\\]\"']|$)")
                            + OPTIONAL_VAULT_REFERENCE + "[^\\s,;}{\\]\"'\\\\]{8,}+"),
                    "$1$2$3$4$2$3" + REDACTED + "$2$3"),

            // Everything that is not JSON: query strings (?api_key=…), log lines
            // ("password: …"). No quotes to preserve, so the separator is put back
            // and the value replaced.
            //
            // The value is NOT allowed to open with a quote. Every quoted value has
            // already been decided by redactQuotedValues before this rule runs —
            // redacted (the guard sees the marker), exempt (the possessive vault
            // prefix and the guard see those), or under the floor — so an optional
            // opening quote here could only ever misread something else as one. A
            // fuzzer found the something else: a key NAMED `token:`, colon and all,
            // in valid JSON. `"token:":12378901` read as name `token`, separator
            // `:`, opening quote `"` — the key's CLOSING quote — and a value of
            // `:12378901`, and left a bare marker where the pair had been.
            new RedactionRule(Pattern.compile(
                    "(?i)(api[_-]?key|token|secret|password|authorization)((?:\\\\*+[\"'])?+\\s*+[=:]\\s*+)"
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
                    + "((?:\\\\*+[\"'])?+)\\s*+[=:]\\s*+(\\\\*+)([\"'])");

    /**
     * A separator, after optional whitespace — what follows a key's CLOSING quote.
     */
    private static final Pattern SEPARATOR_AHEAD = Pattern.compile("\\s*+[=:]");

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
     * Where each value ends — its matching quote, a double quote for an apostrophe
     * value, or the end of a truncated input — is {@link #endOfValue}'s business.
     */
    private static String redactQuotedValues(String message) {
        Matcher matcher = QUOTED_VALUE_START.matcher(message);
        StringBuilder out = new StringBuilder(message.length());
        int copiedUpTo = 0;
        int searchFrom = 0;

        while (searchFrom <= message.length() && matcher.find(searchFrom)) {
            // The opening quote's DEPTH is its own escaping, not the raw count of
            // backslashes in front of it: four backslashes are two escaped
            // backslashes of content and a bare quote, depth zero — a fuzzer found
            // the raw count read as a nonsense depth five.
            int openingEscaping = escapingOf(matcher.group(3).length());
            char quote = matcher.group(4).charAt(0);
            int valueStart = matcher.end();

            if (isKeyNameEndingInASeparator(message, matcher.start(), matcher.group(2), valueStart)) {
                searchFrom = matcher.start() + 1;
                continue;
            }

            int valueEnd = endOfValue(message, valueStart, quote, openingEscaping);

            String value = message.substring(valueStart, valueEnd);
            if (isExempt(value)) {
                // A reference or an already-redacted form: a token, left whole.
                // Nothing inside it is a field, so resume after it — see isExempt.
                searchFrom = valueEnd;
                continue;
            }
            if (value.length() < MINIMUM_SECRET_LENGTH) {
                // Under the floor, so left legible — and its text is still live: a
                // credential field can START inside it. `SECRET:'SECRET:'<long>'`
                // reads first as a 7-character value of `SECRET:`, whose closing
                // apostrophe is the second field's opening one; resuming past that
                // close skipped the second field entirely, and a fuzzer caught a
                // second pass redacting what the first had missed. Resume just
                // inside the opening quote. Each untouched value is read at most
                // once more, so this stays linear.
                searchFrom = valueStart;
                continue;
            }
            out.append(message, copiedUpTo, valueStart).append(REDACTED);
            copiedUpTo = valueEnd;
            // Resume AT the value's end, not past the closing token: for an
            // apostrophe value that stopped at a double quote, that quote may be
            // the opening of the next field. Re-reading a closing token is harmless
            // — the pattern needs a credential name in front of a quote to match.
            searchFrom = valueEnd;
        }

        return copiedUpTo == 0
                ? message
                : out.append(message, copiedUpTo, message.length()).toString();
    }

    /**
     * Whether what {@link #QUOTED_VALUE_START} just matched is really a quoted JSON
     * key whose NAME ends in a separator — {@code "token:"} — rather than a
     * credential followed by a quoted value.
     * <p>
     * A fuzzer found the misread in valid JSON: {@code {"token:":12378901}} was
     * parsed as name {@code token}, separator {@code :} (the one inside the key
     * name), opening quote {@code "} (the key's CLOSING quote) and a value of
     * {@code :12378901} — and the redaction left a bare marker where the pair had
     * been. Three things are true of that shape together and of nothing legitimate:
     * the optional key-closing-quote group matched EMPTY (the separator came before
     * any quote), the name sits directly inside a quoted key (a quote precedes it
     * through identifier characters), and the supposed opening quote is followed by
     * a separator. A value that merely starts with a colon,
     * {@code "password": ":x"}, has a non-empty key-close group; a credential
     * embedded at the start of a string value, {@code "error":"password:\"x\""},
     * has no separator after its quote. Both still redact.
     * <p>
     * The value under such a key is not redacted by name — {@code token:} is not a
     * credential key any rule recognises — but the document stays JSON, which is
     * the promise.
     */
    private static boolean isKeyNameEndingInASeparator(String text, int nameStart, String keyClose, int valueStart) {
        if (!keyClose.isEmpty()) {
            return false;
        }
        int i = nameStart - 1;
        while (i >= 0 && isIdentifierChar(text.charAt(i))) {
            i--;
        }
        boolean insideQuotedKey = i >= 0 && (text.charAt(i) == '"' || text.charAt(i) == '\'');
        return insideQuotedKey && SEPARATOR_AHEAD.matcher(text).region(valueStart, text.length()).lookingAt();
    }

    private static boolean isIdentifierChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.';
    }

    /**
     * Where a value ends — the index just past its last character, exclusive of the
     * closing token — for a value opened by {@code quote} at a given escaping
     * depth. Three outcomes, in priority order as the scan moves forward:
     * <ul>
     * <li><b>The matching quote at the same depth.</b> The depth is the opening
     * quote's own escaping (see {@link #escapingOf}): none for a plain document,
     * one for a JSON body carried inside a string field, three for one carried
     * inside THAT — each level of nesting escapes every backslash and quote once
     * more. A quote with {@code b} backslashes in front of it is the terminator
     * when {@code (b + 1) / (escaping + 1)} is a whole ODD number (at depth 0 an
     * even count of backslashes; at depth 1 one, five, nine, …); an escaped quote
     * INSIDE the value when that quotient is a whole EVEN number ({@code \"} at
     * depth 0, {@code \\\"} at depth 1), skipped whatever follows it; and a quote
     * from a SHALLOWER level when it does not divide — the enclosing string closed
     * before this value did, so the value ends here.
     * <li><b>For an apostrophe value only, any double quote.</b> JSON has no
     * apostrophe quoting, so Jackson never escapes one and the depth arithmetic
     * cannot see its context: an apostrophe value may sit in a JSON string at any
     * depth, or in none. A fuzzer showed what letting it run to its matching
     * apostrophe does inside JSON: opened in one string and closed in another at a
     * different nesting level, it ate the brace between and left the carrier
     * unparseable — and the Manager's escalation checks skip a body that does not
     * parse, the failure this whole filter exists to prevent. Stopping at the first
     * double quote of ANY escaping never crosses a string boundary at any depth,
     * and keeps the invariant idempotency rests on: a pass never removes or
     * re-escapes a quote. The cost is that an apostrophe-quoted secret with a
     * double quote inside it ({@code {'password': 'pa"ss'}}) is cut at the quote —
     * which is exactly what the rule this scan replaced did too, so it is the
     * status quo on that leak and an improvement on everything else.
     * <li><b>End of input.</b> A value that never closes — a body cut short by the
     * preview cap — is redacted to the end. Everything after its opening quote IS
     * the secret, and a truncated body is exactly where a leak goes unnoticed.
     * </ul>
     * Deciding by escaping rather than by what FOLLOWS a quote is what closes three
     * leaks at once: an escaped quote followed by a comma
     * ({@code "ab\",cd-SECRET"}) no longer ends the value early; a free-text value
     * closes at its own quote rather than eating the rest of the line up to some
     * later one ({@code apiKey: "x" to host "y"}); and a pretty-printed nested body
     * closes at its {@code \"} even though what follows is {@code \r\n} rather than
     * a brace. Three further drafts qualified the apostrophe stop by what followed
     * the quote and a fuzzer broke each the same way: redaction changes what
     * follows a quote, so a bound that reads ahead lands elsewhere on a second
     * pass.
     * <p>
     * In every outcome the returned end excludes the closing token's own escaping,
     * read off THAT quote — not assumed equal to the opening's. A value opened
     * three backslashes deep can legitimately close on the enclosing string's bare
     * quote, and stripping three from in front of that turned a run of escaped
     * backslashes odd and the bare quote into an escaped one.
     */
    private static int endOfValue(String text, int from, char quote, int openingEscaping) {
        int depthUnit = openingEscaping + 1;
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
                    return i - escapingOf(backslashes);
                }
            } else if (c == '"' && quote == '\'') {
                return i - escapingOf(backslashes);
            }
            backslashes = 0;
        }
        return text.length();
    }

    /**
     * How many of the {@code b} backslashes in front of a quote are the quote's own
     * escaping, as opposed to escaped-backslash content before it.
     * <p>
     * A quote at depth {@code d} wears {@code 2^d - 1} backslashes of its own; each
     * escaped backslash of content in front of it adds {@code 2^d} more. So
     * {@code b + 1 = (content + 1) * 2^d}, and {@code 2^d} is the largest power of
     * two dividing {@code b + 1} — its lowest set bit. Zero for a bare quote or one
     * behind an even run, one for {@code \"} or {@code \\\\\"}, three for
     * {@code \\\"}, and so on.
     */
    private static int escapingOf(int backslashes) {
        return Integer.lowestOneBit(backslashes + 1) - 1;
    }

    /**
     * Whether a quoted value is, IN FULL, something that is not a secret and is
     * left exactly as it is: a vault reference, or a value an earlier rule has
     * already redacted.
     * <p>
     * Both exemptions ask that same question and both have to be whole-value
     * matches, because a prefix check is a bypass:
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
     * An exempt value is a TOKEN, not live text: nothing inside a vault reference
     * is a credential field, so the search resumes after it — never inside it. A
     * fuzzer found what resuming inside does: a reference whose key name happened
     * to contain {@code token:'} had its interior redacted, closing brace and all,
     * and a second pass then saw no reference and redacted the rest.
     */
    private static boolean isExempt(String value) {
        return SecretReference.compiledPattern().matcher(value).matches()
                || FULLY_REDACTED_VALUE.matcher(value).matches();
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
