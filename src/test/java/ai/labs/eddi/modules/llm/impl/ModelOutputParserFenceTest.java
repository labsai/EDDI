/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.datastore.serialization.JsonSerialization;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Security review 2026-10-17, H3: the fence stripper was a regex whose three
 * quantifiers competed for one whitespace run, so "```" + N spaces + "a" cost
 * O(N³) on the turn thread — 3.7 s at 2,000 spaces. It is string operations
 * now; these tests hold it to the old regex's results and to linear time.
 */
@DisplayName("ModelOutputParser.stripFence — linear, and the same answer as the regex it replaced")
class ModelOutputParserFenceTest {

    /** The pattern {@code stripFence} used until 2026-10-17, kept as the oracle. */
    private static final Pattern OLD_FENCE = Pattern.compile("^```(?:json)?[ \\t]*\\r?\\n?(.*?)\\s*```$", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private static String oldStripFence(String trimmed) {
        Matcher m = OLD_FENCE.matcher(trimmed);
        return m.matches() ? m.group(1).trim() : trimmed;
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"```json\n{\"a\":1}\n```", "```\n{\"a\":1}\n```", "```JSON\n[1,2]\n```", "```Json {\"a\":1}```",
            "```jsonl\n{\"a\":1}\n```",
            "```json```", "``````", "`````", "```", "```a```", "```\t\r\n {\"a\":1} \n\t```", "``` json\n{}\n```", "```js\n{}\n```",
            "```json \t\r\n\r\n{}\r\n```", "```\n```\n```", "no fence {\"a\":1}", "{\"a\":1}```", "```{\"a\":1}", "```jSoN{}```",
            "```  a", "a```"})
    @DisplayName("every input strips exactly as the old regex did")
    void sameAsTheRegex(String input) {
        assertEquals(oldStripFence(input), ModelOutputParser.stripFence(input));
    }

    /**
     * Characters Unicode case folding could map onto {@code json}, and a vertical
     * tab ({@code \s} but also below {@code trim()}'s cut-off). Built from code
     * points, not escapes: the formatter rewrites a unicode escape as the raw
     * character.
     */
    @Test
    @DisplayName("non-ASCII look-alikes and a vertical tab strip exactly as the old regex did")
    void nonAsciiSameAsTheRegex() {
        char longS = (char) 0x017F;
        char kelvin = (char) 0x212A;
        char verticalTab = (char) 0x0B;
        for (String input : new String[]{"```json" + longS + "```", "```" + kelvin + "SON{}```", "```j" + longS + "on{}```",
                "```" + verticalTab + "{}" + verticalTab + "```"}) {
            assertEquals(oldStripFence(input), ModelOutputParser.stripFence(input), input);
        }
    }

    @Test
    @DisplayName("the cubic input — three backticks, 50,000 spaces, a letter — returns in under a second")
    void cubicInputIsLinear() {
        String hostile = "```" + " ".repeat(50_000) + "a";
        String hostileClosed = "```json" + " ".repeat(50_000) + "a" + " ".repeat(50_000) + "```";
        // Warm up, so the budget measures the algorithm, not class loading.
        ModelOutputParser.stripFence("```json\n{}\n```");

        long start = System.nanoTime();
        String unchanged = ModelOutputParser.stripFence(hostile);
        String stripped = ModelOutputParser.stripFence(hostileClosed);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertEquals(hostile, unchanged);
        assertEquals("a", stripped);
        assertTrue(elapsedMillis < 1_000, "stripFence took " + elapsedMillis + " ms on 50,000 spaces");
    }

    @Test
    @DisplayName("through parse(): the same hostile reply is judged in under a second, and a fenced reply still parses")
    void parseIsLinear() {
        var parser = new ModelOutputParser(new JsonSerialization(new ObjectMapper()));
        parser.parse("```json\n{\"a\":1}\n```", true);

        long start = System.nanoTime();
        parser.parse("```" + " ".repeat(50_000) + "a", true);
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000;

        assertTrue(elapsedMillis < 1_000, "parse took " + elapsedMillis + " ms on 50,000 spaces");
        assertEquals(Map.of("a", 1), parser.parse("```json\n{\"a\":1}\n```", true).value());
    }
}
