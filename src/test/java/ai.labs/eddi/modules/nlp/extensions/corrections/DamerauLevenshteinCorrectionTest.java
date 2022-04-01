package ai.labs.eddi.modules.nlp.extensions.corrections;

import ai.labs.eddi.modules.nlp.expressions.ExpressionFactory;
import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.nlp.expressions.utilities.ExpressionProvider;
import ai.labs.eddi.modules.nlp.expressions.utilities.IExpressionProvider;
import ai.labs.eddi.modules.nlp.extensions.dictionaries.IDictionary;
import ai.labs.eddi.modules.nlp.model.Phrase;
import ai.labs.eddi.modules.nlp.model.Word;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author ginccc
 */
public class DamerauLevenshteinCorrectionTest {
    private static final String DEFAULT_LANGUAGE = "en";
    private IExpressionProvider expressionUtilities;

    @BeforeEach
    public void setup() {
        expressionUtilities = new ExpressionProvider(new ExpressionFactory());
    }

    @Test
    public void testLookupTermDistanceOfOne() {
        //setup
        TestDictionary testDictionary = new TestDictionary();
        DamerauLevenshteinCorrection levenshteinCorrection = new DamerauLevenshteinCorrection(); // max distance of 2
        levenshteinCorrection.init(Arrays.asList(new IDictionary[]{testDictionary}));

        //test
        List<IDictionary.IFoundWord> foundWords = levenshteinCorrection.correctWord("helo", DEFAULT_LANGUAGE);

        //assert
        assertEquals(1, foundWords.size());
    }

    @Test
    public void testLookupTermDistanceOfTwo() {
        //setup
        TestDictionary testDictionary = new TestDictionary();
        DamerauLevenshteinCorrection levenshteinCorrection = new DamerauLevenshteinCorrection(); // max distance of 2
        levenshteinCorrection.init(Arrays.asList(new IDictionary[]{testDictionary}));

        //test
        List<IDictionary.IFoundWord> foundWords = levenshteinCorrection.correctWord("heo", DEFAULT_LANGUAGE);

        //assert
        assertEquals(1, foundWords.size());
    }

    @Test
    public void testLookupTermDistanceOfThree() {
        //setup
        TestDictionary testDictionary = new TestDictionary();
        DamerauLevenshteinCorrection levenshteinCorrection = new DamerauLevenshteinCorrection(); // max distance of 2
        levenshteinCorrection.init(Arrays.asList(new IDictionary[]{testDictionary}));

        //test
        List<IDictionary.IFoundWord> foundWords = levenshteinCorrection.correctWord("he", DEFAULT_LANGUAGE);

        //assert
        assertEquals(0, foundWords.size());
    }

    private class TestDictionary implements IDictionary {
        @Override
        public List<IWord> getWords() {
            return Arrays.asList(new IWord[]
                    {
                            new Word("hello", new Expressions(), "", 0, false),
                            new Word("world", new Expressions(), "", 0, false)
                    });
        }

        @Override
        public List<IPhrase> getPhrases() {
            return Arrays.asList(new IPhrase[]
                    {
                            new Phrase("good morning", expressionUtilities.parseExpressions("good morning"), ""),
                            new Phrase("day after tomorrow", expressionUtilities.parseExpressions("day after tomorrow"), "")
                    });
        }

        @Override
        public List<IFoundWord> lookupTerm(String value) {
            return Collections.emptyList();
        }

        @Override
        public boolean lookupIfKnown() {
            return false;
        }
    }
}
