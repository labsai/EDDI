package io.sls.core.parser.internal;

import io.sls.expressions.utilities.IExpressionUtilities;
import io.sls.runtime.DependencyInjector;
import org.junit.Before;

/**
 * User: jarisch
 * Date: 31.03.12
 * Time: 17:00
 */
public class InputParserTest {
    private IExpressionUtilities expressionUtilities;

    @Before
    public void setup() {
        expressionUtilities = DependencyInjector.getInstance().getInstance(IExpressionUtilities.class);
    }

    //TODO make DI work for tests
    /*@Test
    public void testParseWord() throws Exception {
        //setup
        String lookupString = "test1 test2";
        TestDictionary testDictionary = new TestDictionary();
        testDictionary.addWord(new Word("test1", expressionUtilities.parseExpressions("expression1"), "exp1", 0, false));
        testDictionary.addWord(new Word("test2", expressionUtilities.parseExpressions("expression2"), "exp2", 0, false));
        InputParser inputParser = new InputParser(Arrays.asList((IDictionary) testDictionary));

        //test
        List<Solution> suggestions = inputParser.parse(lookupString);

        //assert
        Assert.assertEquals(1, suggestions.size());
        Assert.assertEquals("test1", suggestions.get(0).getDictionaryEntries().get(0).getValue());
        Assert.assertEquals("test2", suggestions.get(0).getDictionaryEntries().get(1).getValue());
    }

    @Test
    public void testParsePhrase() throws Exception {
        //setup
        String lookupString = "day after tomorrow";
        TestDictionary testDictionary = new TestDictionary();
        testDictionary.addPhrase(new Phrase("day after tomorrow", expressionUtilities.parseExpressions("phrase(day_after_tomorrow)"), ""));
        InputParser inputParser = new InputParser(Arrays.asList((IDictionary) testDictionary));

        //test
        List<Solution> suggestions = inputParser.parse(lookupString);

        //assert
        Assert.assertEquals("day after tomorrow", suggestions.get(0).getDictionaryEntries().get(0).getValue());
        Assert.assertEquals("phrase(day_after_tomorrow)", expressionUtilities.toString(suggestions.get(0).getDictionaryEntries().get(0).getExpressions()));
    }

    @Test
    public void testParsePhrase2() throws Exception {
        //setup
        String lookupString = "someword day after tomorrow";
        TestDictionary testDictionary = new TestDictionary();
        testDictionary.addPhrase(new Phrase("day after tomorrow", expressionUtilities.parseExpressions("phrase(day_after_tomorrow)"), ""));
        InputParser inputParser = new InputParser(Arrays.asList((IDictionary) testDictionary));

        //test
        List<Solution> suggestions = inputParser.parse(lookupString);

        //assert
        List<IDictionary.IFoundWord> found = suggestions.get(0).getDictionaryEntries();
        Assert.assertEquals(1, suggestions.size());
        Assert.assertEquals(2, found.size());
        Assert.assertEquals("someword", found.get(0).getValue());
        Assert.assertEquals("unknown(someword)", expressionUtilities.toString(found.get(0).getExpressions()));
        Assert.assertEquals("day after tomorrow", found.get(1).getValue());
        Assert.assertEquals("phrase(day_after_tomorrow)", expressionUtilities.toString(found.get(1).getExpressions()));
    }

    @Test
    public void testParsePhrase3() throws Exception {
        //setup
        String lookupString = "day after tomorrow someword";
        TestDictionary testDictionary = new TestDictionary();
        testDictionary.addPhrase(new Phrase("day after tomorrow", expressionUtilities.parseExpressions("phrase(day_after_tomorrow)"), ""));
        InputParser inputParser = new InputParser(Arrays.asList((IDictionary) testDictionary));

        //test
        List<Solution> suggestions = inputParser.parse(lookupString);

        //assert
        Assert.assertEquals("day after tomorrow", suggestions.get(0).getDictionaryEntries().get(0).getValue());
        Assert.assertEquals("phrase(day_after_tomorrow)", expressionUtilities.toString(suggestions.get(0).getDictionaryEntries().get(0).getExpressions()));
    }

    @Test
    public void testParsePhrase4() throws Exception {
        //setup
        String lookupString = "someword day after tomorrow someword";
        TestDictionary testDictionary = new TestDictionary();
        testDictionary.addPhrase(new Phrase("day after tomorrow", expressionUtilities.parseExpressions("phrase(day_after_tomorrow)"), ""));
        InputParser inputParser = new InputParser(Arrays.asList((IDictionary) testDictionary));

        //test
        List<Solution> suggestions = inputParser.parse(lookupString);

        //assert
        Assert.assertEquals("day after tomorrow", suggestions.get(0).getDictionaryEntries().get(1).getValue());
        Assert.assertEquals("phrase(day_after_tomorrow)", expressionUtilities.toString(suggestions.get(0).getDictionaryEntries().get(1).getExpressions()));
    }

    @Test
    public void testParsePhrase5() throws Exception {
        //setup
        String lookupString = "day someword after tomorrow";
        TestDictionary testDictionary = new TestDictionary();
        testDictionary.addPhrase(new Phrase("day after tomorrow", expressionUtilities.parseExpressions("phrase(day_after_tomorrow)"), ""));
        InputParser inputParser = new InputParser(Arrays.asList((IDictionary) testDictionary));

        //test
        List<Solution> suggestions = inputParser.parse(lookupString);

        //assert
        Assert.assertNotSame("day after tomorrow", suggestions.get(0).getDictionaryEntries().get(0).getValue());
        Assert.assertEquals("day", suggestions.get(0).getDictionaryEntries().get(0).getValue());
        Assert.assertEquals("someword", suggestions.get(0).getDictionaryEntries().get(1).getValue());
        Assert.assertEquals("after", suggestions.get(0).getDictionaryEntries().get(2).getValue());
        Assert.assertEquals("tomorrow", suggestions.get(0).getDictionaryEntries().get(3).getValue());
    }

    @Test
    public void testParsePhrase6() throws Exception {
        //setup
        String lookupString = "day after tomorrow";
        TestDictionary testDictionary = new TestDictionary();
        testDictionary.addPhrase(new Phrase("day after", expressionUtilities.parseExpressions("phrase(day_after)"), ""));
        testDictionary.addPhrase(new Phrase("day after tomorrow", expressionUtilities.parseExpressions("phrase(day_after_tomorrow)"), ""));
        InputParser inputParser = new InputParser(Arrays.asList((IDictionary) testDictionary));

        //test
        List<Solution> suggestions = inputParser.parse(lookupString);

        //assert
        Assert.assertEquals("day after tomorrow", suggestions.get(0).getDictionaryEntries().get(0).getValue());
        Assert.assertEquals("phrase(day_after_tomorrow)", expressionUtilities.toString(suggestions.get(0).getDictionaryEntries().get(0).getExpressions()));
    }

    @Test
    public void testResultset() throws Exception {
        //setup
        String lookupString = "day after tomorrow";
        TestDictionary testDictionary = new TestDictionary();
        testDictionary.addWord(new Word("day", expressionUtilities.parseExpressions("unused(day)"), "", 0, false));
        testDictionary.addWord(new Word("day", expressionUtilities.parseExpressions("unused(day1)"), "", 0, false));
        testDictionary.addWord(new Word("after", expressionUtilities.parseExpressions("unused(after)"), "", 0, false));
        testDictionary.addWord(new Word("after", expressionUtilities.parseExpressions("unused(after1)"), "", 0, false));
        testDictionary.addWord(new Word("tomorrow", expressionUtilities.parseExpressions("unused(tomorrow)"), "", 0, false));
        testDictionary.addWord(new Word("tomorrow", expressionUtilities.parseExpressions("unused(tomorrow1)"), "", 0, false));
        InputParser inputParser = new InputParser(Arrays.asList((IDictionary) testDictionary));

        //test
        List<Solution> suggestions = inputParser.parse(lookupString);

        //assert
        Assert.assertEquals(1, suggestions.size());
        List<IDictionary.IFoundWord> foundWords = suggestions.get(0).getDictionaryEntries();
        Assert.assertEquals(3, foundWords.size());
        Assert.assertEquals("day", foundWords.get(0).getValue());
        Assert.assertEquals("after", foundWords.get(1).getValue());
        Assert.assertEquals("tomorrow", foundWords.get(2).getValue());
    }

    @Test
    public void testParse2Phrase() throws Exception {
        //setup
        String lookupString = "day after tomorrow another phrase";
        TestDictionary testDictionary = new TestDictionary();
        testDictionary.addPhrase(new Phrase("another phrase", expressionUtilities.parseExpressions("phrase(another_phrase)"), ""));
        testDictionary.addPhrase(new Phrase("day after tomorrow", expressionUtilities.parseExpressions("phrase(day_after_tomorrow)"), ""));
        InputParser inputParser = new InputParser(Arrays.asList((IDictionary) testDictionary));

        //test
        List<Solution> suggestions = inputParser.parse(lookupString);

        //assert
        Assert.assertEquals(1, suggestions.size());
        List<IDictionary.IFoundWord> foundWords = suggestions.get(0).getDictionaryEntries();
        Assert.assertEquals(2, foundWords.size());
        Assert.assertEquals("day after tomorrow", foundWords.get(0).getValue());
        Assert.assertEquals("phrase(day_after_tomorrow)", expressionUtilities.toString(foundWords.get(0).getExpressions()));
        Assert.assertEquals("another phrase", foundWords.get(1).getValue());
                Assert.assertEquals("phrase(another_phrase)", expressionUtilities.toString(foundWords.get(1).getExpressions()));
    }*/
}
