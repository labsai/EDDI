/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp;

import ai.labs.eddi.engine.TestMemoryFactory;
import ai.labs.eddi.engine.TestMemoryFactory.MemoryContext;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.MemoryKey;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.Data;
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

    private static IInputParser.Config passThroughConfig() {
        var config = new IInputParser.Config();
        config.setPassThrough(true);
        return config;
    }

    private boolean matches(String storedExpressions, String ruleExpressions) {
        return Collections.indexOfSubList(provider.parseExpressions(storedExpressions), provider.parseExpressions(ruleExpressions)) > -1;
    }

    @Test
    @DisplayName("passThrough: one unknown expression, interior kept as typed, ends trimmed, and a catch-all rule matches")
    void passThroughKeepsInterior() {
        String typed = "  line one\n    indented   code  \n";
        MemoryContext ctx = TestMemoryFactory.createWithInput(typed);

        task.execute(ctx.memory(), emptyParser(passThroughConfig()));

        String stored = storedExpressions(ctx);
        assertEquals("unknown(line one\n indented code)", stored);
        assertTrue(matches(stored, "*"));
        verify(ctx.currentStep()).addConversationOutputString(MemoryKeys.INPUT.key(), "line one\n    indented   code");
    }

    @Test
    @DisplayName("a bare parser without passThrough still yields one unknown per word, and unknown(hello) matches as before")
    void defaultKeepsPerWordUnknowns() {
        MemoryContext ctx = TestMemoryFactory.createWithInput("hello   world");

        task.execute(ctx.memory(), emptyParser(new IInputParser.Config()));

        String stored = storedExpressions(ctx);
        assertEquals("unknown(hello), unknown(world)", stored);
        assertTrue(matches(stored, "unknown(hello)"));
        assertTrue(matches(stored, "unknown(hello), unknown(world)"));
        assertTrue(matches(stored, "*"));
        // no normalizers: the interior is as typed, the ends are trimmed
        verify(ctx.currentStep()).addConversationOutputString(MemoryKeys.INPUT.key(), "hello   world");
    }

    @Test
    @DisplayName("a trailing newline is stripped from the output input so that a value comparison still matches")
    void trailingNewlineStripped() {
        MemoryContext ctx = TestMemoryFactory.createWithInput("yes\n");

        task.execute(ctx.memory(), emptyParser(new IInputParser.Config()));

        verify(ctx.currentStep()).addConversationOutputString(MemoryKeys.INPUT.key(), "yes");
    }

    @Test
    @DisplayName("the passThrough expression is bounded, cut outside a surrogate pair, and notation characters are removed")
    void passThroughIsBoundedAndSurrogateSafe() {
        String emoji = new String(Character.toChars(0x1F600));
        // 999 letters, then a pair that straddles the 1000 character limit
        MemoryContext ctx = TestMemoryFactory.createWithInput("x), intent(admin" + "a".repeat(983) + emoji + "tail");

        task.execute(ctx.memory(), emptyParser(passThroughConfig()));

        String stored = storedExpressions(ctx);
        assertTrue(stored.length() < 1100, "bounded: " + stored.length());
        for (int i = 0; i < stored.length(); i++) {
            char c = stored.charAt(i);
            if (Character.isHighSurrogate(c)) {
                assertTrue(i + 1 < stored.length() && Character.isLowSurrogate(stored.charAt(i + 1)), "split pair at " + i);
            }
        }
        Expressions parsed = provider.parseExpressions(stored);
        assertEquals(1, parsed.size());
        assertEquals("unknown", parsed.getFirst().getExpressionName());
    }

    @Test
    @DisplayName("an empty text yields no expression")
    void emptyText() {
        MemoryContext ctx = TestMemoryFactory.createWithInput("   ");

        task.execute(ctx.memory(), emptyParser(passThroughConfig()));

        assertNull(storedExpressions(ctx));
    }

    @Test
    @DisplayName("preserveInput=false puts the normalised text in the output")
    void preserveInputSwitchedOff() {
        var config = new IInputParser.Config();
        config.setPreserveInput(false);
        MemoryContext ctx = TestMemoryFactory.createWithInput("hello   world");

        task.execute(ctx.memory(), emptyParser(config));

        verify(ctx.currentStep()).addConversationOutputString(MemoryKeys.INPUT.key(), "hello world");
    }

    @Test
    @DisplayName("a parser with a normalizer still replaces the output input")
    void normalizerOverwritesInput() {
        INormalizer upper = (sentence, language) -> sentence.toUpperCase();
        var parser = new InputParser(List.of(upper), Collections.emptyList(), Collections.emptyList(), passThroughConfig());
        MemoryContext ctx = TestMemoryFactory.createWithInput("hello");

        task.execute(ctx.memory(), parser);

        assertFalse(parser.isPassThrough());
        verify(ctx.currentStep()).addConversationOutputString(MemoryKeys.INPUT.key(), "HELLO");
    }

    @Test
    @DisplayName("a parser with a dictionary does not take the fast path")
    void dictionaryParserIsNotPassThrough() {
        var parser = new InputParser(Collections.emptyList(), List.of(mock(IDictionary.class)), Collections.emptyList(), passThroughConfig());

        assertFalse(parser.isPassThrough());
    }

    @Test
    @DisplayName("one unbroken 40,000 character token no longer produces an over-limit result")
    void longTokenIsDegraded() {
        MemoryContext ctx = TestMemoryFactory.createWithInput("a".repeat(40_000));

        task.execute(ctx.memory(), emptyParser(new IInputParser.Config()));

        String stored = storedExpressions(ctx);
        assertNotNull(stored);
        assertTrue(stored.length() <= ExpressionLimits.DEFAULT_MAX_LENGTH);
        assertDoesNotThrow(() -> provider.parseExpressions(stored));
        assertTrue(matches(stored, "*"));
    }

    @Test
    @DisplayName("200 tokens of 165 characters are degraded, not fatal")
    void manyLongTokensAreDegraded() {
        String token = "b".repeat(165);
        MemoryContext ctx = TestMemoryFactory.createWithInput((token + " ").repeat(200));

        task.execute(ctx.memory(), emptyParser(new IInputParser.Config()));

        String stored = storedExpressions(ctx);
        assertTrue(stored.length() <= ExpressionLimits.DEFAULT_MAX_LENGTH, "length " + stored.length());
        assertDoesNotThrow(() -> provider.parseExpressions(stored));
    }

    @Test
    @DisplayName("an over-long stored value reads as one bounded unknown, not as 'the user said nothing'")
    void storedExpressionsOverLimitReadAsUnknown() {
        String tooLong = "unknown(" + "a".repeat(40_000) + ")";

        assertThrows(IllegalArgumentException.class, () -> provider.parseExpressions(tooLong));
        Expressions read = StoredExpressions.parseOrEmpty(provider, tooLong);
        assertEquals(1, read.size());
        assertEquals("unknown", read.getFirst().getExpressionName());
        assertEquals(1, StoredExpressions.parseOrEmpty(provider, "unknown(a)").size());
    }

    @Test
    @DisplayName("with appendExpressions, the earlier result is kept when a result that fits alone does not fit beside it")
    void appendKeepsEarlierResultWhenOnlyTheMergeIsTooLong() {
        var smallProvider = new ExpressionProvider(new ExpressionFactory(), new ExpressionLimits(40, 32));
        var smallTask = new InputParserTask(smallProvider, new HashMap<>(), new HashMap<>(), new HashMap<>(), new ObjectMapper(), null);
        MemoryContext ctx = TestMemoryFactory.createWithInput("hello wonderful world");
        var earlier = new Data<>("expressions:parsed", "greeting(hello)");
        Data<String> input = new Data<>("input", "hello wonderful world");
        doAnswer(invocation -> {
            MemoryKey<?> key = invocation.getArgument(0);
            return "expressions:parsed".equals(key.key()) ? earlier : "input".equals(key.key()) ? input : null;
        }).when(ctx.currentStep()).getLatestData(any(MemoryKey.class));
        var config = passThroughConfig();
        config.setAppendExpressions(true);

        smallTask.execute(ctx.memory(), emptyParser(config));

        assertEquals("greeting(hello)", storedExpressions(ctx));
    }

    @Test
    @DisplayName("with appendExpressions, earlier results survive an over-long new result")
    void appendKeepsEarlierResults() {
        MemoryContext ctx = TestMemoryFactory.createWithInput("a".repeat(40_000));
        var earlier = new Data<>("expressions:parsed", "greeting(hello)");
        Data<String> input = new Data<>("input", "a".repeat(40_000));
        doAnswer(invocation -> {
            MemoryKey<?> key = invocation.getArgument(0);
            return "expressions:parsed".equals(key.key()) ? earlier : "input".equals(key.key()) ? input : null;
        }).when(ctx.currentStep()).getLatestData(any(MemoryKey.class));

        task.execute(ctx.memory(), emptyParser(new IInputParser.Config()));

        String stored = storedExpressions(ctx);
        assertTrue(stored.startsWith("greeting(hello)"), stored);
        assertDoesNotThrow(() -> provider.parseExpressions(stored));
    }
}
