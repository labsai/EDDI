/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.templating;

import ai.labs.eddi.modules.templating.ITemplatingEngine.TemplateMode;
import ai.labs.eddi.modules.templating.impl.TemplatingEngine;
import io.quarkus.qute.Engine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@link TemplateEscaping#unparsedBlock} must render back to exactly its input,
 * whatever the input is — the sub-agent tool feeds it text a model wrote.
 */
@DisplayName("TemplateEscaping.unparsedBlock")
class TemplateEscapingTest {

    private ITemplatingEngine templatingEngine;

    @BeforeEach
    void setUp() {
        templatingEngine = new TemplatingEngine(Engine.builder().addDefaults().strictRendering(false).build());
    }

    private String roundTrip(String content) throws Exception {
        return templatingEngine.processTemplate(TemplateEscaping.unparsedBlock(content),
                Map.of("properties", Map.of("name", "LEAKED"), "vars", Map.of("apiKey", "s3cret")), TemplateMode.TEXT);
    }

    @Test
    void nullAndEmptyAreReturnedUnchanged() {
        assertNull(TemplateEscaping.unparsedBlock(null));
        assertEquals("", TemplateEscaping.unparsedBlock(""));
    }

    /**
     * Qute counts every pipe directly after the opening brace as part of the
     * opener, so wrapping {@code |x} naively yields {@code {||x|}} — a two-pipe
     * block the single-pipe terminator never closes, and the render fails.
     */
    @ParameterizedTest
    @ValueSource(strings = {"|", "||", "|x", "||x {vars.apiKey}", "|}", "|} {properties.name}", "||} tail", "| {#for i in 3}x{/for}"})
    @DisplayName("content starting with a pipe renders literally")
    void leadingPipes(String content) throws Exception {
        assertEquals(content, roundTrip(content));
    }

    @ParameterizedTest
    @ValueSource(strings = {"a|} {properties.name} b", "x|", "x||", "a||}b", "|}|}|}", "{|nested|}", "{vars.apiKey}", "end|"})
    @DisplayName("terminators and markers anywhere in the content stay literal")
    void terminatorsAndMarkers(String content) throws Exception {
        assertEquals(content, roundTrip(content));
    }

    /**
     * Exhaustive-ish sweep over the characters that matter to the unparsed-block
     * parser, so the next delimiter corner case is caught here rather than by a
     * model-written prompt in production.
     */
    @Test
    @DisplayName("any mix of braces, pipes and text round-trips byte-identically")
    void randomisedRoundTrip() throws Exception {
        char[] alphabet = {'{', '}', '|', 'a', ' ', '#', '!'};
        Random random = new Random(832);
        for (int i = 0; i < 5000; i++) {
            StringBuilder content = new StringBuilder();
            int length = 1 + random.nextInt(12);
            for (int j = 0; j < length; j++) {
                content.append(alphabet[random.nextInt(alphabet.length)]);
            }
            String input = content.toString();
            assertEquals(input, roundTrip(input), () -> "round trip failed for [" + input + "]");
        }
    }
}
