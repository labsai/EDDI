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
 * Holds the Manager's escape of the operator prompt to the real engine.
 * <p>
 * The Platform Operator's system prompt is stored as an LLM task's
 * {@code systemMessage}, which {@code LlmTask} renders as a Qute template on
 * every turn. It is full of text that reads as an expression
 * ({@code eddi_turn_rejected_total{reason}}, {@code /agents/{agentId}/profile},
 * {@code {input}}). 6.7.0 shipped one that did not parse (every turn logged
 * {@code Parser error: invalid virtual method in
 * {required),(iterationObjectName}} and sent the model the raw prompt), and a
 * render that does parse drops the span without a word. The Manager escapes
 * every brace when it builds the prompt
 * ({@code ui/manager/src/lib/operator/qute-literal.ts}), apart from its own
 * per-turn context block, reference mentions and unparsed blocks the author
 * wrote.
 * <p>
 * The rule is conservative on purpose (it does not mirror the lexer), so the
 * corpus ({@code qute-literal-corpus.json}, read by both suites) is the source
 * of truth: each case is rendered here through the engine EDDI uses, and the
 * escaped form has to come back as exactly what was typed.
 * <p>
 * <b>Every render here is forced to parse.</b> {@link TemplatingEngine} returns
 * a string without any of its control characters untouched, without parsing it,
 * so a case such as {@code {0}} or {@code {_x}} rendered on its own proves
 * nothing. The real prompt always carries markers (its context block, and the
 * unparsed blocks of the escape itself), so the engine always parses it; the
 * harness reproduces that with a leading comment.
 */
class OperatorPromptQuteCorpusTest {

    private static final Path CORPUS = Path.of("ui", "manager", "src", "lib", "operator", "__tests__", "qute-literal-corpus.json");

    /**
     * Makes the engine parse a template that would otherwise skip it; renders to
     * nothing.
     */
    private static final String FORCE_PARSE = "{!force!}";

    private final TemplatingEngine engine = new TemplatingEngine(Engine.builder().addDefaults().build());

    private JsonNode cases() throws IOException {
        assertTrue(Files.isRegularFile(CORPUS), "missing " + CORPUS.toAbsolutePath());
        JsonNode cases = new ObjectMapper().readTree(Files.readString(CORPUS, StandardCharsets.UTF_8)).get("cases");
        assertTrue(cases != null && cases.size() > 20, "the corpus is empty or unreadable: " + CORPUS);
        return cases;
    }

    /**
     * What {@code LlmTask.runTemplateEngineOnParams} does to a prompt: escape
     * reference mentions, then render. Forced to parse, see the class comment.
     */
    private String render(String template, Map<String, Object> data) throws ITemplatingEngine.TemplateEngineException {
        return engine.processTemplate(FORCE_PARSE + LlmTask.escapeConfigReferenceMentions(template), data);
    }

    /**
     * The outcome of rendering {@code text} as typed: its output, or {@code null}
     * when the render fails.
     */
    private String renderOrNull(String text) {
        try {
            return render(text, Map.of());
        } catch (ITemplatingEngine.TemplateEngineException | RuntimeException e) {
            return null;
        }
    }

    @TestFactory
    List<DynamicTest> everyCaseBehavesAsTheCorpusRecordsItForTheRealEngine() throws IOException {
        List<DynamicTest> tests = new ArrayList<>();
        String contextBlock = contextBlock();
        for (JsonNode c : cases()) {
            String name = c.get("name").asText();
            String text = c.get("text").asText();
            String kind = c.get("kind").asText();
            String escaped = c.get("escaped").asText();
            tests.add(DynamicTest.dynamicTest(kind + ": " + name, () -> check(kind, text, escaped, contextBlock)));
        }
        return tests;
    }

    private String contextBlock() throws IOException {
        for (JsonNode c : cases()) {
            if ("the context block alone".equals(c.get("name").asText())) {
                return c.get("text").asText();
            }
        }
        return fail("the corpus has no 'the context block alone' case");
    }

    private void check(String kind, String text, String escaped, String contextBlock) throws Exception {
        switch (kind) {
            case "expression" -> {
                String rendered = renderOrNull(text);
                assertTrue(rendered == null || !rendered.equals(text),
                        "the engine must change (or reject) this, or the case is not an expression: " + text);
                assertEquals(text, render(escaped, Map.of()), "the escaped form must render back to the text: " + escaped);
            }
            case "inert" -> {
                assertEquals(text, renderOrNull(text), "the engine leaves an inert case alone, even when forced to parse it");
                assertEquals(text, render(escaped, Map.of()), "the escaped form must render back to the text: " + escaped);
            }
            case "mention" -> {
                assertEquals(text, render(escaped, Map.of()), "a reference mention reaches the model exactly as written: " + escaped);
                assertFalse(escaped.contains("{|{vault"), "the escape must split around a mention, not wrap it: " + escaped);
            }
            case "raw" -> assertEquals(text.replaceAll("\\{\\|([\\s\\S]*?)\\|\\}", "$1"), render(escaped, Map.of()),
                    "an unparsed block the author wrote is kept and unwrapped by the engine");
            case "live" -> {
                Map<String, Object> data = Map.of("context", Map.of("screen", "Home", "agentId", "a1"));
                assertNull(engine.validateTemplate(contextBlock), "the context block must parse");
                String block = render(contextBlock, data);
                assertTrue(block.contains("currently viewing: Home (agent a1)"), "the context block must be evaluated: " + block);
                // The block's closing {/if} is a line of its own, which the engine removes
                // together
                // with its newline (quarkus.qute.remove-standalone-lines): the block ends where
                // it ends.
                String blockAndNewline = contextBlock + "\n";
                String expected = text.contains(blockAndNewline) ? text.replace(blockAndNewline, block) : text.replace(contextBlock, block);
                assertEquals(expected, render(escaped, data), "the context block is evaluated, every other brace comes back as typed");
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
        assertEquals(Set.of("expression", "inert", "mention", "raw", "live"), kinds);
    }

    /**
     * Why the harness forces a parse: the engine skips a string with none of its
     * control characters, so {@code {0}} and {@code {_x}} come back as typed on
     * their own, whatever they would do inside a real prompt.
     */
    @Test
    void theEngineOnlyParsesATemplateThatHasAControlCharacter() throws Exception {
        assertEquals("{0}", engine.processTemplate("{0}", Map.of()), "skipped, never parsed");
        assertNotEquals("{0} {input}", engine.processTemplate("{0} {input}", Map.of()), "parsed: one control character is enough");
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
