/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.templating;

import ai.labs.eddi.modules.llm.impl.LlmTask;
import ai.labs.eddi.modules.templating.impl.TemplatingEngine;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.qute.Engine;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Holds the Manager's idea of "what Qute treats as an expression" to the real
 * engine.
 * <p>
 * The Platform Operator's system prompt is stored as an LLM task's
 * {@code systemMessage}, which {@code LlmTask} renders as a Qute template on
 * every turn. It is full of text that reads as an expression
 * ({@code eddi_turn_rejected_total{reason}}, {@code /agents/{agentId}/profile},
 * {@code {input}}). 6.7.0 shipped one that did not parse (every turn logged
 * {@code Parser error: invalid virtual method in
 * {required),(iterationObjectName}} and sent the model the raw prompt), and a
 * render that does parse drops the span without a word. The Manager now escapes
 * such spans when it builds the prompt
 * ({@code ui/manager/src/lib/operator/qute-literal.ts}) and its own vitest
 * checks every prompt it can send.
 * <p>
 * That check is only as good as the rule it applies, and the rule is Qute's
 * lexer, which a browser test cannot run. So the rule is written down as a
 * corpus ({@code qute-literal-corpus.json}, read by both suites), and this test
 * renders every case through the engine EDDI uses: what the engine does with
 * the text, and that the Manager's escaped form renders back to exactly the
 * text. A Qute upgrade that moves the lexer's rule fails here, in the plain
 * unit run, without Docker and without a browser; the TypeScript side then has
 * to follow.
 */
class OperatorPromptQuteCorpusTest {

    private static final Path CORPUS = Path.of("ui", "manager", "src", "lib", "operator", "__tests__", "qute-literal-corpus.json");

    private final TemplatingEngine engine = new TemplatingEngine(Engine.builder().addDefaults().build());

    private JsonNode cases() throws IOException {
        assertTrue(Files.isRegularFile(CORPUS), "missing " + CORPUS.toAbsolutePath());
        JsonNode cases = new ObjectMapper().readTree(Files.readString(CORPUS, StandardCharsets.UTF_8)).get("cases");
        assertTrue(cases != null && cases.size() > 20, "the corpus is empty or unreadable: " + CORPUS);
        return cases;
    }

    /**
     * What {@code LlmTask.runTemplateEngineOnParams} does to a prompt: escape
     * reference mentions, then render with nothing in the data map.
     */
    private String render(String template) throws ITemplatingEngine.TemplateEngineException {
        return engine.processTemplate(LlmTask.escapeConfigReferenceMentions(template), Map.of());
    }

    /**
     * The outcome of rendering {@code text}: its output, or {@code null} when the
     * render fails.
     */
    private String renderOrNull(String text) {
        try {
            return render(text);
        } catch (ITemplatingEngine.TemplateEngineException | RuntimeException e) {
            return null;
        }
    }

    @TestFactory
    List<DynamicTest> everyCaseBehavesAsTheCorpusRecordsItForTheRealEngine() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        for (JsonNode c : cases()) {
            String name = c.get("name").asText();
            String text = c.get("text").asText();
            String kind = c.get("kind").asText();
            String escaped = c.get("escaped").asText();
            tests.add(DynamicTest.dynamicTest(kind + ": " + name, () -> check(kind, text, escaped)));
        }
        return tests;
    }

    private void check(String kind, String text, String escaped) throws Exception {
        switch (kind) {
            case "literal", "mention" -> {
                assertEquals(text, escaped, "a " + kind + " is left exactly as written");
                assertEquals(text, render(text), "the engine must leave a " + kind + " as it is");
            }
            case "expression" -> {
                String rendered = renderOrNull(text);
                assertTrue(rendered == null || !rendered.equals(text),
                        "the engine must evaluate (or reject) this, or the case is not an expression: " + text);
                assertEquals(text, render(escaped), "the escaped form must render back to the text: " + escaped);
            }
            case "live" -> {
                assertEquals(text, escaped, "live syntax is left for the engine to evaluate");
                assertNull(engine.validateTemplate(text), "live syntax must parse: " + text);
            }
            default -> fail("unknown kind '" + kind + "' in the corpus");
        }
    }

    @Test
    void corpusCoversEveryKindSoARuleCannotQuietlyLoseItsCase() throws IOException {
        Set<String> kinds = new HashSet<>();
        for (JsonNode c : cases()) {
            kinds.add(c.get("kind").asText());
        }
        assertEquals(Set.of("literal", "expression", "mention", "live"), kinds);
    }

    /**
     * The probe behind the rule's one-line summary: for every ASCII character that
     * can follow a lone {@code {}, whether the engine treats the brace as the start
     * of an expression. {@code qute-literal.ts} says: an ASCII letter or one of
     * {@code ! # / |}, nothing else.
     */
    @Test
    void onlyLettersAndFourPunctuationMarksOpenAnExpression() {
        StringBuilder opens = new StringBuilder();
        for (char c = 0; c < 128; c++) {
            String text = "a{" + c + "x} b";
            String rendered = renderOrNull(text);
            if (rendered == null || !rendered.equals(text)) {
                opens.append(c);
            }
        }
        String expected = "!#/|ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
        assertEquals(sorted(expected), sorted(opens.toString()),
                "the characters after '{' that the engine evaluates changed; update qute-literal.ts and its corpus");
    }

    @Test
    void aDoubledOpenerIsAPairOfLiteralBraces() throws Exception {
        assertEquals("{{a}}", render("{{a}}"));
        assertNotEquals("{{{a}}}", render("{{{a}}}"), "the odd brace of a run of three opens an expression");
    }

    private static String sorted(String s) {
        char[] chars = s.toCharArray();
        Arrays.sort(chars);
        return new String(chars);
    }

    @Test
    void theSixPointSevenForEachLineReproducesTheLoggedParseError() {
        // The exact text 6.7.0 shipped, and the exact message the server logged for it.
        String line = "- An apicalls entry's forEach {pathToTargetArray (a plain dot path such as\n"
                + "  properties.orderIds, required), iterationObjectName (default \"item\", bound\n"
                + "  to the element itself), maxItems (default 100)} sends the call once per";
        String problem = engine.validateTemplate(line);
        assertFalse(problem == null, "this line must not parse");
        assertTrue(problem.contains("invalid virtual method in {required),(iterationObjectName}"), problem);
    }
}
