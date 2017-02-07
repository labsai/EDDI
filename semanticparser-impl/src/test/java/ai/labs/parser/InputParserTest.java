package ai.labs.parser;

import ai.labs.expressions.ExpressionFactory;
import ai.labs.expressions.utilities.ExpressionProvider;
import ai.labs.expressions.utilities.IExpressionProvider;
import ai.labs.parser.internal.InputParser;
import ai.labs.parser.internal.matches.RawSolution;
import ai.labs.parser.model.IDictionary;
import ai.labs.parser.model.Phrase;
import ai.labs.parser.model.Word;
import lombok.extern.slf4j.Slf4j;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.List;

/**
 * @author ginccc
 */
@Slf4j
public class InputParserTest {
    private IExpressionProvider expressionUtilities;

    @Before
    public void setup() {
        expressionUtilities = new ExpressionProvider(new ExpressionFactory());
    }

    @Test
    public void testParseWord() throws Exception {
        //setup
        String lookupString = "test1 test2";
        TestDictionary testDictionary = new TestDictionary();
        testDictionary.addWord(new Word("test1", expressionUtilities.parseExpressions("expression1"), "exp1", 0, false));
        testDictionary.addWord(new Word("test2", expressionUtilities.parseExpressions("expression2"), "exp2", 0, false));
        InputParser inputParser = new InputParser(Collections.singletonList(testDictionary));

        //test
        List<RawSolution> suggestions = inputParser.parse(lookupString);

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
        InputParser inputParser = new InputParser(Collections.singletonList(testDictionary), Collections.emptyList());

        //test
        List<RawSolution> suggestions = inputParser.parse(lookupString);

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
        InputParser inputParser = new InputParser(Collections.singletonList(testDictionary));

        //test
        List<RawSolution> suggestions = inputParser.parse(lookupString);

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
        InputParser inputParser = new InputParser(Collections.singletonList(testDictionary));

        //test
        List<RawSolution> suggestions = inputParser.parse(lookupString);

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
        InputParser inputParser = new InputParser(Collections.singletonList(testDictionary));

        //test
        List<RawSolution> suggestions = inputParser.parse(lookupString);

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
        InputParser inputParser = new InputParser(Collections.singletonList(testDictionary));

        //test
        List<RawSolution> suggestions = inputParser.parse(lookupString);

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
        InputParser inputParser = new InputParser(Collections.singletonList(testDictionary));

        //test
        List<RawSolution> suggestions = inputParser.parse(lookupString);

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
        InputParser inputParser = new InputParser(Collections.singletonList(testDictionary));

        //test
        List<RawSolution> suggestions = inputParser.parse(lookupString);

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
        InputParser inputParser = new InputParser(Collections.singletonList(testDictionary), Collections.emptyList());

        //test
        List<RawSolution> suggestions = inputParser.parse(lookupString);

        //assert
        Assert.assertEquals(1, suggestions.size());
        List<IDictionary.IFoundWord> foundWords = suggestions.get(0).getDictionaryEntries();
        Assert.assertEquals(2, foundWords.size());
        Assert.assertEquals("day after tomorrow", foundWords.get(0).getValue());
        Assert.assertEquals("phrase(day_after_tomorrow)", expressionUtilities.toString(foundWords.get(0).getExpressions()));
        Assert.assertEquals("another phrase", foundWords.get(1).getValue());
                Assert.assertEquals("phrase(another_phrase)", expressionUtilities.toString(foundWords.get(1).getExpressions()));
    }
}
