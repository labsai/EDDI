package ai.labs.eddi.modules.nlp.extensions.dictionaries;

import ai.labs.eddi.modules.nlp.expressions.Expression;
import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.nlp.model.FoundDictionaryEntry;
import ai.labs.eddi.modules.nlp.model.FoundWord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RegularDictionaryTest {

    private RegularDictionary dict;

    @BeforeEach
    void setUp() {
        dict = new RegularDictionary();
    }

    @Test
    void addWord_andLookup() {
        dict.addWord("hello", new Expressions(new Expression("greeting")), 0);
        var result = dict.lookupTerm("hello");
        assertEquals(1, result.size());
        assertEquals("hello", result.get(0).getFoundWord().getValue());
    }

    @Test
    void lookupTerm_caseInsensitive() {
        dict.addWord("Hello", new Expressions(new Expression("greeting")), 0);
        var result = dict.lookupTerm("hello");
        assertFalse(result.isEmpty());
        // case-insensitive match → corrected=true, accuracy=0.9
        var found = (FoundDictionaryEntry) result.get(0);
        assertTrue(found.isIsCorrected());
        assertEquals(0.9, found.getMatchingAccuracy(), 0.01);
    }

    @Test
    void lookupTerm_caseSensitive() {
        dict.addWord("Hello", new Expressions(new Expression("greeting")), 0);
        var result = dict.lookupTerm("Hello");
        assertFalse(result.isEmpty());
        // exact match → corrected=false, accuracy=1.0
        var found = (FoundDictionaryEntry) result.get(0);
        assertFalse(found.isIsCorrected());
        assertEquals(1.0, found.getMatchingAccuracy(), 0.01);
    }

    @Test
    void lookupTerm_notFound() {
        dict.addWord("hello", new Expressions(new Expression("greeting")), 0);
        var result = dict.lookupTerm("nonexistent");
        assertTrue(result.isEmpty());
    }

    @Test
    void getWords_empty() {
        assertTrue(dict.getWords().isEmpty());
    }

    @Test
    void getWords_afterAdd() {
        dict.addWord("a", new Expressions(), 0);
        dict.addWord("b", new Expressions(), 0);
        assertEquals(2, dict.getWords().size());
    }

    @Test
    void getPhrases_empty() {
        assertTrue(dict.getPhrases().isEmpty());
    }

    @Test
    void addPhrase() {
        dict.addPhrase("how are you", new Expressions(new Expression("greeting")));
        assertEquals(1, dict.getPhrases().size());
    }

    @Test
    void addPhrase_wordsIncludedInGetWords() {
        dict.addPhrase("good morning", new Expressions(new Expression("greeting")));
        // Phrases contribute words via getWords()
        assertFalse(dict.getWords().isEmpty());
    }

    @Test
    void addRegex() {
        dict.addRegex("\\d+", new Expressions(new Expression("number")));
        assertEquals(1, dict.getRegExs().size());
    }

    @Test
    void lookupTerm_regex() {
        dict.addRegex("\\d+", new Expressions(new Expression("number")));
        var result = dict.lookupTerm("42");
        assertFalse(result.isEmpty());
    }

    @Test
    void lookupTerm_regexNoMatch() {
        dict.addRegex("\\d+", new Expressions(new Expression("number")));
        var result = dict.lookupTerm("abc");
        assertTrue(result.isEmpty());
    }

    @Test
    void lookupIfKnown_default() {
        assertFalse(dict.lookupIfKnown());
    }

    @Test
    void lookupIfKnown_setter() {
        dict.setLookupIfKnown(true);
        assertTrue(dict.lookupIfKnown());
        assertTrue(dict.isLookupIfKnown());
    }

    @Test
    void getWords_unmodifiable() {
        dict.addWord("test", new Expressions(), 0);
        var words = dict.getWords();
        assertThrows(UnsupportedOperationException.class, () -> words.add(null));
    }

    @Test
    void getPhrases_unmodifiable() {
        dict.addPhrase("test phrase", new Expressions());
        var phrases = dict.getPhrases();
        assertThrows(UnsupportedOperationException.class, () -> phrases.add(null));
    }
}
