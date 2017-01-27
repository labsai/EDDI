package ai.labs.parser.internal.phrases;

import ai.labs.expressions.ExpressionFactory;
import ai.labs.expressions.utilities.ExpressionProvider;
import ai.labs.expressions.utilities.IExpressionProvider;
import ai.labs.parser.TestDictionary;
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
public class PhraseExpressionTest {

    private IExpressionProvider expressionUtilities;

    @Before
    public void setup() {
        expressionUtilities = new ExpressionProvider(new ExpressionFactory());
    }

    @Test
    public void testParsePhraseWithExpression() throws Exception {
        //setup
        String lookupString = "day after tomorrow";
        TestDictionary testDictionary = new TestDictionary();
        testDictionary.addWord(new Word("after", expressionUtilities.parseExpressions("unused(after)"), "", 0, false));
        testDictionary.addPhrase(new Phrase("day [exp:unused(after)] tomorrow", expressionUtilities.parseExpressions("phrase(day_after_tomorrow)"), ""));
        InputParser inputParser = new InputParser(Collections.singletonList(testDictionary), Collections.emptyList());

        //test
        List<RawSolution> rawSolutions = inputParser.parse(lookupString);

        //assert
        Assert.assertEquals("day after tomorrow", convert(rawSolutions.get(0).getDictionaryEntries()));
    }

    @Test
    public void testParsePhraseWithExpression1() throws Exception {
        //setup
        String lookupString = "day after tomorrow";
        TestDictionary testDictionary = new TestDictionary();
        testDictionary.addWord(new Word("after", expressionUtilities.parseExpressions("unused(af), unused(ter)"), "", 0, false));
        testDictionary.addPhrase(new Phrase("day [exp:unused(af),unused(ter)] tomorrow", expressionUtilities.parseExpressions("phrase(day_after_tomorrow)"), ""));
        InputParser inputParser = new InputParser(Collections.singletonList(testDictionary), Collections.emptyList());

        //test
        List<RawSolution> rawSolutions = inputParser.parse(lookupString);

        //assert
        Assert.assertEquals("day after tomorrow", convert(rawSolutions.get(0).getDictionaryEntries()));
    }

    private String convert(List<IDictionary.IFoundWord> dictionaryEntries) {
        StringBuilder ret = new StringBuilder();

        for (IDictionary.IFoundWord dictionaryEntry : dictionaryEntries) {
            ret.append(dictionaryEntry.getFoundWord().getValue()).append(" ");
        }

        if (!dictionaryEntries.isEmpty()) {
            ret.deleteCharAt(ret.length() - 1);
        }

        return ret.toString();
    }
}
