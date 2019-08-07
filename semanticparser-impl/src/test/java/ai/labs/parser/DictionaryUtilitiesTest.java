package ai.labs.parser;

import ai.labs.expressions.Expression;
import ai.labs.expressions.Expressions;
import ai.labs.parser.extensions.dictionaries.IDictionary;
import ai.labs.parser.internal.matches.RawSolution;
import ai.labs.parser.model.FoundWord;
import ai.labs.parser.model.Word;
import ai.labs.parser.rest.model.Solution;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */
public class DictionaryUtilitiesTest {
    private List<RawSolution> rawSolutions;

    @Before
    public void setup() {
        Expressions expressions = createExpressions("someExp", "someValue1");
        expressions.addAll(createExpressions("unused", "someValue2"));
        expressions.addAll(createExpressions("unknown", "someValue3"));
        rawSolutions = createRawSolutions(expressions);
    }

    @Test
    public void extractExpressions_NotFiltered() {
        //setup
        final String expected = "someExp(someValue1), unused(someValue2), unknown(someValue3)";

        //test
        List<Solution> actual = DictionaryUtilities.extractExpressions(rawSolutions, true, true);

        //assert
        Assert.assertEquals(expected, actual.get(0).getExpressions().toString());
    }

    @Test
    public void extractExpressions_FilteredUnused() {
        //setup
        final String expected = "someExp(someValue1), unknown(someValue3)";

        //test
        List<Solution> actual = DictionaryUtilities.extractExpressions(rawSolutions, false, true);

        //assert
        Assert.assertEquals(expected, actual.get(0).getExpressions().toString());
    }

    @Test
    public void extractExpressions_FilteredUnknown() {
        //setup
        final String expected = "someExp(someValue1), unused(someValue2)";

        //test
        List<Solution> actual = DictionaryUtilities.extractExpressions(rawSolutions, true, false);

        //assert
        Assert.assertEquals(expected, actual.get(0).getExpressions().toString());
    }

    private LinkedList<RawSolution> createRawSolutions(Expressions expressions) {
        LinkedList<RawSolution> rawSolutions = new LinkedList<>();
        RawSolution rawSolution = new RawSolution(RawSolution.Match.FULLY);
        List<IDictionary.IFoundWord> dictionaryEntries = new LinkedList<>();
        dictionaryEntries.add(new FoundWord(new Word("test", expressions, ""), false, 0));
        rawSolution.setDictionaryEntries(dictionaryEntries);
        rawSolutions.add(rawSolution);
        return rawSolutions;
    }

    private Expressions createExpressions(String expressionName, String expressionValue) {
        Expressions ret = new Expressions();
        ret.add(new Expression(expressionName, new Expression(expressionValue)));
        return ret;
    }
}