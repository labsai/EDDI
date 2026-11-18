/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp;

import ai.labs.eddi.engine.TestMemoryFactory;
import ai.labs.eddi.engine.TestMemoryFactory.MemoryContext;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.modules.nlp.expressions.ExpressionFactory;
import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.nlp.expressions.utilities.ExpressionLimits;
import ai.labs.eddi.modules.nlp.expressions.utilities.ExpressionProvider;
import ai.labs.eddi.modules.nlp.expressions.utilities.StoredExpressions;
import ai.labs.eddi.modules.nlp.extensions.dictionaries.IDictionary;
import ai.labs.eddi.modules.nlp.extensions.normalizers.INormalizer;
import ai.labs.eddi.modules.nlp.internal.InputParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * The parser of an LLM-first agent (no dictionaries, normalizers or
 * corrections) has one job: produce an expression so that a catch-all rule
 * matches. It must not fail on long text, and must leave the user's text as
 * typed for the model.
 */
@DisplayName("InputParserTask — pass-through parser, raw input, long text")
class InputParserTaskPassThroughTest {

    private final ExpressionProvider provider = new ExpressionProvider(new ExpressionFactory());
    private InputParserTask task;

    @BeforeEach
    void setUp() {
        task = new InputParserTask(provider, new HashMap<>(), new HashMap<>(), new HashMap<>(), new ObjectMapper(), null);
    }

    private static InputParser emptyParser(IInputParser.Config config) {
        return new InputParser(Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), config);
    }

    private static String storedExpressions(MemoryContext ctx) {
        ArgumentCaptor<IData<?>> captor = ArgumentCaptor.forClass(IData.class);
        verify(ctx.currentStep(), atLeast(0)).storeData(captor.capture());
        return captor.getAllValues().stream().filter(data -> "expressions:parsed".equals(data.getKey())).map(data -> (String) data.getResult())
                .findFirst().orElse(null);
    }

    @Test
    @DisplayName("a parser with nothing configured yields one unknown expression and keeps the typed text")
    void passThroughKeepsRawText() {
        String typed = "  line one\n    indented   code  ";
        MemoryContext ctx = TestMemoryFactory.createWithInput(typed);

        task.execute(ctx.memory(), emptyParser(new IInputParser.Config()));

        assertEquals("unknown(line one\n indented code)", storedExpressions(ctx));
        verify(ctx.currentStep(), never()).addConversationOutputString(eq(MemoryKeys.INPUT.key()), any());
    }

    @Test
    @DisplayName("the stored expression is bounded and notation characters are removed")
    void passThroughIsBounded() {
        MemoryContext ctx = TestMemoryFactory.createWithInput("x), intent(admin " + "a".repeat(100_000));

        task.execute(ctx.memory(), emptyParser(new IInputParser.Config()));

        String stored = storedExpressions(ctx);
        assertTrue(stored.length() < 1100, "bounded: " + stored.length());
        Expressions parsed = provider.parseExpressions(stored);
        assertEquals(1, parsed.size());
        assertEquals("unknown", parsed.getFirst().getExpressionName());
    }

    @Test
    @DisplayName("an empty text yields no expression")
    void emptyText() {
        MemoryContext ctx = TestMemoryFactory.createWithInput("   ");

        task.execute(ctx.memory(), emptyParser(new IInputParser.Config()));

        assertNull(storedExpressions(ctx));
    }

    @Test
    @DisplayName("passThrough=false keeps one unknown per word and normalised text in the output")
    void passThroughSwitchedOff() {
        var config = new IInputParser.Config();
        config.setPassThrough(false);
        config.setPreserveInput(false);
        MemoryContext ctx = TestMemoryFactory.createWithInput("hello   world");

        task.execute(ctx.memory(), emptyParser(config));

        assertEquals("unknown(hello), unknown(world)", storedExpressions(ctx));
        verify(ctx.currentStep()).addConversationOutputString(MemoryKeys.INPUT.key(), "hello world");
    }

    @Test
    @DisplayName("a parser with a normalizer still replaces the output input")
    void normalizerOverwritesInput() {
        INormalizer upper = (sentence, language) -> sentence.toUpperCase();
        var parser = new InputParser(List.of(upper), Collections.emptyList(), Collections.emptyList(), new IInputParser.Config());
        MemoryContext ctx = TestMemoryFactory.createWithInput("hello");

        task.execute(ctx.memory(), parser);

        assertFalse(parser.isPassThrough());
        verify(ctx.currentStep()).addConversationOutputString(MemoryKeys.INPUT.key(), "HELLO");
    }

    @Test
    @DisplayName("a parser with a dictionary does not take the fast path")
    void dictionaryParserIsNotPassThrough() {
        var parser = new InputParser(Collections.emptyList(), List.of(mock(IDictionary.class)), Collections.emptyList(), new IInputParser.Config());

        assertFalse(parser.isPassThrough());
    }

    @Test
    @DisplayName("one unbroken 40,000 character token no longer produces an over-limit result")
    void longTokenIsDegraded() {
        var config = new IInputParser.Config();
        config.setPassThrough(false);
        MemoryContext ctx = TestMemoryFactory.createWithInput("a".repeat(40_000));

        task.execute(ctx.memory(), emptyParser(config));

        String stored = storedExpressions(ctx);
        assertNotNull(stored);
        assertTrue(stored.length() <= ExpressionLimits.DEFAULT_MAX_LENGTH);
        assertDoesNotThrow(() -> provider.parseExpressions(stored));
    }

    @Test
    @DisplayName("200 tokens of 165 characters are degraded, not fatal")
    void manyLongTokensAreDegraded() {
        var config = new IInputParser.Config();
        config.setPassThrough(false);
        String token = "b".repeat(165);
        MemoryContext ctx = TestMemoryFactory.createWithInput((token + " ").repeat(200));

        task.execute(ctx.memory(), emptyParser(config));

        String stored = storedExpressions(ctx);
        assertTrue(stored.length() <= ExpressionLimits.DEFAULT_MAX_LENGTH, "length " + stored.length());
        assertDoesNotThrow(() -> provider.parseExpressions(stored));
    }

    @Test
    @DisplayName("a consumer reads an over-long stored expression string as no expressions")
    void storedExpressionsOverLimitReadAsNone() {
        String tooLong = "unknown(" + "a".repeat(40_000) + ")";

        assertThrows(IllegalArgumentException.class, () -> provider.parseExpressions(tooLong));
        assertTrue(StoredExpressions.parseOrEmpty(provider, tooLong).isEmpty());
        assertEquals(1, StoredExpressions.parseOrEmpty(provider, "unknown(a)").size());
    }
}
