package ai.labs.eddi.modules.nlp;


import ai.labs.eddi.modules.nlp.expressions.ExpressionFactory;
import ai.labs.eddi.modules.nlp.expressions.utilities.ExpressionProvider;
import ai.labs.eddi.modules.nlp.internal.InputParser;
import ai.labs.eddi.modules.nlp.internal.matches.RawSolution;
import ai.labs.eddi.modules.nlp.model.Phrase;
import ai.labs.eddi.modules.nlp.model.Word;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * @author ginccc
 */
public class TemporaryDictionaryParserTest {

    public static final String DEFAULT_USER_LANGUAGE = "en";
    private static ExpressionProvider expressionProvider;
    private static InputParser inputParser;

    @BeforeEach
    public void setUp() {
        expressionProvider = new ExpressionProvider(new ExpressionFactory());

        //setup
        TestDictionary testDictionary = new TestDictionary(true);
        testDictionary.addWord(new Word("test1", expressionProvider.parseExpressions("expression1"), "exp1", 0, false));
        testDictionary.addWord(new Word("test2", expressionProvider.parseExpressions("expression2"), "exp2", 0, false));
        inputParser = new InputParser(Collections.singletonList(testDictionary));
    }

    @Test
    public void testRecognitionOfTemporaryDictionaryWord() throws InterruptedException {
        //setup
        TestDictionary temporaryTestDictionary = new TestDictionary();
        temporaryTestDictionary.addWord(new Word("tmp1", expressionProvider.parseExpressions("tmpExp1"), "tmpExp1", 0, false));
        temporaryTestDictionary.addWord(new Word("tmp2", expressionProvider.parseExpressions("tmpExp2"), "tmpExp2", 0, false));
        List<Solution> expected = Collections.singletonList(new Solution(expressionProvider.parseExpressions("expression1, expression2, tmpExp1, tmpExp2")));

        //test
        List<RawSolution> rawSolutions = inputParser.parse("test1 test2 tmp1 tmp2",
                DEFAULT_USER_LANGUAGE, Collections.singletonList(temporaryTestDictionary));

        //assert
        List<Solution> actual = DictionaryUtilities.extractExpressions(rawSolutions, true, true);
        assertEquals(expected, actual);
    }

    @Test
    public void testRecognitionOfTemporaryDictionaryPhrase() throws InterruptedException {
        //setup
        TestDictionary temporaryTestDictionary = new TestDictionary();
        temporaryTestDictionary.addPhrase(new Phrase("test tmp phrase", expressionProvider.parseExpressions
                ("tmpExp1"), "tmpExp1"));
        List<Solution> expected = Collections.singletonList(new Solution(expressionProvider.parseExpressions("expression1, expression2, tmpExp1")));

        //test
        List<RawSolution> rawSolutions = inputParser.parse("test1 test2 test tmp phrase",
                DEFAULT_USER_LANGUAGE, Collections.singletonList(temporaryTestDictionary));

        //assert
        List<Solution> actual = DictionaryUtilities.extractExpressions(rawSolutions, true, true);
        assertEquals(expected, actual);
    }

    @Test
    public void testPreferTmpDictionaryWordOverPredefinedDictionaryWords() throws InterruptedException {
        //setup
        TestDictionary temporaryTestDictionary = new TestDictionary();
        temporaryTestDictionary.addWord(new Word("test1", expressionProvider.parseExpressions("tmpExp1"), "tmpExp1",
                0, false));
        List<Solution> expected = Collections.singletonList(new Solution(expressionProvider.parseExpressions("tmpExp1")));

        //test
        List<RawSolution> rawSolutions = inputParser.parse("test1",
                DEFAULT_USER_LANGUAGE, Collections.singletonList(temporaryTestDictionary));

        //assert
        List<Solution> actual = DictionaryUtilities.extractExpressions(rawSolutions, true, true);
        assertEquals(2, actual.size());
        assertEquals(expected.get(0), actual.get(0));
    }
}
