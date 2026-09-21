/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.migration;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Converts Thymeleaf TEXT-mode template syntax to Qute. Used by
 * {@link V6QuteMigration} (startup) and the import pipeline.
 *
 * @since 6.0.0
 */
@ApplicationScoped
public class TemplateSyntaxMigrator {

    private static final List<Map.Entry<Pattern, String>> PATTERNS = List.of(
            // 1. #strings nested calls: #strings.outer(#strings.inner(var, args)) →
            // var.inner(args).outer()
            Map.entry(Pattern.compile("\\[\\[\\$\\{#strings\\.([a-zA-Z]+)\\(#strings\\.([a-zA-Z]+)\\(" + "([^,)]+)(?:,\\s*([^)]*?))?\\)\\)\\}\\]\\]"),
                    "{$3.$2($4).$1()}"),
            // 2. #strings.method(var, arg1, arg2) → var.method(arg1, arg2)
            Map.entry(Pattern.compile("#strings\\.([a-zA-Z]+)\\(([^,)]+),\\s*([^)]+)\\)"), "$2.$1($3)"),
            // 3. #strings.method(var) → var.method()
            Map.entry(Pattern.compile("#strings\\.([a-zA-Z]+)\\(([^)]+)\\)"), "$2.$1()"),
            // 4. th:each iteration
            Map.entry(Pattern.compile("\\[#\\s*th:each=\"(\\w+)\\s*:\\s*\\$\\{([^}]+)\\}\"\\]"), "{#for $1 in $2}"),
            // 5. th:if conditional
            Map.entry(Pattern.compile("\\[#\\s*th:if=\"\\$\\{([^}]+)\\}\"\\]"), "{#if $1}"),
            // 6. [(${var})] unescaped output
            Map.entry(Pattern.compile("\\[\\(\\$\\{([^}]+)\\}\\)\\]"), "{$1}"),
            // 7. [[${var}]] escaped output
            Map.entry(Pattern.compile("\\[\\[\\$\\{([^}]+)\\}\\]\\]"), "{$1}"),
            // 8. #uuidUtils namespace
            Map.entry(Pattern.compile("#uuidUtils\\."), "uuidUtils:"),
            // 9. #json namespace
            Map.entry(Pattern.compile("#json\\."), "json:"),
            // 10. #encoder namespace
            Map.entry(Pattern.compile("#encoder\\."), "encoder:"));

    /**
     * Migrate a string from Thymeleaf to Qute syntax. Returns input unchanged if no
     * Thymeleaf patterns are found.
     */
    public String migrate(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }

        // concatenation is resolved first, while the Thymeleaf delimiters are still
        // there to prove the expression is Thymeleaf and not ordinary document content
        String result = migrateStringConcat(input);
        for (var entry : PATTERNS) {
            result = entry.getKey().matcher(result).replaceAll(entry.getValue());
        }
        return migrateCloseTags(result);
    }

    /**
     * The two Thymeleaf output forms, opening delimiter to closing delimiter:
     * {@code [[${…}]]} escapes, {@code [(${…})]} does not.
     *
     * <p>
     * Anchored to these delimiters on purpose — an unanchored <code>{…+…}</code>
     * also matches JSON bodies ({@code {"a": 1+2}}) and plain arithmetic that
     * merely happen to live in a document containing Thymeleaf syntax elsewhere.
     * </p>
     */
    private static final String[][] OUTPUT_DELIMITERS = {{"[[${", "}]]"}, {"[(${", "})]"}};

    /** The escape character inside an OGNL string literal. */
    private static final char ESCAPE = '\\';

    /**
     * Convert Thymeleaf/OGNL string concatenation to Qute inline expressions. e.g.
     * [[${a + '/' + b}]] → {a}/{b}, [[${a + '..' + b}]] → {a}..{b}
     */
    private String migrateStringConcat(String input) {
        if (!input.contains("+")) {
            return input;
        }
        var out = new StringBuilder(input.length());
        int cursor = 0;
        while (cursor < input.length()) {
            int open = -1;
            String[] delimiters = null;
            for (String[] candidate : OUTPUT_DELIMITERS) {
                int at = input.indexOf(candidate[0], cursor);
                if (at >= 0 && (open < 0 || at < open)) {
                    open = at;
                    delimiters = candidate;
                }
            }
            if (open < 0) {
                break;
            }
            int bodyStart = open + delimiters[0].length();
            int close = closingDelimiter(input, bodyStart, delimiters[1]);
            if (close < 0) {
                // Unterminated as far as this scan can tell. Copy the opening
                // delimiter through and carry on rather than guessing where the
                // expression ends — a wrong guess rewrites document content.
                out.append(input, cursor, bodyStart);
                cursor = bodyStart;
                continue;
            }
            out.append(input, cursor, open);
            String expr = input.substring(bodyStart, close).trim();
            if (expr.indexOf('+') >= 0) {
                out.append(concatToQute(expr));
            } else {
                // No concatenation: left exactly as it is, for the output patterns
                // below to convert.
                out.append(input, open, close + delimiters[1].length());
            }
            cursor = close + delimiters[1].length();
        }
        out.append(input, Math.min(cursor, input.length()), input.length());
        return out.toString();
    }

    /**
     * The index of the closing delimiter of an expression that starts at
     * {@code from}, or {@code -1} when there is none.
     *
     * <p>
     * A scan rather than a regex because the delimiter cannot be found by looking
     * for the first {@code }}: an OGNL string literal may contain one. {@code [[${a
     * + '}' + b}]]} defeated the old {@code [^}]*?} pattern entirely — it matched
     * nowhere, so the expression never reached {@link #splitOnConcatOperator}, the
     * output patterns further down failed on it for the same reason, and the
     * template was left in Thymeleaf syntax on a migration that runs once and then
     * records itself complete. That is the same defect
     * {@code splitOnConcatOperator} fixed one level down, at the operator rather
     * than at the delimiter.
     * </p>
     */
    private static int closingDelimiter(String input, int from, String closing) {
        char openQuote = 0;
        boolean escaped = false;
        for (int i = from; i < input.length(); i++) {
            char c = input.charAt(i);
            if (openQuote != 0) {
                if (escaped) {
                    escaped = false;
                } else if (c == ESCAPE) {
                    escaped = true;
                } else if (c == openQuote) {
                    openQuote = 0;
                }
            } else if (c == '\'' || c == '"') {
                openQuote = c;
            } else if (input.startsWith(closing, i)) {
                return i;
            }
        }
        return -1;
    }

    /** One Thymeleaf concat expression, rendered as Qute. */
    private static String concatToQute(String expr) {
        var replacement = new StringBuilder();
        for (String part : splitOnConcatOperator(expr)) {
            String trimmed = part.trim();
            if (isStringLiteral(trimmed)) {
                // String literal → inline without braces
                replacement.append(literalText(trimmed));
            } else if (!trimmed.isEmpty()) {
                // Variable → wrap in Qute expression. An empty part is not a variable:
                // it only arises from a leading, trailing or doubled +, i.e. from a
                // malformed expression, and `{}` would be a broken Qute expression where
                // nothing at all is merely a dropped empty operand.
                replacement.append('{').append(trimmed).append('}');
            }
        }
        return replacement.toString();
    }

    /**
     * Splits a concat expression on its {@code +} operators, ignoring any {@code +}
     * that sits inside a string literal.
     *
     * <p>
     * This used to be {@code split("\\s*\\+\\s*")}, which cuts literals apart:
     * {@code 'a+b'} became {@code 'a} and {@code b'}, and a literal plus
     * ({@code '+'}) became two lone quote characters. A lone quote both starts and
     * ends with a quote, so the caller took it for a quoted literal and stripped
     * its delimiters with {@code substring(1, 0)} — a
     * {@link StringIndexOutOfBoundsException} that, before {@link V6QuteMigration}
     * isolated documents from one another, aborted the Thymeleaf-to-Qute migration
     * for the whole database over one such template.
     * </p>
     *
     * <p>
     * A backslash escapes the next character while inside a literal, so
     * {@code 'it\'s + here'} stays one part: without that, the escaped apostrophe
     * would close the literal and the {@code +} after it would be read as an
     * operator.
     * </p>
     */
    private static List<String> splitOnConcatOperator(String expr) {
        var parts = new ArrayList<String>();
        var current = new StringBuilder();
        char openQuote = 0;
        boolean escaped = false;
        for (int i = 0; i < expr.length(); i++) {
            char c = expr.charAt(i);
            if (openQuote != 0) {
                current.append(c);
                if (escaped) {
                    escaped = false;
                } else if (c == ESCAPE) {
                    escaped = true;
                } else if (c == openQuote) {
                    openQuote = 0;
                }
            } else if (c == '\'' || c == '"') {
                openQuote = c;
                current.append(c);
            } else if (c == '+') {
                parts.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        parts.add(current.toString());
        return parts;
    }

    /**
     * A quoted string literal, i.e. something whose delimiters can be stripped.
     * Length two is the minimum: a single quote character starts and ends with a
     * quote but has no delimiters to strip. Both delimiters must be the same kind
     * of quote, so {@code 'x"} is a (broken) variable rather than a literal.
     */
    private static boolean isStringLiteral(String value) {
        return value.length() >= 2
                && ((value.startsWith("'") && value.endsWith("'")) || (value.startsWith("\"") && value.endsWith("\"")));
    }

    /**
     * The text of a quoted literal: delimiters removed, and an escaped quote or
     * backslash reduced to the character it stood for.
     *
     * <p>
     * The unescaping is what the delimiters imply. Thymeleaf renders the literal
     * {@code 'it\'s'} as {@code it's}, and the Qute conversion inlines that text
     * verbatim, so leaving the backslash in would put it on the screen. Only
     * {@code \'}, {@code \"} and {@code \\} are reduced: the other OGNL escapes
     * ({@code \t}, {@code \n}, …) are left exactly as they are rather than guessed
     * at, since those are the ones where a Windows path in a config would be
     * silently rewritten into control characters.
     * </p>
     */
    private static String literalText(String literal) {
        String body = literal.substring(1, literal.length() - 1);
        if (body.indexOf(ESCAPE) < 0) {
            return body;
        }
        var text = new StringBuilder(body.length());
        for (int i = 0; i < body.length(); i++) {
            char c = body.charAt(i);
            if (c == ESCAPE && i + 1 < body.length() && isEscapableInLiteral(body.charAt(i + 1))) {
                text.append(body.charAt(i + 1));
                i++;
            } else {
                text.append(c);
            }
        }
        return text.toString();
    }

    private static boolean isEscapableInLiteral(char c) {
        return c == '\'' || c == '"' || c == ESCAPE;
    }

    /**
     * Stack-based {@code [/]} → {@code {/for}} or {@code {/if}} conversion.
     */
    private String migrateCloseTags(String input) {
        if (!input.contains("[/]")) {
            return input;
        }

        var stack = new ArrayDeque<String>();
        var sb = new StringBuilder();
        int i = 0;
        while (i < input.length()) {
            // Detect {#for ...} or {#if ...} to push onto the stack
            if (input.startsWith("{#for", i)) {
                stack.push("for");
            } else if (input.startsWith("{#if", i)) {
                stack.push("if");
            }

            // Replace [/] with the correct close tag
            if (input.startsWith("[/]", i) && !stack.isEmpty()) {
                sb.append("{/").append(stack.pop()).append('}');
                i += 3; // skip past [/]
            } else {
                sb.append(input.charAt(i));
                i++;
            }
        }
        return sb.toString();
    }

    /**
     * Quick check: does this string contain any Thymeleaf template syntax?
     */
    public boolean containsThymeleafSyntax(String input) {
        return input != null && (input.contains("[[${") || input.contains("[(${") || input.contains("th:each") || input.contains("th:if")
                || input.contains("#strings.") || input.contains("#uuidUtils.") || input.contains("#json.") || input.contains("#encoder."));
    }
}
