package ai.labs.parser.extensions.corrections;

import ai.labs.expressions.ExpressionFactory;
import ai.labs.expressions.Expressions;
import ai.labs.expressions.utilities.ExpressionProvider;
import ai.labs.expressions.utilities.IExpressionProvider;
import ai.labs.parser.extensions.dictionaries.IDictionary;
import ai.labs.parser.model.Phrase;
import ai.labs.parser.model.Word;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * @author ginccc
 */
public class DamerauLevenshteinCorrectionTest {
    private IExpressionProvider expressionUtilities;

    @Before
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
        List<IDictionary.IFoundWord> foundWords = levenshteinCorrection.correctWord("helo");

        //assert
        Assert.assertEquals(1, foundWords.size());
    }

    @Test
    public void testLookupTermDistanceOfTwo() {
        //setup
        TestDictionary testDictionary = new TestDictionary();
        DamerauLevenshteinCorrection levenshteinCorrection = new DamerauLevenshteinCorrection(); // max distance of 2
        levenshteinCorrection.init(Arrays.asList(new IDictionary[]{testDictionary}));

        //test
        List<IDictionary.IFoundWord> foundWords = levenshteinCorrection.correctWord("heo");

        //assert
        Assert.assertEquals(1, foundWords.size());
    }

    @Test
    public void testLookupTermDistanceOfThree() {
        //setup
        TestDictionary testDictionary = new TestDictionary();
        DamerauLevenshteinCorrection levenshteinCorrection = new DamerauLevenshteinCorrection(); // max distance of 2
        levenshteinCorrection.init(Arrays.asList(new IDictionary[]{testDictionary}));

        //test
        List<IDictionary.IFoundWord> foundWords = levenshteinCorrection.correctWord("he");

        //assert
        Assert.assertEquals(0, foundWords.size());
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
