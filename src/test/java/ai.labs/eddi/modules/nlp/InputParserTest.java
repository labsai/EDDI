package ai.labs.eddi.modules.nlp;

import ai.labs.eddi.modules.nlp.expressions.Expression;
import ai.labs.eddi.modules.nlp.expressions.ExpressionFactory;
import ai.labs.eddi.modules.nlp.expressions.utilities.ExpressionProvider;
import ai.labs.eddi.modules.nlp.expressions.utilities.IExpressionProvider;
import ai.labs.eddi.modules.nlp.extensions.dictionaries.IDictionary;
import ai.labs.eddi.modules.nlp.internal.InputParser;
import ai.labs.eddi.modules.nlp.internal.matches.RawSolution;
import ai.labs.eddi.modules.nlp.model.Phrase;
import ai.labs.eddi.modules.nlp.model.Word;
import ai.labs.eddi.utils.StringUtilities;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

/**
 * @author ginccc
 */
@Slf4j
public class InputParserTest {
    private static IExpressionProvider expressionProvider;

    @BeforeEach
    public void setUp() {
        expressionProvider = new ExpressionProvider(new ExpressionFactory());
    }

    @Test
    public void testParseWord() throws Exception {
        //setup
        String lookupString = "test1 test2";
        TestDictionary testDictionary = new TestDictionary();
        testDictionary.addWord(new Word("test1", expressionProvider.parseExpressions("expression1"), "exp1", 0, false));
        testDictionary.addWord(new Word("test2", expressionProvider.parseExpressions("expression2"), "exp2", 0, false));
        InputParser inputParser = new InputParser(Collections.singletonList(testDictionary));

        //test
        List<RawSolution> suggestions = inputParser.parse(lookupString);

        //assert
        assertEquals(1, suggestions.size());
        assertEquals("test1", suggestions.get(0).getDictionaryEntries().get(0).getValue());
        assertEquals("test2", suggestions.get(0).getDictionaryEntries().get(1).getValue());
    }

    @Test
    public void testParsePhrase() throws Exception {
        //setup
        String lookupString = "day after tomorrow";
        TestDictionary testDictionary = new TestDictionary();
        testDictionary.addPhrase(new Phrase("day after tomorrow", expressionProvider.parseExpressions("phrase(day_after_tomorrow)"), ""));
        InputParser inputParser = new InputParser(Collections.singletonList(testDictionary), Collections.emptyList());

        //test
        List<RawSolution> suggestions = inputParser.parse(lookupString);

        //assert
        assertEquals("day after tomorrow", suggestions.get(0).getDictionaryEntries().get(0).getValue());
        assertEquals("phrase(day_after_tomorrow)", toString(suggestions.get(0).getDictionaryEntries().get(0).getExpressions()));
    }

    @Test
    public void testParsePhrase2() throws Exception {
        //setup
        String lookupString = "someword day after tomorrow";
        TestDictionary testDictionary = new TestDictionary();
        testDictionary.addPhrase(new Phrase("day after tomorrow", expressionProvider.parseExpressions("phrase(day_after_tomorrow)"), ""));
        InputParser inputParser = new InputParser(Collections.singletonList(testDictionary));

        //test
        List<RawSolution> suggestions = inputParser.parse(lookupString);

        //assert
        List<IDictionary.IFoundWord> found = suggestions.get(0).getDictionaryEntries();
        assertEquals(1, suggestions.size());
        assertEquals(2, found.size());
        assertEquals("someword", found.get(0).getValue());
        assertEquals("unknown(someword)", toString(found.get(0).getExpressions()));
        assertEquals("day after tomorrow", found.get(1).getValue());
        assertEquals("phrase(day_after_tomorrow)", toString(found.get(1).getExpressions()));
    }

    @Test
    public void testParsePhrase3() throws Exception {
        //setup
        String lookupString = "day after tomorrow someword";
        TestDictionary testDictionary = new TestDictionary();
        testDictionary.addPhrase(new Phrase("day after tomorrow", expressionProvider.parseExpressions("phrase(day_after_tomorrow)"), ""));
        InputParser inputParser = new InputParser(Collections.singletonList(testDictionary));

        //test
        List<RawSolution> suggestions = inputParser.parse(lookupString);

        //assert
        assertEquals("day after tomorrow", suggestions.get(0).getDictionaryEntries().get(0).getValue());
        assertEquals("phrase(day_after_tomorrow)", toString(suggestions.get(0).getDictionaryEntries().get(0).getExpressions()));
    }

    @Test
    public void testParsePhrase4() throws Exception {
        //setup
        String lookupString = "someword day after tomorrow someword";
        TestDictionary testDictionary = new TestDictionary();
        testDictionary.addPhrase(new Phrase("day after tomorrow", expressionProvider.parseExpressions("phrase(day_after_tomorrow)"), ""));
        InputParser inputParser = new InputParser(Collections.singletonList(testDictionary));

        //test
        List<RawSolution> suggestions = inputParser.parse(lookupString);

        //assert
        assertEquals("day after tomorrow", suggestions.get(0).getDictionaryEntries().get(1).getValue());
        assertEquals("phrase(day_after_tomorrow)", toString(suggestions.get(0).getDictionaryEntries().get(1).getExpressions()));
    }

    @Test
    public void testParsePhrase5() throws Exception {
        //setup
        String lookupString = "day someword after tomorrow";
        TestDictionary testDictionary = new TestDictionary();
        testDictionary.addPhrase(new Phrase("day after tomorrow", expressionProvider.parseExpressions("phrase(day_after_tomorrow)"), ""));
        InputParser inputParser = new InputParser(Collections.singletonList(testDictionary));

        //test
        List<RawSolution> suggestions = inputParser.parse(lookupString);

        //assert
        assertNotSame("day after tomorrow", suggestions.get(0).getDictionaryEntries().get(0).getValue());
        assertEquals("day", suggestions.get(0).getDictionaryEntries().get(0).getValue());
        assertEquals("someword", suggestions.get(0).getDictionaryEntries().get(1).getValue());
        assertEquals("after", suggestions.get(0).getDictionaryEntries().get(2).getValue());
        assertEquals("tomorrow", suggestions.get(0).getDictionaryEntries().get(3).getValue());
    }

    @Test
    public void testParsePhrase6() throws Exception {
        //setup
        String lookupString = "day after tomorrow";
        TestDictionary testDictionary = new TestDictionary();
        testDictionary.addPhrase(new Phrase("day after", expressionProvider.parseExpressions("phrase(day_after)"), ""));
        testDictionary.addPhrase(new Phrase("day after tomorrow", expressionProvider.parseExpressions("phrase(day_after_tomorrow)"), ""));
        InputParser inputParser = new InputParser(Collections.singletonList(testDictionary));

        //test
        List<RawSolution> suggestions = inputParser.parse(lookupString);

        //assert
        assertEquals("day after tomorrow", suggestions.get(0).getDictionaryEntries().get(0).getValue());
        assertEquals("phrase(day_after_tomorrow)", toString(suggestions.get(0).getDictionaryEntries().get(0).getExpressions()));
    }

    @Test
    public void testResultSet() throws Exception {
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
        assertEquals(7, suggestions.size());
        List<IDictionary.IFoundWord> foundWords = suggestions.get(0).getDictionaryEntries();
        assertEquals(3, foundWords.size());
        assertEquals("day", foundWords.get(0).getValue());
        assertEquals("after", foundWords.get(1).getValue());
        assertEquals("tomorrow", foundWords.get(2).getValue());
    }

    @Test
    public void testParse2Phrase() throws Exception {
        //setup
        String lookupString = "day after tomorrow another phrase";
        TestDictionary testDictionary = new TestDictionary();
        testDictionary.addPhrase(new Phrase("another phrase", expressionProvider.parseExpressions("phrase(another_phrase)"), ""));
        testDictionary.addPhrase(new Phrase("day after tomorrow", expressionProvider.parseExpressions("phrase(day_after_tomorrow)"), ""));
        InputParser inputParser = new InputParser(Collections.singletonList(testDictionary), Collections.emptyList());

        //test
        List<RawSolution> suggestions = inputParser.parse(lookupString);

        //assert
        assertEquals(1, suggestions.size());
        List<IDictionary.IFoundWord> foundWords = suggestions.get(0).getDictionaryEntries();
        assertEquals(2, foundWords.size());
        assertEquals("day after tomorrow", foundWords.get(0).getValue());
        assertEquals("phrase(day_after_tomorrow)", toString(foundWords.get(0).getExpressions()));
        assertEquals("another phrase", foundWords.get(1).getValue());
        assertEquals("phrase(another_phrase)", toString(foundWords.get(1).getExpressions()));
    }

    private static String toString(List<Expression> expressions) {
        return StringUtilities.joinStrings(", ", expressions);
    }
}
