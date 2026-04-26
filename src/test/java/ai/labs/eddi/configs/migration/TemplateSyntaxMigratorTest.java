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

    @Test
    void containsThymeleafSyntax_negative() {
        assertFalse(migrator.containsThymeleafSyntax(null));
        assertFalse(migrator.containsThymeleafSyntax(""));
        assertFalse(migrator.containsThymeleafSyntax("Plain text"));
        assertFalse(migrator.containsThymeleafSyntax("{quteVar}"));
        assertFalse(migrator.containsThymeleafSyntax("{\"json\": \"value\"}"));
    }
}
