/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.migration;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for TemplateSyntaxMigrator — Thymeleaf → Qute conversion.
 */
class TemplateSyntaxMigratorTest {

    private final TemplateSyntaxMigrator migrator = new TemplateSyntaxMigrator();

    // --- Null / empty ---

    @Test
    void migrateNull_returnsNull() {
        assertNull(migrator.migrate(null));
    }

    @Test
    void migrateEmpty_returnsEmpty() {
        assertEquals("", migrator.migrate(""));
    }

    @Test
    void migrateNoThymeleaf_unchanged() {
        String input = "Hello world, this is {already} Qute";
        assertEquals(input, migrator.migrate(input));
    }

    // --- Basic output expressions ---

    @Test
    void migrateEscapedOutput() {
        assertEquals("Hello {name}!", migrator.migrate("Hello [[${name}]]!"));
    }

    @Test
    void migrateUnescapedOutput() {
        assertEquals("{aiOutput.htmlResponseText}", migrator.migrate("[(${aiOutput.htmlResponseText})]"));
    }

    @Test
    void migrateDottedPath() {
        assertEquals("{memory.current.input}", migrator.migrate("[[${memory.current.input}]]"));
    }

    // --- Control structures ---

    @Test
    void migrateThEach() {
        String input = "[# th:each=\"item : ${items}\"][[${item}]][/]";
        String expected = "{#for item in items}{item}{/for}";
        assertEquals(expected, migrator.migrate(input));
    }

    @Test
    void migrateThIf() {
        String input = "[# th:if=\"${condition}\"]visible[/]";
        String expected = "{#if condition}visible{/if}";
        assertEquals(expected, migrator.migrate(input));
    }

    @Test
    void migrateNestedForAndIf() {
        String input = "[# th:each=\"x : ${list}\"]\n[# th:if=\"${x.active}\"][[${x.name}]][/]\n[/]";
        String expected = "{#for x in list}\n{#if x.active}{x.name}{/if}\n{/for}";
        assertEquals(expected, migrator.migrate(input));
    }

    @Test
    void migrateSingleLineForAndClose() {
        // Edge case: {#for} and [/] on the same line
        String input = "[# th:each=\"x : ${items}\"][[${x}]][/]";
        String expected = "{#for x in items}{x}{/for}";
        assertEquals(expected, migrator.migrate(input));
    }

    // --- #uuidUtils ---

    @Test
    void migrateUuidUtils() {
        assertEquals("{uuidUtils:extractId(location)}", migrator.migrate("[[${#uuidUtils.extractId(location)}]]"));
    }

    // --- #strings single-method calls ---

    @Test
    void migrateStringsToLowerCase() {
        assertEquals("{name.toLowerCase()}", migrator.migrate("[[${#strings.toLowerCase(name)}]]"));
    }

    @Test
    void migrateStringsToUpperCase() {
        assertEquals("{name.toUpperCase()}", migrator.migrate("[[${#strings.toUpperCase(name)}]]"));
    }

    @Test
    void migrateStringsTrim() {
        assertEquals("{input.trim()}", migrator.migrate("[[${#strings.trim(input)}]]"));
    }

    @Test
    void migrateStringsLength() {
        assertEquals("{name.length()}", migrator.migrate("[[${#strings.length(name)}]]"));
    }

    // --- #strings multi-arg calls ---

    @Test
    void migrateStringsReplace() {
        assertEquals("{name.replace(' ', '-')}", migrator.migrate("[[${#strings.replace(name, ' ', '-')}]]"));
    }

    @Test
    void migrateStringsSubstring() {
        assertEquals("{location.substring(37)}", migrator.migrate("[[${#strings.substring(location, 37)}]]"));
    }

    @Test
    void migrateStringsSubstringRange() {
        assertEquals("{s.substring(0, 5)}", migrator.migrate("[[${#strings.substring(s, 0, 5)}]]"));
    }

    @Test
    void migrateStringsIndexOf() {
        assertEquals("{s.indexOf('x')}", migrator.migrate("[[${#strings.indexOf(s, 'x')}]]"));
    }

    @Test
    void migrateStringsContains() {
        assertEquals("{s.contains('sub')}", migrator.migrate("[[${#strings.contains(s, 'sub')}]]"));
    }

    // --- #strings nested calls ---

    @Test
    void migrateStringsNestedCalls() {
        String input = "[[${#strings.toLowerCase(#strings.replace(name, ' ', '-'))}]]";
        String expected = "{name.replace(' ', '-').toLowerCase()}";
        assertEquals(expected, migrator.migrate(input));
    }

    // --- Multiple patterns in one string ---

    @Test
    void migrateMultiplePatterns() {
        String input = "ID: [[${#uuidUtils.extractId(location)}]], Name: [[${name}]]";
        String expected = "ID: {uuidUtils:extractId(location)}, Name: {name}";
        assertEquals(expected, migrator.migrate(input));
    }

    // --- String concatenation ---

    @Test
    void migrateStringConcat_separatorLiteral() {
        // [[${a + '/' + b}]] → first becomes {a + '/' + b} → then {a}/{b}
        String input = "[[${baseUrl + '/' + path}]]";
        String expected = "{baseUrl}/{path}";
        assertEquals(expected, migrator.migrate(input));
    }

    @Test
    void migrateStringConcat_dotsLiteral() {
        String input = "[[${prefix + '..' + suffix}]]";
        String expected = "{prefix}..{suffix}";
        assertEquals(expected, migrator.migrate(input));
    }

    @Test
    void migrateStringConcat_multiSegment() {
        String input = "[[${a + '/' + b + '/' + c}]]";
        String expected = "{a}/{b}/{c}";
        assertEquals(expected, migrator.migrate(input));
    }

    @Test
    void migrateStringConcat_leadingLiteral() {
        String input = "[[${'/api/' + resource}]]";
        String expected = "/api/{resource}";
        assertEquals(expected, migrator.migrate(input));
    }

    @Test
    void migrateStringConcat_unescapedOutputExpression() {
        assertEquals("{baseUrl}/{path}", migrator.migrate("[(${baseUrl + '/' + path})]"));
    }

    // --- a "+" inside the literal being concatenated ---

    /**
     * The splitter used to be {@code split("\\s*\\+\\s*")}, which cut the literal
     * apart and left a lone quote character as a part. A lone quote starts and ends
     * with a quote, so it was taken for a quoted literal and stripped with
     * {@code substring(1, 0)}: {@code StringIndexOutOfBoundsException}, which
     * aborted the startup migration for every remaining document in the database.
     */
    @Test
    void migrateStringConcat_literalIsAPlus() {
        assertEquals("{a}+{b}", migrator.migrate("[[${a + '+' + b}]]"));
    }

    @Test
    void migrateStringConcat_literalSurroundsAPlus() {
        assertEquals("{prefix} + {suffix}", migrator.migrate("[[${prefix + ' + ' + suffix}]]"));
    }

    @Test
    void migrateStringConcat_doubleQuotedLiteralIsAPlus() {
        assertEquals("{a}+{b}", migrator.migrate("[[${a + \"+\" + b}]]"));
    }

    /**
     * The shape of a template found in a real configuration: three literals
     * concatenated so that the rendered output is itself a template expression, for
     * a generated agent configuration. Malformed, never used, and it stopped the
     * whole migration. Whatever it converts to, it must not throw.
     */
    @Test
    void migrateStringConcat_nestedTemplateLiterals_doesNotThrow() {
        String input = "{\"targetServerUrl\":\"[['[[${'+'properties.apiBaseUrl'+'}]]']]\"}";
        String migrated = assertDoesNotThrow(() -> migrator.migrate(input));
        assertFalse(migrated.contains("[[${"), "the crashing expression survived: " + migrated);
    }

    // --- an escaped quote inside the literal (Copilot review, PR #781) ---

    /**
     * A backslash escapes the next character inside an OGNL literal. Without that,
     * the splitter leaves quote mode at the escaped apostrophe and reads the
     * following {@code +} as a concat operator, cutting the literal in half.
     */
    @Test
    void migrateStringConcat_escapedQuoteInsideLiteral() {
        // Thymeleaf source: [[${a + 'it\'s + here' + b}]]
        assertEquals("{a}it's + here{b}", migrator.migrate("[[${a + 'it\\'s + here' + b}]]"));
    }

    @Test
    void migrateStringConcat_escapedDoubleQuoteInsideLiteral() {
        // Thymeleaf source: [[${a + "say \"hi\" + bye" + b}]]
        assertEquals("{a}say \"hi\" + bye{b}", migrator.migrate("[[${a + \"say \\\"hi\\\" + bye\" + b}]]"));
    }

    /**
     * The escape only consumes the character after it, so a literal ending in an
     * escaped backslash still has its closing delimiter recognised.
     */
    @Test
    void migrateStringConcat_escapedBackslashAtEndOfLiteral() {
        // Thymeleaf source: [[${a + 'dir\\' + b}]] — the literal is `dir\`
        assertEquals("{a}dir\\{b}", migrator.migrate("[[${a + 'dir\\\\' + b}]]"));
    }

    /**
     * Escapes other than a quote or a backslash are left exactly as they are. OGNL
     * would read `\t` as a tab, but a Windows path in a config is the likelier
     * intent and silently rewriting it into control characters is the worse
     * mistake.
     */
    @Test
    void migrateStringConcat_otherBackslashSequencesAreLeftAlone() {
        // Thymeleaf source: [[${a + 'C:\temp' + b}]]
        assertEquals("{a}C:\\temp{b}", migrator.migrate("[[${a + 'C:\\temp' + b}]]"));
    }

    // --- B13: concatenation rewriting must not touch non-Thymeleaf content ---

    @Test
    void migrateJsonBodyWithArithmetic_leavesJsonUntouched() {
        // The body is migrated because of the Thymeleaf expression in it — the JSON
        // around that expression, including {"a": 1+2}, must survive verbatim.
        String input = "{\"a\": 1+2, \"user\": \"[[${properties.name}]]\"}";
        String expected = "{\"a\": 1+2, \"user\": \"{properties.name}\"}";
        assertEquals(expected, migrator.migrate(input));
    }

    @Test
    void migrateJsonBodyWithConcatenatedStringValue_leavesJsonUntouched() {
        String input = "{\"expr\": \"{total + surcharge}\", \"id\": \"[[${conversationInfo.conversationId}]]\"}";
        String expected = "{\"expr\": \"{total + surcharge}\", \"id\": \"{conversationInfo.conversationId}\"}";
        assertEquals(expected, migrator.migrate(input));
    }

    @Test
    void migrateThIfWithArithmetic_conditionIsNotSplit() {
        String input = "[# th:if=\"${count + offset}\"]visible[/]";
        String expected = "{#if count + offset}visible{/if}";
        assertEquals(expected, migrator.migrate(input));
    }

    // --- #json and #encoder namespaces ---

    @Test
    void migrateJsonNamespace() {
        assertEquals("{json:serialize(obj)}", migrator.migrate("[[${#json.serialize(obj)}]]"));
    }

    @Test
    void migrateEncoderNamespace() {
        assertEquals("{encoder:base64(data)}", migrator.migrate("[[${#encoder.base64(data)}]]"));
    }

    // --- Detection ---

    @Test
    void containsThymeleafSyntax_positive() {
        assertTrue(migrator.containsThymeleafSyntax("[[${var}]]"));
        assertTrue(migrator.containsThymeleafSyntax("[(${var})]"));
        assertTrue(migrator.containsThymeleafSyntax("th:each=\"x\""));
        assertTrue(migrator.containsThymeleafSyntax("th:if=\"cond\""));
        assertTrue(migrator.containsThymeleafSyntax("#strings.toLowerCase"));
        assertTrue(migrator.containsThymeleafSyntax("#strings.substring"));
        assertTrue(migrator.containsThymeleafSyntax("#uuidUtils.extractId"));
        assertTrue(migrator.containsThymeleafSyntax("#json.serialize"));
        assertTrue(migrator.containsThymeleafSyntax("#encoder.base64"));
    }

    // --- A brace inside a string literal ---

    /**
     * The expression is located by scanning, not by a pattern that stops at the
     * first closing brace. With the old quote-blind pattern this input matched
     * nowhere: the concat handling never saw it, the output patterns below failed
     * on it for the same reason, and the template was left in Thymeleaf syntax by a
     * migration that runs once and then records itself complete.
     */
    @Test
    void migrateConcat_withClosingBraceInsideALiteral() {
        assertEquals("{a}}{b}", migrator.migrate("[[${a + '}' + b}]]"));
    }

    @Test
    void migrateConcat_withOpeningBraceInsideALiteral() {
        assertEquals("{a}{{b}", migrator.migrate("[(${a + '{' + b})]"));
    }

    /**
     * The OGNL literal here is {@code 'it\'s}'}: the backslash keeps the apostrophe
     * inside the literal, so the brace after it is inside the literal too. Both the
     * escape and the brace have to be understood, or the scan ends in the wrong
     * place.
     */
    @Test
    void migrateConcat_withAnEscapedQuoteBeforeABrace() {
        assertEquals("{a}it's}{b}", migrator.migrate("[[${a + 'it\\'s}' + b}]]"));
    }

    /**
     * Nothing closes it, so nothing is known about where it ends. Rewriting on a
     * guess would corrupt document content, which is worse than leaving a template
     * for the operator to find.
     */
    @Test
    void migrateConcat_unterminatedExpressionIsLeftAlone() {
        String input = "before [[${a + 'x and the rest of the document";
        assertEquals(input, migrator.migrate(input));
    }

    @Test
    void migrateConcat_twoExpressionsOnOneLineBothConvert() {
        assertEquals("{a}/{b} and {c}-{d}",
                migrator.migrate("[[${a + '/' + b}]] and [[${c + '-' + d}]]"));
    }

    @Test
    void containsThymeleafSyntax_negative() {
        assertFalse(migrator.containsThymeleafSyntax(null));
        assertFalse(migrator.containsThymeleafSyntax(""));
        assertFalse(migrator.containsThymeleafSyntax("Plain text"));
        assertFalse(migrator.containsThymeleafSyntax("{quteVar}"));
        assertFalse(migrator.containsThymeleafSyntax("{\"json\": \"value\"}"));
    }
}
