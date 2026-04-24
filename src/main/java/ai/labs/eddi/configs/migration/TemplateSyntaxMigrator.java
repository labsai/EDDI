/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.migration;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
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

        String result = input;
        for (var entry : PATTERNS) {
            result = entry.getKey().matcher(result).replaceAll(entry.getValue());
        }
        result = migrateCloseTags(result);
        return migrateStringConcat(result);
    }

    // Pattern for string concatenation inside Qute expressions: {a + 'lit' + b}
    private static final Pattern CONCAT_PATTERN = Pattern.compile("\\{([^}]*?\\+[^}]*?)\\}");
    private static final Pattern CONCAT_OPERATOR = Pattern.compile("\\s*\\+\\s*");

    /**
     * Convert Thymeleaf/OGNL string concatenation to Qute inline expressions. e.g.
     * {a + '/' + b} → {a}/{b}, {a + '..' + b} → {a}..{b}
     */
    private String migrateStringConcat(String input) {
        if (!input.contains("+")) {
            return input;
        }
        Matcher m = CONCAT_PATTERN.matcher(input);
        var sb = new StringBuilder();
        while (m.find()) {
            String expr = m.group(1).trim();
            String[] parts = CONCAT_OPERATOR.split(expr);
            var replacement = new StringBuilder();
            for (String part : parts) {
                String trimmed = part.trim();
                if ((trimmed.startsWith("'") && trimmed.endsWith("'")) || (trimmed.startsWith("\"") && trimmed.endsWith("\""))) {
                    // String literal → inline without braces
                    replacement.append(trimmed.substring(1, trimmed.length() - 1));
                } else {
                    // Variable → wrap in Qute expression
                    replacement.append('{').append(trimmed).append('}');
                }
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement.toString()));
        }
        m.appendTail(sb);
        return sb.toString();
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
