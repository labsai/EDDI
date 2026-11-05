/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.datastore.serialization.JsonSerialization;
import ai.labs.eddi.modules.llm.impl.ModelOutputParser.JsonOutcome;
import ai.labs.eddi.modules.llm.impl.ModelOutputParser.Kind;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The four failure shapes seen in production, repaired locally (no model call)
 * through the shared parse path: plain text, a double-escaped object, a wrong
 * field name and the empty object. Everything is opt-in through
 * {@link RescueOptions}; {@link RescueOptions#NONE} is today's behaviour.
 */
@DisplayName("ModelOutputParser — local rescue of near-miss replies")
class ModelOutputParserRescueTest {

    private static final String FIELD = "htmlResponseText";

    private final ModelOutputParser parser = new ModelOutputParser(new JsonSerialization(new ObjectMapper()));

    private static RescueOptions wrap() {
        return new RescueOptions(FIELD, Map.of("quickReplies", List.of()), RescueOptions.DEFAULT_WRAP_MAX_CHARS, true, Map.of());
    }

    private static RescueOptions escapeOnly() {
        return new RescueOptions(null, Map.of(), RescueOptions.DEFAULT_WRAP_MAX_CHARS, true, Map.of());
    }

    private static RescueOptions aliases() {
        return new RescueOptions(null, Map.of(), RescueOptions.DEFAULT_WRAP_MAX_CHARS, false, Map.of("htmlResponseStr", FIELD));
    }

    private JsonOutcome parse(String raw, RescueOptions rescue) {
        return parser.parse(raw, true, null, null, "t", rescue);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(JsonOutcome outcome) {
        return (Map<String, Object>) outcome.value();
    }

    @Nested
    @DisplayName("shape 1: plain text, wrapped")
    class PlainText {

        static Stream<Arguments> prose() {
            return Stream.of(Arguments.of("Chinese", "您好！这是一个很长的回答。我已经为您查看了这些记录，没有发现任何问题。"),
                    Arguments.of("emoji", "Done \uD83C\uDF89 all 3 invitations were sent \uD83D\uDE80"),
                    Arguments.of("html with attributes", "<p>Overdue: <strong style=\"color:rgb(230, 0, 0)\">3 invoices</strong></p>"),
                    Arguments.of("brace mid sentence", "Use the {userId} placeholder, then press {Enter} to confirm."),
                    Arguments.of("bracket mid sentence", "Pick option [1] or [2] to continue."),
                    Arguments.of("multi line", "First line\n\nSecond paragraph with \"quotes\" and a backslash \\ ok"),
                    Arguments.of("an escape-looking text that is not JSON", "Path is C:\\temp\\new and it said \\\"hello\\\" there"));
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("prose")
        @DisplayName("is wrapped into the configured field, defaults merged, outcome wrapped")
        void wrapped(String name, String text) {
            JsonOutcome outcome = parse(text, wrap());

            assertEquals(Kind.VALID, outcome.kind(), name);
            assertEquals("wrapped", outcome.label());
            assertTrue(outcome.wrapped());
            assertEquals(Map.of(FIELD, text.trim(), "quickReplies", List.of()), outcome.value());
            assertEquals(text, outcome.raw(), "the raw reply is kept for logging");
        }

        @Test
        @DisplayName("the wrapped text is trimmed, a fence around prose is kept as the model wrote it")
        void trimmedNotUnfenced() {
            assertEquals("hello", map(parse("  \n hello \n ", wrap())).get(FIELD));
            assertEquals("```html\n<p>x</p>\n```", map(parse("```html\n<p>x</p>\n```", wrap())).get(FIELD));
        }

        @Test
        @DisplayName("wrapDefaults are copied per reply and never override the wrap field")
        void defaultsAreCopiedAndLose() {
            List<Object> shared = new ArrayList<>(List.of("a"));
            Map<String, Object> defaults = new LinkedHashMap<>();
            defaults.put("quickReplies", shared);
            defaults.put(FIELD, "default text");
            var options = new RescueOptions(FIELD, defaults, RescueOptions.DEFAULT_WRAP_MAX_CHARS, false, Map.of());

            JsonOutcome first = parse("plain", options);

            assertEquals("plain", map(first).get(FIELD));
            assertNotSame(shared, map(first).get("quickReplies"), "a reply must not share (and mutate) the configured list");
            assertEquals(List.of("a"), map(first).get("quickReplies"));
        }

        @Test
        @DisplayName("the default cap is 20,000 characters: at the cap it wraps, one over it does not")
        void lengthCap() {
            assertEquals("wrapped", parse("x".repeat(20_000), wrap()).label());

            JsonOutcome over = parse("x".repeat(20_001), wrap());
            assertEquals(Kind.INVALID, over.kind());
            assertEquals("x".repeat(20_001), over.value(), "the raw reply is still the value, for the policy to act on");
        }

        @Test
        @DisplayName("a configured cap is honoured")
        void configuredCap() {
            var small = new RescueOptions(FIELD, Map.of(), 10, false, Map.of());
            assertEquals("wrapped", parse("0123456789", small).label());
            assertEquals(Kind.INVALID, parse("0123456789X", small).kind());
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("brokenJson")
        @DisplayName("broken JSON is never wrapped: it stays INVALID for the policy")
        void brokenJsonIsNotWrapped(String name, String text) {
            JsonOutcome outcome = parse(text, wrap());

            assertEquals(Kind.INVALID, outcome.kind(), name);
            assertFalse(outcome.wrapped());
            assertEquals(text, outcome.value());
        }

        static Stream<Arguments> brokenJson() {
            return Stream.of(Arguments.of("truncated object", "{\"htmlResponseText\": \"half an ans"),
                    Arguments.of("truncated array", "[1, 2"), Arguments.of("trailing comma", "{\"htmlResponseText\": \"x\",}"),
                    Arguments.of("single quotes", "{'htmlResponseText': 'x'}"),
                    Arguments.of("fenced broken json", "```json\n{\"htmlResponseText\": \"x\n```"),
                    Arguments.of("unbalanced", "{\"a\": [1, 2}"));
        }

        @Test
        @DisplayName("blank is EMPTY, not wrapped")
        void blankIsEmpty() {
            assertEquals(Kind.EMPTY, parse("   ", wrap()).kind());
            assertEquals(Kind.EMPTY, parse(null, wrap()).kind());
        }

        @Test
        @DisplayName("a wrapped reply is then shape-validated like any parsed reply")
        void wrappedThenValidated() {
            var options = new RescueOptions(FIELD, Map.of(), RescueOptions.DEFAULT_WRAP_MAX_CHARS, false, Map.of());
            String schema = "{\"type\":\"object\",\"required\":[\"htmlResponseText\",\"quickReplies\"]}";

            JsonOutcome noDefaults = parser.parse("just text", true, schema, List.of(FIELD), "t", options);

            assertEquals(Kind.SCHEMA_MISMATCH, noDefaults.kind(), "required quickReplies is missing and no default supplies it");
            assertTrue(noDefaults.wrapped());
            assertEquals("schema_mismatch", noDefaults.label());
            assertEquals(Map.of(FIELD, "just text"), noDefaults.value());

            JsonOutcome withDefaults = parser.parse("just text", true, schema, List.of(FIELD), "t", wrap());
            assertEquals(Kind.VALID, withDefaults.kind());
            assertEquals("wrapped", withDefaults.label());
        }

        @Test
        @DisplayName("prose around an embedded JSON object is still extracted, not wrapped")
        void extractionWinsOverWrap() {
            JsonOutcome outcome = parse("Here you go: {\"htmlResponseText\": \"<p>x</p>\"} bye", wrap());

            assertEquals("repaired", outcome.label());
            assertEquals(Map.of(FIELD, "<p>x</p>"), outcome.value());
        }
    }

    @Nested
    @DisplayName("shape 2: double-escaped JSON")
    class DoubleEscaped {

        @ParameterizedTest(name = "{0}")
        @MethodSource("escaped")
        @DisplayName("is unescaped one level and accepted as an object, outcome repaired")
        void unescaped(String name, String raw, Map<String, Object> expected) {
            JsonOutcome outcome = parse(raw, escapeOnly());

            assertEquals(Kind.VALID, outcome.kind(), name);
            assertEquals("repaired", outcome.label());
            assertEquals(expected, outcome.value());
        }

        static Stream<Arguments> escaped() {
            return Stream.of(Arguments.of("fully escaped", "{\\\"htmlResponseText\\\": \\\"<p>Hi</p>\\\"}", Map.of(FIELD, "<p>Hi</p>")),
                    Arguments.of("mixed: plain key, escaped value", "{\"htmlResponseText\": \\\"<p>Hi</p>\\\"}", Map.of(FIELD, "<p>Hi</p>")),
                    Arguments.of("a quote escaped inside an escaped value", "{\\\"htmlResponseText\\\": \\\"say \\\\\\\"hi\\\\\\\"\\\"}",
                            Map.of(FIELD, "say \"hi\"")),
                    Arguments.of("escaped newline", "{\\\"htmlResponseText\\\": \\\"a\\\\nb\\\"}", Map.of(FIELD, "a\nb")),
                    Arguments.of("escaped backslash", "{\\\"htmlResponseText\\\": \\\"a\\\\\\\\b\\\"}", Map.of(FIELD, "a\\b")),
                    Arguments.of("fenced", "```json\n{\\\"htmlResponseText\\\": \\\"x\\\"}\n```", Map.of(FIELD, "x")),
                    Arguments.of("Chinese and emoji", "{\\\"htmlResponseText\\\": \\\"您好 \uD83C\uDF89\\\"}", Map.of(FIELD, "您好 \uD83C\uDF89")));
        }

        @Test
        @DisplayName("also runs under wrap, and wins over wrapping the escaped text")
        void underWrap() {
            JsonOutcome outcome = parse("{\\\"htmlResponseText\\\": \\\"x\\\"}", wrap());

            assertEquals("repaired", outcome.label());
            assertFalse(outcome.wrapped());
            assertEquals(Map.of(FIELD, "x"), outcome.value());
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("alreadyValid")
        @DisplayName("is never applied to a reply that already parsed: legitimate escapes are not corrupted")
        void neverAppliedToValidJson(String name, String raw, Map<String, Object> expected) {
            JsonOutcome outcome = parse(raw, escapeOnly());

            assertEquals(Kind.VALID, outcome.kind(), name);
            assertEquals("valid", outcome.label(), "not even flagged as repaired");
            assertEquals(expected, outcome.value());
        }

        static Stream<Arguments> alreadyValid() {
            return Stream.of(
                    Arguments.of("escaped quotes in an HTML attribute", "{\"htmlResponseText\": \"<a href=\\\"x\\\">go</a>\"}",
                            Map.of(FIELD, "<a href=\"x\">go</a>")),
                    // contains the trigger pattern "\": \"" inside the value
                    Arguments.of("trigger pattern inside a value", "{\"htmlResponseText\": \"he said \\\": \\\"yes\\\"\"}",
                            Map.of(FIELD, "he said \": \"yes\"")),
                    Arguments.of("escaped backslash and newline", "{\"htmlResponseText\": \"C:\\\\dir\\nnext\"}", Map.of(FIELD, "C:\\dir\nnext")));
        }

        @Test
        @DisplayName("a result that is not an object is not accepted")
        void onlyObjectsAreAccepted() {
            // unescapes to ["a"] ... never started with {, so it is not a candidate at all
            assertEquals(Kind.INVALID, parse("[\\\"a\\\"]", escapeOnly()).kind());
            // looks escaped but is still broken after one level
            assertEquals(Kind.INVALID, parse("{\\\"htmlResponseText\\\": \\\"cut off", escapeOnly()).kind());
        }

        @Test
        @DisplayName("without rescue options a double-escaped reply is invalid, as before")
        void offByDefault() {
            JsonOutcome outcome = parse("{\\\"htmlResponseText\\\": \\\"x\\\"}", RescueOptions.NONE);
            assertEquals(Kind.INVALID, outcome.kind());
        }
    }

    @Nested
    @DisplayName("shape 3: wrong field name, fieldAliases")
    class Aliases {

        @Test
        @DisplayName("a key in the alias map is renamed when the target is absent")
        void renamed() {
            JsonOutcome outcome = parse("{\"htmlResponseStr\": \"<p>x</p>\", \"quickReplies\": []}", aliases());

            assertEquals(Kind.VALID, outcome.kind());
            assertEquals("repaired", outcome.label());
            assertEquals(Map.of(FIELD, "<p>x</p>", "quickReplies", List.of()), outcome.value());
        }

        @Test
        @DisplayName("when both keys exist the target wins and nothing is renamed")
        void targetWins() {
            JsonOutcome outcome = parse("{\"htmlResponseStr\": \"wrong\", \"htmlResponseText\": \"right\"}", aliases());

            assertEquals("valid", outcome.label());
            assertEquals(Map.of("htmlResponseStr", "wrong", FIELD, "right"), outcome.value());
        }

        @Test
        @DisplayName("the renamed reply is the one the shape is validated on")
        void renamedThenValidated() {
            JsonOutcome outcome = parser.parse("{\"htmlResponseStr\": \"x\"}", true, null, List.of(FIELD), "t", aliases());
            assertEquals(Kind.VALID, outcome.kind());

            JsonOutcome without = parser.parse("{\"htmlResponseStr\": \"x\"}", true, null, List.of(FIELD), "t", RescueOptions.NONE);
            assertEquals(Kind.SCHEMA_MISMATCH, without.kind());
        }

        @Test
        @DisplayName("also applies to an object extracted from prose and to an unescaped one")
        void everyParsePath() {
            assertEquals(Map.of(FIELD, "x"), parse("Sure: {\"htmlResponseStr\": \"x\"} done", aliases()).value());

            var both = new RescueOptions(null, Map.of(), RescueOptions.DEFAULT_WRAP_MAX_CHARS, true, Map.of("htmlResponseStr", FIELD));
            assertEquals(Map.of(FIELD, "x"), parse("{\\\"htmlResponseStr\\\": \\\"x\\\"}", both).value());
        }

        @Test
        @DisplayName("no fuzzy matching: an unlisted key stays as it is")
        void noFuzzy() {
            JsonOutcome outcome = parse("{\"htmlResponse\": \"x\"}", aliases());
            assertEquals(Map.of("htmlResponse", "x"), outcome.value());
            assertEquals("valid", outcome.label());
        }

        @Test
        @DisplayName("a reply that is not an object is left alone")
        void arraysAreLeftAlone() {
            assertEquals(List.of("htmlResponseStr"), parse("[\"htmlResponseStr\"]", aliases()).value());
        }
    }

    @Nested
    @DisplayName("shape 4: the empty object")
    class EmptyObject {

        @Test
        @DisplayName("{} is not wrapped; nonBlankFields flags it so onSchemaMismatch can act")
        void emptyObjectIsASchemaMismatch() {
            JsonOutcome outcome = parser.parse("{}", true, null, List.of(FIELD), "t", wrap());

            assertEquals(Kind.SCHEMA_MISMATCH, outcome.kind());
            assertFalse(outcome.wrapped());
            assertEquals("nonBlank: $." + FIELD, outcome.reason());
        }
    }

    @Nested
    @DisplayName("no options configured")
    class Unconfigured {

        @ParameterizedTest(name = "{0}")
        @MethodSource("replies")
        @DisplayName("NONE behaves exactly like the two-argument parse")
        void sameAsToday(String name, String raw) {
            JsonOutcome before = parser.parse(raw, true);
            JsonOutcome after = parser.parse(raw, true, null, null, "t", RescueOptions.NONE);

            assertEquals(before, after, name);
            assertFalse(after.wrapped());
        }

        static Stream<Arguments> replies() {
            return Stream.of(Arguments.of("plain text", "Just some text"), Arguments.of("chinese", "您好"),
                    Arguments.of("double escaped", "{\\\"a\\\": \\\"b\\\"}"), Arguments.of("alias key", "{\"htmlResponseStr\": \"x\"}"),
                    Arguments.of("valid", "{\"a\": 1}"), Arguments.of("fenced", "```json\n{\"a\": 1}\n```"), Arguments.of("truncated", "{\"a\": "),
                    Arguments.of("empty object", "{}"), Arguments.of("blank", " "));
        }

        @Test
        @DisplayName("plain text stays INVALID with the raw reply as the value")
        void plainTextStaysInvalid() {
            JsonOutcome outcome = parser.parse("Just some text", true);
            assertEquals(Kind.INVALID, outcome.kind());
            assertEquals("Just some text", outcome.value());
            assertEquals("invalid", outcome.label());
        }
    }

    @Test
    @DisplayName("replay: near-miss replies of every shape give a usable answer with no model call")
    void replay() {
        var options = new RescueOptions(FIELD, Map.of("quickReplies", List.of()), RescueOptions.DEFAULT_WRAP_MAX_CHARS, true,
                Map.of("htmlResponseStr", FIELD));
        List<String> replies = List.of("您好，已经完成。", "<p>All <strong style=\"color:rgb(230, 0, 0)\">3</strong> done</p> \uD83C\uDF89",
                "{\\\"htmlResponseText\\\": \\\"<p>x</p>\\\", \\\"quickReplies\\\": []}", "{\"htmlResponseText\": \\\"<p>x</p>\\\"}",
                "{\"htmlResponseStr\": \"<p>x</p>\"}", "Use {name} here", "a ```json fence in the middle", "Pick [1] or [2]");
        for (String reply : replies) {
            JsonOutcome outcome = parser.parse(reply, true, null, List.of(FIELD), "t", options);
            assertEquals(Kind.VALID, outcome.kind(), reply);
            String text = String.valueOf(map(outcome).get(FIELD));
            assertFalse(text.isBlank(), reply);
        }
    }
}
