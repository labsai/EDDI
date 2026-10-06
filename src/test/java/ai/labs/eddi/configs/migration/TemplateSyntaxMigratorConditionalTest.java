/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.migration;

import io.quarkus.qute.Engine;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Thymeleaf conditionals ({@code cond ? a : b}) converted to Qute, and the
 * parse check that keeps a conversion Qute rejects from being counted as
 * migrated. The converted templates are rendered by a real engine, not compared
 * as text.
 */
@DisplayName("TemplateSyntaxMigrator — conditionals and the Qute parse check")
class TemplateSyntaxMigratorConditionalTest {

    /**
     * A nested conditional with concatenation, as found in a production 5.x config.
     */
    private static final String NESTED = "[[${userInfo.chapterNumber > 0 ? 'session ' + userInfo.chapterNumber + "
            + "(userInfo.actionNumber > 0 ? ', inside action screen ' + userInfo.actionNumber : ' overview screen') "
            + ": 'program overview screen'}]]";

    private final TemplateSyntaxMigrator migrator = new TemplateSyntaxMigrator();
    private final Engine engine = Engine.builder().addDefaults().strictRendering(false).build();

    private String render(String template, Map<String, Object> userInfo) {
        Map<String, Object> data = new HashMap<>();
        data.put("userInfo", userInfo);
        return engine.parse(template).data(data).render();
    }

    @Test
    @DisplayName("the nested conditional converts to the expected Qute")
    void nestedConditionalConvertsToIfElse() {
        assertEquals("{#if userInfo.chapterNumber && userInfo.chapterNumber > 0}session {userInfo.chapterNumber}"
                + "{#if userInfo.actionNumber && userInfo.actionNumber > 0}, inside action screen {userInfo.actionNumber}"
                + "{#else} overview screen{/if}{#else}program overview screen{/if}", migrator.migrate(NESTED));
    }

    @Test
    @DisplayName("the converted nested conditional parses, and renders all four combinations")
    void nestedConditionalRendersEveryCombination() {
        String qute = migrator.migrate(NESTED);
        assertNull(migrator.quteParseError(qute));

        assertEquals("program overview screen", render(qute, Map.of("chapterNumber", 0, "actionNumber", 0)));
        assertEquals("program overview screen", render(qute, Map.of()), "a missing chapterNumber must not fail the render");
        assertEquals("session 3 overview screen", render(qute, Map.of("chapterNumber", 3, "actionNumber", 0)));
        assertEquals("session 3 overview screen", render(qute, Map.of("chapterNumber", 3)));
        assertEquals("session 3, inside action screen 2", render(qute, Map.of("chapterNumber", 3, "actionNumber", 2)));
    }

    @Test
    @DisplayName("a conditional inside a Thymeleaf loop does not steal the [/] that closes the loop")
    void conditionalDoesNotUpsetCloseTags() {
        String qute = migrator.migrate("[# th:each=\"i : ${items}\"][[${userInfo.n > 0 ? 'x' : 'y'}]][/] end");
        assertEquals("{#for i in items}{#if userInfo.n && userInfo.n > 0}x{#else}y{/if}{/for} end", qute);
        assertNull(migrator.quteParseError(qute));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource(delimiter = '|',
               value = {"[[${userInfo.a == 'x' ? 'is x' : 'not x'}]]|a|x|is x", "[[${userInfo.a == 'x' ? 'is x' : 'not x'}]]|a|z|not x",
                       "[[${userInfo.a != 'x' ? 'not x' : userInfo.a}]]|a|x|x", "[[${userInfo.a && !userInfo.b ? 'yes' : 'no'}]]|a|true|yes",
                       "[[${(userInfo.a or userInfo.b) and userInfo.c ? 'yes' : 'no'}]]|c|true|no"})
    @DisplayName("string comparison, negation, and/or and a path branch")
    void conditionVariants(String template, String key, String value, String expected) {
        String qute = migrator.migrate(template);
        assertNull(migrator.quteParseError(qute), qute);
        Object v = "true".equals(value) ? Boolean.TRUE : value;
        assertEquals(expected, render(qute, Map.of(key, v)));
    }

    @Test
    @DisplayName("a literal holding a brace in a branch is output verbatim, not opened as an expression")
    void braceLiteralIsNotAnExpression() {
        String qute = migrator.migrate("[[${userInfo.a ? '{name}' : 'none'}]]");
        assertNull(migrator.quteParseError(qute));
        assertEquals("{name}", render(qute, Map.of("a", true)));
    }

    @Test
    @DisplayName("a comparison whose zero case would change under the guard is not converted by the conditional converter")
    void unguardableComparisonIsNotConverted() {
        // x >= 0 would be turned into 'x && x >= 0', which is false for x == 0
        assertNull(OgnlTernaryConverter.convert("userInfo.n >= 0 ? 'a' : 'b'"));
        assertNull(OgnlTernaryConverter.convert("userInfo.n < 5 ? 'a' : 'b'"));
        assertNull(OgnlTernaryConverter.convert("userInfo.n + 1 > 0 ? 'a' : 'b'"));
        assertNull(OgnlTernaryConverter.convert("user.name() ? 'a' : 'b'"));
        assertNull(OgnlTernaryConverter.convert("userInfo.n > 0 ? 'a' : 'b' trailing"));
    }

    @Test
    @DisplayName("an Elvis operator is not a conditional")
    void elvisIsNotAConditional() {
        assertEquals(false, OgnlTernaryConverter.hasConditional("a ?: 'b'"));
        assertEquals(false, OgnlTernaryConverter.hasConditional("a ?.b"));
        assertEquals(false, OgnlTernaryConverter.hasConditional("a + '?'"));
        assertEquals(true, OgnlTernaryConverter.hasConditional("a ? 'b' : 'c'"));
    }

    @Test
    @DisplayName("the parse check rejects the syntax the old converter produced, and accepts valid Qute with unknown namespaces")
    void parseCheck() {
        String broken = "{(userInfo.chapterNumber > 0 ? 'session '}{userInfo.chapterNumber}{userInfo.actionNumber : ' overview screen') : 'x')}";
        assertNotNull(migrator.quteParseError(broken));
        assertNotNull(migrator.quteParseError("{#if a}unclosed"));
        assertNull(migrator.quteParseError("{#if a}x{/if} {vars:thing} {json:serialize(x)} {vault:key} {#for i in list}{i}{/for}"));
        assertNull(migrator.quteParseError("plain text {\"json\": 1}"));
        assertNull(migrator.quteParseError(null));
    }
}
