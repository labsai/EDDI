package ai.labs.parser;

import ai.labs.expressions.Expression;
import ai.labs.expressions.ExpressionFactory;
import ai.labs.expressions.utilities.ExpressionProvider;
import ai.labs.expressions.utilities.IExpressionProvider;
import ai.labs.parser.extensions.dictionaries.IDictionary;
import ai.labs.parser.internal.InputParser;
import ai.labs.parser.internal.matches.RawSolution;
import ai.labs.parser.model.Phrase;
import ai.labs.parser.model.Word;
import ai.labs.utilities.StringUtilities;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

/**
 * @author ginccc
 */
@Slf4j
public class InputParserTest {
    private IExpressionProvider expressionProvider;

    @BeforeEach
    public void setup() {
        expressionProvider = new ExpressionProvider(new ExpressionFactory());
    }

    @Test
    void testParseWord() throws Exception {
        //setup
        String lookupString = "test1 test2";
        TestDictionary testDictionary = new TestDictionary();
        testDictionary.addWord(new Word("test1", expressionProvider.parseExpressions("expression1"), "exp1", 0, false));
        testDictionary.addWord(new Word("test2", expressionProvider.parseExpressions("expression2"), "exp2", 0, false));
        InputParser inputParser = new InputParser(Collections.singletonList(testDictionary));

        //test
        List<RawSolution> suggestions = inputParser.parse(lookupString);

        //assert
        Assertions.assertEquals(1, suggestions.size());
        Assertions.assertEquals("test1", suggestions.get(0).getDictionaryEntries().get(0).getValue());
        Assertions.assertEquals("test2", suggestions.get(0).getDictionaryEntries().get(1).getValue());
    }

    @Test
    void testParsePhrase() throws Exception {
        //setup
        String lookupString = "day after tomorrow";
        TestDictionary testDictionary = new TestDictionary();
        testDictionary.addPhrase(new Phrase("day after tomorrow", expressionProvider.parseExpressions("phrase(day_after_tomorrow)"), ""));
        InputParser inputParser = new InputParser(Collections.singletonList(testDictionary), Collections.emptyList());

        //test
        List<RawSolution> suggestions = inputParser.parse(lookupString);

        //assert
        Assertions.assertEquals("day after tomorrow", suggestions.get(0).getDictionaryEntries().get(0).getValue());
        Assertions.assertEquals("phrase(day_after_tomorrow)", toString(suggestions.get(0).getDictionaryEntries().get(0).getExpressions()));
    }

    @Test
    void testParsePhrase2() throws Exception {
        //setup
        String lookupString = "someword day after tomorrow";
        TestDictionary testDictionary = new TestDictionary();
        testDictionary.addPhrase(new Phrase("day after tomorrow", expressionProvider.parseExpressions("phrase(day_after_tomorrow)"), ""));
        InputParser inputParser = new InputParser(Collections.singletonList(testDictionary));

        //test
        List<RawSolution> suggestions = inputParser.parse(lookupString);

        //assert
        List<IDictionary.IFoundWord> found = suggestions.get(0).getDictionaryEntries();
        Assertions.assertEquals(1, suggestions.size());
        Assertions.assertEquals(2, found.size());
        Assertions.assertEquals("someword", found.get(0).getValue());
        Assertions.assertEquals("unknown(someword)", toString(found.get(0).getExpressions()));
        Assertions.assertEquals("day after tomorrow", found.get(1).getValue());
        Assertions.assertEquals("phrase(day_after_tomorrow)", toString(found.get(1).getExpressions()));
    }

    @Test
    void testParsePhrase3() throws Exception {
        //setup
        String lookupString = "day after tomorrow someword";
        TestDictionary testDictionary = new TestDictionary();
        testDictionary.addPhrase(new Phrase("day after tomorrow", expressionProvider.parseExpressions("phrase(day_after_tomorrow)"), ""));
        InputParser inputParser = new InputParser(Collections.singletonList(testDictionary));

        //test
        List<RawSolution> suggestions = inputParser.parse(lookupString);

        //assert
        Assertions.assertEquals("day after tomorrow", suggestions.get(0).getDictionaryEntries().get(0).getValue());
        Assertions.assertEquals("phrase(day_after_tomorrow)", toString(suggestions.get(0).getDictionaryEntries().get(0).getExpressions()));
    }

    @Test
    void testParsePhrase4() throws Exception {
        //setup
        String lookupString = "someword day after tomorrow someword";
        TestDictionary testDictionary = new TestDictionary();
        testDictionary.addPhrase(new Phrase("day after tomorrow", expressionProvider.parseExpressions("phrase(day_after_tomorrow)"), ""));
        InputParser inputParser = new InputParser(Collections.singletonList(testDictionary));

        //test
        List<RawSolution> suggestions = inputParser.parse(lookupString);

        //assert
        Assertions.assertEquals("day after tomorrow", suggestions.get(0).getDictionaryEntries().get(1).getValue());
        Assertions.assertEquals("phrase(day_after_tomorrow)", toString(suggestions.get(0).getDictionaryEntries().get(1).getExpressions()));
    }

    @Test
    void testParsePhrase5() throws Exception {
        //setup
        String lookupString = "day someword after tomorrow";
        TestDictionary testDictionary = new TestDictionary();
        testDictionary.addPhrase(new Phrase("day after tomorrow", expressionProvider.parseExpressions("phrase(day_after_tomorrow)"), ""));
        InputParser inputParser = new InputParser(Collections.singletonList(testDictionary));

        //test
        List<RawSolution> suggestions = inputParser.parse(lookupString);

        //assert
        Assertions.assertNotSame("day after tomorrow", suggestions.get(0).getDictionaryEntries().get(0).getValue());
        Assertions.assertEquals("day", suggestions.get(0).getDictionaryEntries().get(0).getValue());
        Assertions.assertEquals("someword", suggestions.get(0).getDictionaryEntries().get(1).getValue());
        Assertions.assertEquals("after", suggestions.get(0).getDictionaryEntries().get(2).getValue());
        Assertions.assertEquals("tomorrow", suggestions.get(0).getDictionaryEntries().get(3).getValue());
    }

    @Test
    void testParsePhrase6() throws Exception {
        //setup
        String lookupString = "day after tomorrow";
        TestDictionary testDictionary = new TestDictionary();
        testDictionary.addPhrase(new Phrase("day after", expressionProvider.parseExpressions("phrase(day_after)"), ""));
        testDictionary.addPhrase(new Phrase("day after tomorrow", expressionProvider.parseExpressions("phrase(day_after_tomorrow)"), ""));
        InputParser inputParser = new InputParser(Collections.singletonList(testDictionary));

        //test
        List<RawSolution> suggestions = inputParser.parse(lookupString);

        //assert
        Assertions.assertEquals("day after tomorrow", suggestions.get(0).getDictionaryEntries().get(0).getValue());
        Assertions.assertEquals("phrase(day_after_tomorrow)", toString(suggestions.get(0).getDictionaryEntries().get(0).getExpressions()));
    }

    @Test
    void testResultset() throws Exception {
        //setup
        String lookupString = "day after tomorrow";
        TestDictionary testDictionary = new TestDictionary();
        testDictionary.addWord(new Word("day", expressionProvider.parseExpressions("unused(day)"), "", 0, false));
        testDictionary.addWord(new Word("day", expressionProvider.parseExpressions("unused(day1)"), "", 0, false));
        testDictionary.addWord(new Word("after", expressionProvider.parseExpressions("unused(after)"), "", 0, false));
        testDictionary.addWord(new Word("after", expressionProvider.parseExpressions("unused(after1)"), "", 0, false));
        testDictionary.addWord(new Word("tomorrow", expressionProvider.parseExpressions("unused(tomorrow)"), "", 0, false));
        testDictionary.addWord(new Word("tomorrow", expressionProvider.parseExpressions("unused(tomorrow1)"), "", 0, false));
        InputParser inputParser = new InputParser(Collections.singletonList(testDictionary));

        //test
        List<RawSolution> suggestions = inputParser.parse(lookupString);

        //assert
        Assertions.assertEquals(7, suggestions.size());
        List<IDictionary.IFoundWord> foundWords = suggestions.get(0).getDictionaryEntries();
        Assertions.assertEquals(3, foundWords.size());
        Assertions.assertEquals("day", foundWords.get(0).getValue());
        Assertions.assertEquals("after", foundWords.get(1).getValue());
        Assertions.assertEquals("tomorrow", foundWords.get(2).getValue());
    }

    @Test
    void testParse2Phrase() throws Exception {
        //setup
        String lookupString = "day after tomorrow another phrase";
        TestDictionary testDictionary = new TestDictionary();
        testDictionary.addPhrase(new Phrase("another phrase", expressionProvider.parseExpressions("phrase(another_phrase)"), ""));
        testDictionary.addPhrase(new Phrase("day after tomorrow", expressionProvider.parseExpressions("phrase(day_after_tomorrow)"), ""));
        InputParser inputParser = new InputParser(Collections.singletonList(testDictionary), Collections.emptyList());

        //test
        List<RawSolution> suggestions = inputParser.parse(lookupString);

        //assert
        Assertions.assertEquals(1, suggestions.size());
        List<IDictionary.IFoundWord> foundWords = suggestions.get(0).getDictionaryEntries();
        Assertions.assertEquals(2, foundWords.size());
        Assertions.assertEquals("day after tomorrow", foundWords.get(0).getValue());
        Assertions.assertEquals("phrase(day_after_tomorrow)", toString(foundWords.get(0).getExpressions()));
        Assertions.assertEquals("another phrase", foundWords.get(1).getValue());
        Assertions.assertEquals("phrase(another_phrase)", toString(foundWords.get(1).getExpressions()));
    }

    private static String toString(List<Expression> expressions) {
        return StringUtilities.joinStrings(", ", expressions);
    }
}
