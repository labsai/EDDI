package ai.labs.parser;

import ai.labs.expressions.ExpressionFactory;
import ai.labs.expressions.utilities.ExpressionProvider;
import ai.labs.parser.internal.InputParser;
import ai.labs.parser.internal.matches.RawSolution;
import ai.labs.parser.model.Phrase;
import ai.labs.parser.model.Word;
import ai.labs.parser.rest.model.Solution;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.List;

/**
 * @author ginccc
 */
public class TemporaryDictionaryParserTest {

    private ExpressionProvider expressionProvider;
    private InputParser inputParser;

    @Before
    public void setup() {
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
                Collections.singletonList(temporaryTestDictionary));

        //assert
        List<Solution> actual = DictionaryUtilities.extractExpressions(rawSolutions, true, true);
        Assert.assertEquals(expected, actual);
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
                Collections.singletonList(temporaryTestDictionary));

        //assert
        List<Solution> actual = DictionaryUtilities.extractExpressions(rawSolutions, true, true);
        Assert.assertEquals(expected, actual);
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
                Collections.singletonList(temporaryTestDictionary));

        //assert
        List<Solution> actual = DictionaryUtilities.extractExpressions(rawSolutions, true, true);
        Assert.assertEquals(2, actual.size());
        Assert.assertEquals(expected.get(0), actual.get(0));
    }
}
