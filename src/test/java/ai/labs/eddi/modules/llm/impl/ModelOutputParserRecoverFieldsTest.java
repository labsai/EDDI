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
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code responseValidation.recoverFields}: the string fields a broken JSON
 * reply still holds are kept. Golden replies are shaped like production ones (a
 * Gemini reply with an unescaped quote, a reply cut off mid-string, CJK, emoji,
 * HTML attributes).
 */
@DisplayName("ModelOutputParser — recoverFields (broken JSON rescue)")
class ModelOutputParserRecoverFieldsTest {

    private static final String FIELD = "htmlResponseText";

    private final ModelOutputParser parser = new ModelOutputParser(new JsonSerialization(new ObjectMapper()));

    private static RescueOptions recover(String... fields) {
        return new RescueOptions(null, Map.of("quickReplies", List.of()), RescueOptions.DEFAULT_WRAP_MAX_CHARS, false, Map.of(), List.of(fields));
    }

    private static RescueOptions recoverAndWrap(String... fields) {
        return new RescueOptions(FIELD, Map.of("quickReplies", List.of()), RescueOptions.DEFAULT_WRAP_MAX_CHARS, true, Map.of(), List.of(fields));
    }

    private JsonOutcome parse(String raw, RescueOptions options) {
        return parser.parse(raw, true, null, List.of(FIELD), "t", options);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(JsonOutcome outcome) {
        return (Map<String, Object>) outcome.value();
    }

    private static final String PRODUCTION_PREFIX = "{\n  \"htmlResponseText\": \"Welcome to the Final Assessment of the Asset Deployment program! "
            + "You are now at the final stage \u00ab from asset types and placement to timelines and quality execution. <br>";

    @Test
    @DisplayName("the production reply: an unescaped quote inside the value is kept, the object is recovered")
    void productionReplyWithUnescapedQuote() {
        String raw = PRODUCTION_PREFIX + "Type \"ready\" when you want to start.<br>\"\n}";

        JsonOutcome outcome = parse(raw, recover(FIELD));

        assertEquals(Kind.VALID, outcome.kind());
        assertTrue(outcome.recovered());
        assertEquals("recovered", outcome.label());
        assertEquals(PRODUCTION_PREFIX.substring(PRODUCTION_PREFIX.indexOf("Welcome")) + "Type \"ready\" when you want to start.<br>",
                map(outcome).get(FIELD));
        assertEquals(List.of(), map(outcome).get("quickReplies"), "wrapDefaults are merged in");
    }

    @Test
    @DisplayName("the production reply cut off mid-string: the rest of the text is the value")
    void productionReplyTruncated() {
        JsonOutcome outcome = parse(PRODUCTION_PREFIX, recover(FIELD));

        assertEquals(Kind.VALID, outcome.kind());
        assertEquals("recovered", outcome.label());
        assertEquals(PRODUCTION_PREFIX.substring(PRODUCTION_PREFIX.indexOf("Welcome")), map(outcome).get(FIELD));
    }

    @Test
    @DisplayName("a truncated reply loses a trailing half-escape, not the text before it")
    void truncatedInsideEscape() {
        assertEquals("a\"b", map(parse("{\"htmlResponseText\": \"a\\\"b\\", recover(FIELD))).get(FIELD));
        assertEquals("caf", map(parse("{\"htmlResponseText\": \"caf\\u00e", recover(FIELD))).get(FIELD));
        assertEquals("caf\u00e9", map(parse("{\"htmlResponseText\": \"caf\\u00e9", recover(FIELD))).get(FIELD));
    }

    @Test
    @DisplayName("an over-long truncated value is cut at wrapMaxChars")
    void truncatedValueIsCapped() {
        var options = new RescueOptions(null, Map.of(), 10, false, Map.of(), List.of(FIELD));

        JsonOutcome outcome = parse("{\"htmlResponseText\": \"0123456789ABCDEF", options);

        assertEquals("0123456789", map(outcome).get(FIELD));
    }

    @Test
    @DisplayName("escaped quotes inside the value are decoded, they do not end it early")
    void escapedQuotesDecode() {
        String raw = "{\"htmlResponseText\": \"He said \\\"hello\\\" and left\\nbye\\\\ \\/ \\t.\", \"broken\": [1,";

        JsonOutcome outcome = parse(raw, recover(FIELD));

        assertEquals("recovered", outcome.label());
        assertEquals("He said \"hello\" and left\nbye\\ / \t.", map(outcome).get(FIELD));
    }

    @Test
    @DisplayName("CJK text and emoji, including an escaped surrogate pair, come through unchanged")
    void cjkAndEmoji() {
        String raw = "{\"htmlResponseText\": \"您好，已经完成 🎉 \\ud83d\\ude00 \"unquoted\" 结束\", \"quickReplies\": [";

        JsonOutcome outcome = parse(raw, recover(FIELD));

        assertEquals("您好，已经完成 🎉 😀 \"unquoted\" 结束", map(outcome).get(FIELD));
    }

    @Test
    @DisplayName("HTML attributes with quotes and commas do not end the value")
    void htmlAttributes() {
        String html = "<p>Total: <strong style=\"color:rgb(230, 0, 0)\">3</strong>, done</p>";
        String raw = "{\"htmlResponseText\": \"" + html + "\", \"quickReplies\": [\"a\"}";

        JsonOutcome outcome = parse(raw, recover(FIELD));

        assertEquals("recovered", outcome.label());
        assertEquals(html, map(outcome).get(FIELD));
    }

    @Test
    @DisplayName("a quote followed by a comma ends the value only when the next key follows")
    void commaInsideValueIsNotAnEnd() {
        String raw = "{\"htmlResponseText\": \"say \"yes\", then go\", \"quickReplies\": [}";

        assertEquals("say \"yes\", then go", map(parse(raw, recover(FIELD))).get(FIELD));
    }

    @Test
    @DisplayName("the reply inside an opening code fence is recovered, a closing fence is not needed")
    void fenceWithoutClosing() {
        JsonOutcome outcome = parse("```json\n{\"htmlResponseText\": \"cut off", recover(FIELD));

        assertEquals("recovered", outcome.label());
        assertEquals("cut off", map(outcome).get(FIELD));
    }

    @Test
    @DisplayName("only string fields are recovered: an array field is not, and a default fills its place")
    void onlyStringFields() {
        String raw = "{\"quickReplies\": [\"a\", \"b\"], \"htmlResponseText\": \"text with \"quote\" inside\", \"summary\": \"short\"}";

        JsonOutcome outcome = parse(raw, recover(FIELD, "quickReplies", "summary"));

        assertEquals("text with \"quote\" inside", map(outcome).get(FIELD));
        assertEquals("short", map(outcome).get("summary"));
        assertEquals(List.of(), map(outcome).get("quickReplies"), "the array was not read; the default stays");
    }

    @Test
    @DisplayName("a listed field that sits inside another field's value is that value's text, not a field")
    void fieldInsideValueIsNotRecovered() {
        String raw = "{\"htmlResponseText\": \"he wrote \"summary\": \"x\" in it\", \"oops";

        JsonOutcome outcome = parse(raw, recover(FIELD, "summary"));

        assertEquals("he wrote \"summary\": \"x\" in it", map(outcome).get(FIELD));
        assertFalse(map(outcome).containsKey("summary"));
    }

    @Test
    @DisplayName("field absent: nothing is recovered, the reply stays invalid")
    void fieldAbsent() {
        JsonOutcome outcome = parse("{\"other\": \"cut off", recover(FIELD));

        assertEquals(Kind.INVALID, outcome.kind());
        assertFalse(outcome.recovered());
    }

    @Test
    @DisplayName("an empty value is not a recovery")
    void emptyValue() {
        assertEquals(Kind.INVALID, parse("{\"htmlResponseText\": \"\", \"quickReplies\": [", recover(FIELD)).kind());
    }

    @Test
    @DisplayName("a reply that parses is untouched, even when it holds the field")
    void parsedReplyIsUntouched() {
        JsonOutcome outcome = parse("{\"htmlResponseText\": \"ok\"}", recover(FIELD));

        assertEquals("valid", outcome.label());
        assertFalse(outcome.recovered());
        assertEquals(Map.of("htmlResponseText", "ok"), outcome.value());
    }

    @Test
    @DisplayName("prose is never recovered, not even when it quotes the field; wrap takes it")
    void proseGoesToWrap() {
        String prose = "Here you go: \"htmlResponseText\": \"nope\"";

        JsonOutcome onlyRecover = parse(prose, recover(FIELD));
        assertEquals(Kind.INVALID, onlyRecover.kind());

        JsonOutcome both = parse(prose, recoverAndWrap(FIELD));
        assertEquals("wrapped", both.label());
        assertFalse(both.recovered());
        assertEquals(prose, map(both).get(FIELD));
    }

    @Test
    @DisplayName("with wrap also on, broken JSON is recovered, not left for fallback")
    void recoverBeatsRefusalOfWrap() {
        JsonOutcome outcome = parse("{\"htmlResponseText\": \"cut", recoverAndWrap(FIELD));

        assertEquals("recovered", outcome.label());
        assertEquals("cut", map(outcome).get(FIELD));
    }

    @Test
    @DisplayName("recoverFields unset: broken JSON stays invalid, exactly as before")
    void offByDefault() {
        JsonOutcome outcome = parse("{\"htmlResponseText\": \"cut", RescueOptions.NONE);

        assertEquals(Kind.INVALID, outcome.kind());
    }

    @Test
    @DisplayName("a recovered object is validated like any parsed reply: a missing required field is a schema mismatch")
    void recoveredObjectIsShapeChecked() {
        JsonOutcome outcome = parser.parse("{\"htmlResponseText\": \"cut", true,
                "{\"type\":\"object\",\"required\":[\"htmlResponseText\",\"other\"]}",
                List.of(FIELD), "t", recover(FIELD));

        assertEquals(Kind.SCHEMA_MISMATCH, outcome.kind());
        assertTrue(outcome.recovered(), "the flag survives, so the task does not count it as a recovery");
    }

    @Test
    @DisplayName("an object over nonBlankFields passes when the recovered field is non-blank")
    void recoveredObjectSatisfiesNonBlank() {
        JsonOutcome outcome = parser.parse("{\"htmlResponseText\": \"cut", true, null, List.of(FIELD), "t", recover(FIELD));

        assertEquals(Kind.VALID, outcome.kind());
    }

    @Test
    @DisplayName("the defaults are copied, not shared with the configuration")
    void defaultsAreCopied() {
        var shared = new ArrayList<Object>();
        var options = new RescueOptions(null, Map.of("quickReplies", shared), 100, false, Map.of(), List.of(FIELD));

        JsonOutcome outcome = parse("{\"htmlResponseText\": \"cut", options);

        assertFalse(map(outcome).get("quickReplies") == shared);
    }

    @Test
    @DisplayName("a quote, comma and another quoted word inside prose do not end the value: only a real next key does")
    void quotedWordsAfterCommaStayInProse() {
        String raw = "{\"htmlResponseText\": \"He said \"hi\", \"bye\" and left\", \"quickReplies\": [";

        assertEquals("He said \"hi\", \"bye\" and left", map(parse(raw, recover(FIELD))).get(FIELD));
    }

    @Test
    @DisplayName("a real following key still ends the value, whatever its name")
    void realNextKeyEndsValue() {
        String raw = "{\"htmlResponseText\": \"answer\",\n  \"follow_up-1\" : \"x\"}";

        assertEquals("answer", map(parse(raw, recover(FIELD))).get(FIELD));
    }

    @Test
    @DisplayName("a closing brace inside prose does not end the value; one that ends the reply, or precedes a closing fence, does")
    void braceEndsOnlyAtTheEnd() {
        assertEquals("x \"y\" use \"}\" z", map(parse("{\"htmlResponseText\": \"x \"y\" use \"}\" z\"}", recover(FIELD))).get(FIELD));
        assertEquals("done", map(parse("```json\n{\"htmlResponseText\": \"done\"}\n```", recover(FIELD))).get(FIELD));
    }

    @Test
    @DisplayName("a field nested in another object is not a field of the root: nothing is lifted to the top level")
    void nestedOccurrenceIsNotRecovered() {
        JsonOutcome outcome = parse("{\"nested\":{\"htmlResponseText\":\"wrong", recover(FIELD));

        assertFalse(outcome.recovered());
        assertTrue(outcome.kind() != Kind.VALID, "the required top-level field is absent: " + outcome.kind());
    }

    @Test
    @DisplayName("a nested occurrence before the real top-level member does not win")
    void nestedBeforeTopLevel() {
        String raw = "{\"meta\":{\"htmlResponseText\":\"nested\"},\"items\":[{\"htmlResponseText\":\"in array\"}],"
                + "\"htmlResponseText\": \"top \"level\" text\", \"quickReplies\": [";

        assertEquals("top \"level\" text", map(parse(raw, recover(FIELD))).get(FIELD));
    }

    @Test
    @DisplayName("a later occurrence is found when the first one sits inside another recovered field's text")
    void laterOccurrenceAfterExcludedOne() {
        String raw = "{\"htmlResponseText\":\"example \"summary\": \"fake\" inside\",\"summary\":\"real\",\"broken\":[";

        Map<String, Object> result = map(parse(raw, recover(FIELD, "summary")));

        assertEquals("example \"summary\": \"fake\" inside", result.get(FIELD));
        assertEquals("real", result.get("summary"));
    }

    @Test
    @DisplayName("a following key of 65 characters (within the 128 limit) still ends the value")
    void longNextKeyEndsValue() {
        String key = "a".repeat(65);
        String raw = "{\"htmlResponseText\": \"bad \"x\" quote\", \"" + key + "\": \"v\"}";

        assertEquals("bad \"x\" quote", map(parse(raw, recover(FIELD))).get(FIELD));
    }

    @Test
    @DisplayName("a following key at the 128-character limit ends the value; a longer one stays in the text (nothing is skipped)")
    void nextKeyLimit() {
        String atLimit = "{\"htmlResponseText\": \"bad \"x\" quote\", \"" + "a".repeat(128) + "\": \"v\"}";
        String over = "{\"htmlResponseText\": \"bad \"x\" quote\", \"" + "a".repeat(129) + "\": \"v\"}";

        assertEquals("bad \"x\" quote", map(parse(atLimit, recover(FIELD))).get(FIELD));
        assertTrue(((String) map(parse(over, recover(FIELD))).get(FIELD)).startsWith("bad \"x\" quote"));
    }

    @Test
    @DisplayName("many candidate quotes followed by a brace are checked without copying the rest of the reply each time")
    void manyBraceCandidatesStayLinear() {
        String raw = "{\"htmlResponseText\":\"bad \"x\" " + "\"}".repeat(200_000);

        JsonOutcome outcome = assertTimeoutPreemptively(Duration.ofSeconds(5),
                () -> parse(raw, recover(FIELD)));

        assertEquals(Kind.VALID, outcome.kind());
    }
}
