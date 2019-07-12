package ai.labs.parser.extensions.corrections;

import ai.labs.expressions.Expression;
import ai.labs.expressions.ExpressionFactory;
import ai.labs.expressions.utilities.ExpressionProvider;
import ai.labs.expressions.utilities.IExpressionProvider;
import ai.labs.parser.extensions.dictionaries.IDictionary;
import ai.labs.parser.model.Phrase;
import ai.labs.parser.model.Word;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * @author ginccc
 */
public class DamerauLevenshteinCorrectionTest {
    private IExpressionProvider expressionUtilities;

    @BeforeEach
    public void setup() {
        expressionUtilities = new ExpressionProvider(new ExpressionFactory());
    }

    @Test
    void testLookupTermDistanceOfOne() throws Exception {
        //setup
        TestDictionary testDictionary = new TestDictionary();
        DamerauLevenshteinCorrection levenshteinCorrection = new DamerauLevenshteinCorrection(); // max distance of 2
        levenshteinCorrection.init(Arrays.asList(new IDictionary[]{testDictionary}));

        //test
        List<IDictionary.IFoundWord> foundWords = levenshteinCorrection.correctWord("helo");

        //assert
        Assertions.assertEquals(1, foundWords.size());
    }

    @Test
    void testLookupTermDistanceOfTwo() throws Exception {
        //setup
        TestDictionary testDictionary = new TestDictionary();
        DamerauLevenshteinCorrection levenshteinCorrection = new DamerauLevenshteinCorrection(); // max distance of 2
        levenshteinCorrection.init(Arrays.asList(new IDictionary[]{testDictionary}));

        //test
        List<IDictionary.IFoundWord> foundWords = levenshteinCorrection.correctWord("heo");

        //assert
        Assertions.assertEquals(1, foundWords.size());
    }

    @Test
    void testLookupTermDistanceOfThree() throws Exception {
        //setup
        TestDictionary testDictionary = new TestDictionary();
        DamerauLevenshteinCorrection levenshteinCorrection = new DamerauLevenshteinCorrection(); // max distance of 2
        levenshteinCorrection.init(Arrays.asList(new IDictionary[]{testDictionary}));

        //test
        List<IDictionary.IFoundWord> foundWords = levenshteinCorrection.correctWord("he");

        //assert
        Assertions.assertEquals(0, foundWords.size());
    }

    private class TestDictionary implements IDictionary {
        @Override
        public List<IWord> getWords() {
            return Arrays.asList(new IWord[]
                    {
                            new Word("hello", Arrays.asList(new Expression[0]), "", 0, false),
                            new Word("world", Arrays.asList(new Expression[0]), "", 0, false)
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
