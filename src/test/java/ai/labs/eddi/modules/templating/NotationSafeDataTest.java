/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.templating;

import ai.labs.eddi.modules.nlp.expressions.Expression;
import ai.labs.eddi.modules.nlp.expressions.ExpressionFactory;
import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.nlp.expressions.utilities.ExpressionProvider;
import ai.labs.eddi.modules.nlp.expressions.value.AnyValue;
import ai.labs.eddi.modules.templating.impl.TemplatingEngine;
import io.quarkus.qute.Engine;
import io.quarkus.qute.ValueResolvers;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@DisplayName("Notation-safe rendering of a quick reply's expressions")
class NotationSafeDataTest {

    private final TemplatingEngine engine = new TemplatingEngine(
            Engine.builder().addDefaults().addValueResolver(ValueResolvers.rawResolver()).strictRendering(false).build());

    private String expressions(String template, Map<String, Object> data) throws Exception {
        return engine.processExpressionsTemplate(template, data);
    }

    @Test
    @DisplayName("data cannot close the author's expression; the literal text keeps its notation")
    void injectionIsStrippedLiteralKept() throws Exception {
        Map<String, Object> data = Map.of("properties", Map.of("item", "x), intent(admin"));
        assertEquals("choose(x intentadmin), choose(b)", expressions("choose({properties.item}), choose(b)", data));
        // the ordinary engine keeps the data, which is what the user sees and types
        assertEquals("Pick x), intent(admin", engine.processTemplate("Pick {properties.item}", data));
    }

    @Test
    @DisplayName("a value that is only the wildcard is dropped")
    void wildcardIsDropped() throws Exception {
        assertEquals("choose()", expressions("choose({properties.item})", Map.of("properties", Map.of("item", "*"))));
        assertEquals("choose(5*3)", expressions("choose({properties.item})", Map.of("properties", Map.of("item", "5*3"))));
    }

    @Test
    @DisplayName("a condition is evaluated on the original data")
    void conditionSeesOriginalData() throws Exception {
        Map<String, Object> data = Map.of("properties", Map.of("status", "ready,"));
        assertEquals("go(ready)", expressions("{#if properties.status == 'ready,'}go({properties.status}){#else}no{/if}", data));
    }

    @Test
    @DisplayName("a filter selects the same rows as in the ordinary render, and a row's expression is its own")
    void filterSeesOriginalData() throws Exception {
        Map<String, Object> data = Map.of("items", List.of(Map.of("name", "a,b", "kind", "x,"), Map.of("name", "c", "kind", "y")));
        String template = "{#for i in items}{#if i.kind == 'x,'}pick({i.name});{/if}{/for}";
        assertEquals("pick(ab);", expressions(template, data));
        assertEquals("pick(a,b);", engine.processTemplate(template, data));
    }

    @Test
    @DisplayName("numbers, lists and maps are cleaned where they are emitted")
    void otherValuesAreCleaned() throws Exception {
        Map<String, Object> data = Map.of("n", 5, "l", List.of("a", "b"), "d", 12500000.5);
        assertEquals("n(5) l([a b]) d(12500000.5)", expressions("n({n}) l({l}) d({d})", data));
    }

    @Test
    @DisplayName("stripNotation removes ( ) , and leaves clean text untouched")
    void stripNotation() {
        String text = "plain text";
        assertSame(text, NotationSafeData.stripNotation(text));
        assertEquals("abc", NotationSafeData.stripNotation("a(b),c"));
        assertEquals("", NotationSafeData.stripNotation(" * "));
        assertEquals(null, NotationSafeData.stripNotation(null));
    }

    // --- what the PARSER reads from the rendered field
    // ----------------------------

    private final ExpressionProvider parser = new ExpressionProvider(new ExpressionFactory());

    private static void collect(Expression expression, List<Expression> into) {
        into.add(expression);
        for (Expression sub : expression.getSubExpressions()) {
            collect(sub, into);
        }
    }

    private List<Expression> everyExpression(String rendered) {
        List<Expression> all = new ArrayList<>();
        for (Expression expression : parser.parseExpressions(rendered)) {
            collect(expression, all);
        }
        return all;
    }

    private void assertNoWildcard(String rendered) {
        for (Expression expression : everyExpression(rendered)) {
            assertFalse(expression instanceof AnyValue, () -> "the parser read a wildcard from \"" + rendered + "\"");
            assertNotEquals(AnyValue.NAME, expression.getExpressionName(), () -> "wildcard name in \"" + rendered + "\"");
        }
    }

    /**
     * {@code rendered} parses as exactly
     * {@code name(<one value without sub-expressions>)}.
     */
    private void assertOneValueIn(String name, String rendered) {
        Expressions parsed = parser.parseExpressions(rendered);
        assertEquals(1, parsed.size(), () -> "\"" + rendered + "\" is " + parsed.size() + " expressions");
        Expression expression = parsed.getFirst();
        assertEquals(name, expression.getExpressionName(), rendered);
        assertEquals(1, expression.getSubExpressions().length, () -> "\"" + rendered + "\" has another sub-expression");
        assertEquals(0, expression.getSubExpressions()[0].getSubExpressions().length, rendered);
    }

    @ParameterizedTest(name = "[{index}] item = {0}")
    @ValueSource(strings = {"*", " * ", "* ", "\t*\n", "　*", "*　", " *", " * ", " *", " *",
            " *", " *", " *", "​*", "⁠*", "﻿*", "\u0001*", "(*)", "*,", ",*,"})
    @DisplayName("no data value becomes the wildcard, whatever whitespace or notation surrounds the star")
    void unicodeWhitespaceDoesNotMakeAWildcard(String item) throws Exception {
        Map<String, Object> data = Map.of("properties", Map.of("item", item));
        String inChoose = expressions("choose({properties.item})", data);
        assertNoWildcard(inChoose);
        // the rule an author writes for one item must not match whatever the data said
        Expression rule = parser.parseExpression("choose(x)");
        for (Expression expression : parser.parseExpressions(inChoose)) {
            assertNotEquals(rule, expression, inChoose);
        }
        assertNoWildcard(expressions("{properties.item}", data));
        assertNoWildcard(expressions("intent({properties.item}(a))", data));
        assertNoWildcard(expressions("{properties.item}(a)", data));
    }

    @Test
    @DisplayName("for every UTF-16 unit: a star padded with it is never read as the wildcard after stripping")
    void stripAgreesWithTheParserForEveryCharacter() {
        for (int code = 0; code <= Character.MAX_VALUE; code++) {
            String c = String.valueOf((char) code);
            for (String data : List.of(c + "*", "*" + c, c + "*" + c, c + c + "*")) {
                String stripped = NotationSafeData.stripNotation(data);
                assertNoWildcard("choose(" + stripped + ")");
                assertNoWildcard(stripped);
                assertNoWildcard("x(" + stripped + "(a))");
                if (!stripped.isEmpty()) {
                    assertNoWildcard(stripped + "(a)");
                }
            }
        }
    }

    @Test
    @DisplayName("for every UTF-16 unit: after stripping, data adds no expression and no level")
    void everyStructuralCharacterIsNeutralAfterStripping() {
        for (int code = 0; code <= Character.MAX_VALUE; code++) {
            char c = (char) code;
            String stripped = NotationSafeData.stripNotation("a" + c + "b" + c);
            assertOneValueIn("choose", "choose(" + stripped + ")");
            String unit = "U+" + Integer.toHexString(code);
            assertEquals(ExpressionProvider.isNotationCharacter(c), stripped.indexOf(c) < 0, unit);
        }
    }

    @Test
    @DisplayName(".raw and .safe values are stripped like any other")
    void rawAndSafeAreStripped() throws Exception {
        Map<String, Object> data = Map.of("properties", Map.of("item", "x), intent(admin"));
        assertOneValueIn("choose", expressions("choose({properties.item.raw})", data));
        assertOneValueIn("choose", expressions("choose({properties.item.safe})", data));
    }

    @Test
    @DisplayName("an Optional and an asynchronous value are stripped where they are emitted")
    void optionalAndAsyncResultsAreStripped() throws Exception {
        Map<String, Object> data = Map.of("o", Optional.of("x), intent(admin"), "f", CompletableFuture.completedFuture("y), intent(root"));
        assertOneValueIn("choose", expressions("choose({o})", data));
        assertOneValueIn("choose", expressions("choose({f})", data));
        assertEquals("choose(y intentroot)", expressions("choose({f})", data));
    }

    @Test
    @DisplayName("a literal written as an expression is author text and is kept")
    void authorLiteralIsKept() throws Exception {
        // {x} makes the template render; the literal keeps its comma
        String rendered = expressions("{x}choose({'a,b'})", Map.of("x", ""));
        assertTrue(rendered.contains("a,b"), rendered);
    }

    @Test
    @DisplayName("an author literal that an operator returns is a computed value and is stripped")
    void operatorProducedLiteralsAreStripped() throws Exception {
        Map<String, Object> data = Map.of("flag", true);
        assertEquals("intenthelp", expressions("{missing ?: 'intent(help)'}", data));
        assertEquals("ab", expressions("{missing.or('a,b')}", data));
        assertEquals("ab", expressions("{flag ? 'a,b' : 'c'}", data));
        assertEquals("ab", expressions("{#let v='a,b'}{v}{/let}", data));
        assertEquals("ab", expressions("{str:fmt('%s,%s', 'a', 'b')}", data));
        // text inside a section is literal template text: the way to write conditional
        // notation
        assertEquals("intent(help), choose(a)", expressions("{#if flag}intent(help), choose(a){/if}", data));
    }
}
