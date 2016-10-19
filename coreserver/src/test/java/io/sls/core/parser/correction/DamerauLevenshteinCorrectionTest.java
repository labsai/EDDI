package io.sls.core.parser.correction;

import io.sls.expressions.utilities.IExpressionUtilities;
import io.sls.runtime.DependencyInjector;
import org.junit.Before;

/**
 * User: jarisch
 * Date: 15.04.12
 * Time: 01:20
 */
public class DamerauLevenshteinCorrectionTest {
    private IExpressionUtilities expressionUtilities;

    @Before
    public void setup() {
        expressionUtilities = DependencyInjector.getInstance().getInstance(IExpressionUtilities.class);
    }

    //TODO make DI work for tests
    /*@Test
    public void testLookupTermDistanceOfOne() throws Exception {
        //setup
        TestDictionary testDictionary = new TestDictionary();
        DamerauLevenshteinCorrection levenshteinCorrection = new DamerauLevenshteinCorrection(true); // max distance of 2
        levenshteinCorrection.init(Arrays.asList(new IDictionary[]{testDictionary}));

        //test
        IDictionary.IFoundWord[] foundWords = levenshteinCorrection.correctWord("helo");

        //assert
        Assert.assertEquals(1, foundWords.length);
    }

    @Test
    public void testLookupTermDistanceOfTwo() throws Exception {
        //setup
        TestDictionary testDictionary = new TestDictionary();
        DamerauLevenshteinCorrection levenshteinCorrection = new DamerauLevenshteinCorrection(true); // max distance of 2
        levenshteinCorrection.init(Arrays.asList(new IDictionary[]{testDictionary}));

        //test
        IDictionary.IFoundWord[] foundWords = levenshteinCorrection.correctWord("heo");

        //assert
        Assert.assertEquals(1, foundWords.length);
    }

    @Test
    public void testLookupTermDistanceOfThree() throws Exception {
        //setup
        TestDictionary testDictionary = new TestDictionary();
        DamerauLevenshteinCorrection levenshteinCorrection = new DamerauLevenshteinCorrection(true); // max distance of 2
        levenshteinCorrection.init(Arrays.asList(new IDictionary[]{testDictionary}));

        //test
        IDictionary.IFoundWord[] foundWords = levenshteinCorrection.correctWord("he");

        //assert
        Assert.assertEquals(0, foundWords.length);
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
        public IFoundWord[] lookupTerm(String value) {
            return new IFoundWord[0];
        }

        @Override
        public String getLanguage() {
            return "";
        }

        @Override
        public boolean lookupIfKnown() {
            return false;
        }
    }*/
}
