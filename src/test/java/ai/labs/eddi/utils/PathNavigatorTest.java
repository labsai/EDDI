/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.utils;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class PathNavigatorTest {

    // === getValue: Simple dot-path ===

    @Test
    void shouldGetSimpleKey() {
        var map = Map.of("name", "EDDI");
        assertEquals("EDDI", PathNavigator.getValue("name", map));
    }

    @Test
    void shouldGetNestedPath() {
        var map = Map.of("a", Map.of("b", Map.of("c", "deep")));
        assertEquals("deep", PathNavigator.getValue("a.b.c", map));
    }

    @Test
    void shouldReturnNullForMissingPath() {
        var map = Map.of("a", Map.of("b", 1));
        assertNull(PathNavigator.getValue("a.x.y", map));
    }

    @Test
    void shouldReturnNullForNullPath() {
        assertNull(PathNavigator.getValue(null, Map.of()));
    }

    @Test
    void shouldReturnNullForEmptyPath() {
        assertNull(PathNavigator.getValue("", Map.of()));
    }

    @Test
    void shouldReturnNullForNullRoot() {
        assertNull(PathNavigator.getValue("a", null));
    }

    // === getValue: Array index ===

    @Test
    void shouldGetArrayElement() {
        var map = Map.of("items", List.of("a", "b", "c"));
        assertEquals("b", PathNavigator.getValue("items[1]", map));
    }

    @Test
    void shouldGetNestedPathWithArrayIndex() {
        var weather = Map.of("description", "sunny");
        var map = Map.of("httpCalls", Map.of("currentWeather", Map.of("weather", List.of(weather))));
        assertEquals("sunny", PathNavigator.getValue("httpCalls.currentWeather.weather[0].description", map));
    }

    @Test
    void shouldReturnNullForOutOfBoundsIndex() {
        var map = Map.of("items", List.of("a"));
        assertNull(PathNavigator.getValue("items[5]", map));
    }

    @Test
    void shouldReturnNullForNegativeIndex() {
        var map = Map.of("items", List.of("a"));
        assertNull(PathNavigator.getValue("items[-1]", map));
    }

    // === getValue: Arithmetic ===

    @Test
    void shouldAddIntegerToValue() {
        Map<String, Object> map = new HashMap<>();
        map.put("properties", new HashMap<>(Map.of("count", 5)));
        assertEquals(6, PathNavigator.getValue("properties.count+1", map));
    }

    @Test
    void shouldSubtractFromValue() {
        Map<String, Object> map = new HashMap<>();
        map.put("properties", new HashMap<>(Map.of("count", 10)));
        assertEquals(7, PathNavigator.getValue("properties.count-3", map));
    }

    @Test
    void shouldHandleDoubleArithmetic() {
        Map<String, Object> map = new HashMap<>();
        map.put("price", 9.99);
        assertEquals(10.99, (Double) PathNavigator.getValue("price+1", map), 0.001);
    }

    // === getValue: String concatenation ===

    @Test
    void shouldConcatenateStrings() {
        Map<String, Object> map = new HashMap<>();
        map.put("properties", new HashMap<>(Map.of("first", "John", "last", "Doe")));
        // "properties.first+' '" concatenates the value of properties.first with a
        // space literal
        assertEquals("John ", PathNavigator.getValue("properties.first+' '", map));
    }

    @Test
    void shouldConcatenateStringWithNumber() {
        Map<String, Object> map = new HashMap<>();
        map.put("name", "Agent");
        assertEquals("Agent1", PathNavigator.getValue("name+1", map));
    }

    // === getValue: Real-world patterns from EDDI configs ===

    @Test
    void shouldHandleEDDIResponseHeaderLocation() {
        // httpcalls postResponse pattern: "EDDIResponseHeader.Location"
        var map = Map.of("EDDIResponseHeader", Map.of("Location", "/resource/123"));
        assertEquals("/resource/123", PathNavigator.getValue("EDDIResponseHeader.Location", map));
    }

    @Test
    void shouldHandleMemoryCurrentInput() {
        // Integration test pattern: "memory.current.input"
        var map = Map.of("memory", Map.of("current", Map.of("input", "hello world")));
        assertEquals("hello world", PathNavigator.getValue("memory.current.input", map));
    }

    @Test
    void shouldHandleWeatherApiPattern() {
        // Integration test:
        // "memory.current.httpCalls.currentWeather.weather[0].description"
        var weather = Map.of("description", "light rain", "id", 500);
        Map<String, Object> map = Map.of("memory",
                Map.of("current", Map.of("httpCalls", Map.of("currentWeather", Map.of("weather", List.of(weather), "main", Map.of("temp", 72.5))))));
        assertEquals("light rain", PathNavigator.getValue("memory.current.httpCalls.currentWeather.weather[0].description", map));
        assertEquals(72.5, PathNavigator.getValue("memory.current.httpCalls.currentWeather.main.temp", map));
    }

    @Test
    void shouldHandlePropertiesCountPlusOne() {
        // Integration test: "properties.count+1"
        Map<String, Object> map = new HashMap<>();
        map.put("properties", new HashMap<>(Map.of("count", 3)));
        assertEquals(4, PathNavigator.getValue("properties.count+1", map));
    }

    @Test
    void shouldHandleSingleKey() {
        // Integration test: "currentWeather"
        var data = Map.of("temp", 25, "humidity", 60);
        Map<String, Object> map = Map.of("currentWeather", data);
        assertEquals(data, PathNavigator.getValue("currentWeather", map));
    }

    @Test
    void shouldHandlePropertiesAgentLocation() {
        // behavior rule valuePath: "properties.agentLocation"
        var map = Map.of("properties", Map.of("agentLocation", "cloud"));
        assertEquals("cloud", PathNavigator.getValue("properties.agentLocation", map));
    }

    // === setValue ===

    @Test
    void shouldSetSimpleValue() {
        Map<String, Object> map = new HashMap<>();
        map.put("name", "old");
        PathNavigator.setValue("name", map, "new");
        assertEquals("new", map.get("name"));
    }

    @Test
    void shouldSetNestedValue() {
        Map<String, Object> inner = new HashMap<>();
        inner.put("city", "Vienna");
        Map<String, Object> map = new HashMap<>();
        map.put("properties", inner);

        PathNavigator.setValue("properties.city", map, "Berlin");
        assertEquals("Berlin", inner.get("city"));
    }

    @Test
    void shouldSetValueInList() {
        List<Object> list = new ArrayList<>(List.of("a", "b", "c"));
        Map<String, Object> map = new HashMap<>();
        map.put("items", list);

        PathNavigator.setValue("items[1]", map, "B");
        assertEquals("B", list.get(1));
    }

    @Test
    void shouldNotThrowOnInvalidSetPath() {
        Map<String, Object> map = new HashMap<>();
        // Should not throw — just silently does nothing
        assertDoesNotThrow(() -> PathNavigator.setValue("a.b.c", map, "value"));
    }

    @Test
    void shouldNotThrowOnNullSetArgs() {
        assertDoesNotThrow(() -> PathNavigator.setValue(null, Map.of(), "value"));
        assertDoesNotThrow(() -> PathNavigator.setValue("a", null, "value"));
    }

    /**
     * A last segment the segment grammar does not recognise is a malformed path,
     * and setValue's contract is to write nothing rather than invent a key. Storing
     * {@code "items[abc]"} as a literal map key would leave a plausible-looking
     * entry that nothing can ever read back through the same path.
     */
    @Test
    void shouldWriteNothingWhenTheLastSegmentIsMalformed() {
        Map<String, Object> map = new HashMap<>();
        map.put("items", new ArrayList<>(List.of("a", "b")));

        PathNavigator.setValue("items[abc]", map, "X");

        assertEquals(1, map.size(), "no key may be invented: " + map);
        assertEquals(List.of("a", "b"), map.get("items"));
    }

    /** An indexed write against something that is not a list must do nothing. */
    @Test
    void shouldWriteNothingWhenTheIndexedTargetIsNotAList() {
        Map<String, Object> map = new HashMap<>();
        map.put("items", "not a list");

        PathNavigator.setValue("items[0]", map, "X");

        assertEquals("not a list", map.get("items"));
    }

    /**
     * An indexed write needs a MAP to look the key up in. A bare list as the root
     * offers no key to resolve, so the write is skipped rather than guessed at.
     */
    @Test
    void shouldWriteNothingWhenAnIndexedPathHasNoMapToResolveTheKeyIn() {
        List<Object> root = new ArrayList<>(List.of("a", "b"));

        assertDoesNotThrow(() -> PathNavigator.setValue("items[0]", root, "X"));

        assertEquals(List.of("a", "b"), root);
    }

    // --- Multi-operand concatenation (the class's own documented example) ---

    /**
     * The class Javadoc advertises {@code "properties.first+' '+properties.last"},
     * but ARITHMETIC_PATTERN splits reluctantly: the remainder
     * {@code ' '+properties.last} went straight to parseLiteral, which found no
     * closing quote, failed both number parses and returned the raw text. The
     * documented form therefore produced the literal garbage
     * {@code John' '+properties.last} — silently, into a conversation property or a
     * behaviour-rule comparison.
     */
    @Test
    void shouldEvaluateThreeOperandConcatenation() {
        Map<String, Object> root = Map.of("properties", Map.of("first", "John", "last", "Doe"));

        assertEquals("John Doe", PathNavigator.getValue("properties.first+' '+properties.last", root));
    }

    @Test
    void shouldStillEvaluateTwoOperandForms() {
        Map<String, Object> root = Map.of("properties", Map.of("first", "John", "last", "Doe", "count", 10));

        assertEquals("JohnDoe", PathNavigator.getValue("properties.first+properties.last", root));
        assertEquals("John ", PathNavigator.getValue("properties.first+' '", root));
        assertEquals(11, PathNavigator.getValue("properties.count+1", root));
        assertEquals(9, PathNavigator.getValue("properties.count-1", root));
    }

    // --- Hyphenated keys ---

    /**
     * A missing hyphenated key used to resolve to the value of a shorter path that
     * did exist: {@code properties.my-key} split into left={@code properties.my}
     * (10), op='-', right="key", and applyOperator returned the left operand
     * unchanged. Callers read null as "not found" and any non-null as a match, so
     * an absent key could make a behaviour rule fire or pull a neighbouring value
     * into a property, with no error and no log line.
     */
    @Test
    void shouldReturnNullForAbsentHyphenatedKeyWithAResolvablePrefix() {
        Map<String, Object> root = Map.of("properties", Map.of("my", 10));

        assertNull(PathNavigator.getValue("properties.my-key", root));
    }

    @Test
    void shouldStillResolveHyphenatedKeysThatExist() {
        Map<String, Object> root = Map.of("properties", Map.of("my-key", "present"));

        assertEquals("present", PathNavigator.getValue("properties.my-key", root));
    }

    /**
     * An opening quote with no closing quote is a malformed literal, and
     * {@code parseLiteral} used to fall through every branch and hand the raw text
     * back — so {@code properties.first+'oops} concatenated it and produced
     * {@code John'oops}, a broken expression dressed up as a plausible value.
     * <p>
     * The left operand must RESOLVE for this to test parseLiteral at all. The
     * earlier version of this test used {@code properties.missing-'oops}, whose
     * left path does not resolve, so {@code getValue} returned null at its own
     * {@code leftValue != null} guard and parseLiteral was never reached — it
     * passed against the old code just as happily (see
     * {@link #shouldReturnNullWhenTheLeftOperandDoesNotResolve}, which is what that
     * expression actually pins).
     */
    @Test
    void shouldDropAnUnterminatedStringLiteralInsteadOfConcatenatingItRaw() {
        Map<String, Object> root = Map.of("properties", Map.of("first", "John"));

        assertEquals("John", PathNavigator.getValue("properties.first+'oops", root));
    }

    @Test
    void shouldReturnNullWhenTheLeftOperandDoesNotResolve() {
        Map<String, Object> root = Map.of("properties", Map.of("first", "John"));

        assertNull(PathNavigator.getValue("properties.missing-'oops", root));
    }

    // --- Associativity and operators inside literals ---

    /**
     * The class Javadoc promises left-to-right evaluation, but the split pattern
     * was re-applied to the RIGHT-hand remainder, making it right-associative:
     * {@code count-1-1} became {@code count-(1-1)} and answered 10 instead of 8.
     * Silently — no error, no log line — through PropertySetterTask,
     * MatchingUtilities and SizeMatcher.
     */
    @Test
    void shouldFoldRepeatedSubtractionFromLeftToRight() {
        Map<String, Object> root = Map.of("properties", Map.of("count", 10));

        assertEquals(8, PathNavigator.getValue("properties.count-1-1", root));
        assertEquals(4, PathNavigator.getValue("properties.count-5-1", root));
        assertEquals(12, PathNavigator.getValue("properties.count+1+1", root));
        // Unequal operands, so the two associativities disagree in BOTH directions:
        // left-to-right is 10-1-2 = 7, right-associative would be 10-(1-2) = 11.
        assertEquals(7, PathNavigator.getValue("properties.count-1-2", root));
    }

    /**
     * An operator inside a quoted literal is part of the literal, not a separator.
     * Splitting before recognising quotes truncated
     * {@code properties.first+' - '+properties.last} — the most ordinary way to
     * join two names — to just {@code John}: the remainder was split again at the
     * hyphen inside the literal, every parse below it failed, and an empty string
     * was concatenated.
     */
    @Test
    void shouldTreatOperatorsInsideStringLiteralsAsText() {
        Map<String, Object> root = Map.of("properties", Map.of("first", "John", "last", "Doe"));

        assertEquals("John-Doe", PathNavigator.getValue("properties.first+'-'+properties.last", root));
        assertEquals("John - Doe", PathNavigator.getValue("properties.first+' - '+properties.last", root));
        assertEquals("John+Doe", PathNavigator.getValue("properties.first+'+'+properties.last", root));
    }

    /**
     * Tokenising must not cost the hyphenated-key support: a key that really
     * contains a hyphen has to keep resolving as one path segment even when it is
     * not the first operand of the expression.
     */
    @Test
    void shouldStillResolveAHyphenatedKeyAsTheRightOperand() {
        Map<String, Object> root = Map.of("properties", Map.of("first", "John", "my-key", "present", "my", "shorter"));

        assertEquals("Johnpresent", PathNavigator.getValue("properties.first+properties.my-key", root));
    }

    /**
     * A sign belongs to the operand it precedes.
     * <p>
     * Splitting on every top-level {@code +}/{@code -} turned {@code count+-1} into
     * {@code count}, {@code +}, EMPTY, {@code -}, {@code 1}: the empty operand
     * parsed as null, the concatenation branch folded it into the String
     * {@code "10"}, and {@code "10" - 1} is not an expression at all — so a
     * template that adds a negative literal answered "not found", and the rule
     * stopped matching or the property stopped being set.
     */
    @Test
    void shouldEvaluateASignedNumericLiteralAsOneOperand() {
        Map<String, Object> root = Map.of("properties", Map.of("count", 10));

        assertEquals(9, PathNavigator.getValue("properties.count+-1", root));
        assertEquals(11, PathNavigator.getValue("properties.count--1", root));
        assertEquals(9, PathNavigator.getValue("properties.count + -1", root));
        assertEquals(11.5, PathNavigator.getValue("properties.count--1.5", root));
    }

    /**
     * A dangling operator is a malformed expression, and the only honest answer is
     * "not found". Folding the left operand with an empty right one produced the
     * String {@code "10"} — a plausible-looking value out of a broken expression,
     * which is precisely the failure class this class refuses everywhere else.
     */
    @Test
    void shouldRefuseAnExpressionWithADanglingOperator() {
        Map<String, Object> root = Map.of("properties", Map.of("count", 10));

        assertNull(PathNavigator.getValue("properties.count+", root));
        assertNull(PathNavigator.getValue("properties.count-", root));
        assertNull(PathNavigator.getValue("properties.count+1+", root));
        assertNull(PathNavigator.getValue("properties.count+ ", root));
    }
}
