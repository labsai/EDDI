package ai.labs.eddi.modules.nlp.extensions.corrections;

import ai.labs.eddi.modules.nlp.expressions.Expression;
import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.nlp.extensions.dictionaries.IDictionary;
import ai.labs.eddi.modules.nlp.model.Word;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PhoneticCorrectionTest {

    private PhoneticCorrection correction;

    @BeforeEach
    void setUp() {
        correction = new PhoneticCorrection(true);
        var dictionary = mock(IDictionary.class);
        var words = List.<IDictionary.IWord>of(
                new Word("hello", new Expressions(new Expression("g1")), "test", 0, false),
                new Word("world", new Expressions(new Expression("g2")), "test", 0, false),
                new Word("phone", new Expressions(new Expression("g3")), "test", 0, false));
        when(dictionary.getWords()).thenReturn(words);
        correction.init(List.of(dictionary));
    }

    @Test
    void lookupIfKnown_returnsConfiguredValue() {
        assertTrue(correction.lookupIfKnown());
    }

    @Test
    void lookupIfKnown_false() {
        var c = new PhoneticCorrection(false);
        assertFalse(c.lookupIfKnown());
    }

    @Test
    void correctWord_exactPhonetic() {
        // "hello" should phonetically match "hello"
        var result = correction.correctWord("hello", null, Collections.emptyList());
        assertNotNull(result);
        assertFalse(result.isEmpty());
    }

    @Test
    void correctWord_anotherKnownWord() {
        // "world" should phonetically match itself
        var result = correction.correctWord("world", null, Collections.emptyList());
        assertNotNull(result);
        assertFalse(result.isEmpty());
    }

    @Test
    void correctWord_unknownWord_returnsEmptyList() {
        // Regression test for NPE fix: unknown words with no phonetic match
        // should return an empty list, not throw NullPointerException
        var result = correction.correctWord("xyzzy12345", null, Collections.emptyList());
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    void init_emptyDictionary() {
        var empty = new PhoneticCorrection(false);
        var dictionary = mock(IDictionary.class);
        when(dictionary.getWords()).thenReturn(Collections.emptyList());
        empty.init(List.of(dictionary));

        // Should handle empty dictionaries gracefully
        var result = empty.correctWord("anything", null, Collections.emptyList());
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }
}
