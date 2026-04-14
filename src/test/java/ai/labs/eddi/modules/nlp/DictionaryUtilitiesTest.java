package ai.labs.eddi.modules.nlp;

import ai.labs.eddi.modules.nlp.expressions.Expression;
import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.nlp.extensions.dictionaries.IDictionary;
import ai.labs.eddi.modules.nlp.internal.matches.RawSolution;
import ai.labs.eddi.modules.nlp.model.FoundPhrase;
import ai.labs.eddi.modules.nlp.model.FoundWord;
import ai.labs.eddi.modules.nlp.model.Phrase;
import ai.labs.eddi.modules.nlp.model.Word;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */
public class DictionaryUtilitiesTest {
    private List<RawSolution> rawSolutions;

    @BeforeEach
    public void setup() {
        Expressions expressions = createExpressions("someExp", "someValue1");
        expressions.addAll(createExpressions("unused", "someValue2"));
        expressions.addAll(createExpressions("unknown", "someValue3"));
        rawSolutions = createRawSolutions(expressions);
    }

    @Test
    public void extractExpressions_NotFiltered() {
        // setup
        final String expected = "someExp(someValue1), unused(someValue2), unknown(someValue3)";

        // test
        List<Solution> actual = DictionaryUtilities.extractExpressions(rawSolutions, true, true);

        // assert
        Assertions.assertEquals(expected, actual.get(0).getExpressions().toString());
    }

    @Test
    public void extractExpressions_FilteredUnused() {
        // setup
        final String expected = "someExp(someValue1), unknown(someValue3)";

        // test
        List<Solution> actual = DictionaryUtilities.extractExpressions(rawSolutions, false, true);

        // assert
        Assertions.assertEquals(expected, actual.get(0).getExpressions().toString());
    }

    @Test
    public void extractExpressions_FilteredUnknown() {
        // setup
        final String expected = "someExp(someValue1), unused(someValue2)";

        // test
        List<Solution> actual = DictionaryUtilities.extractExpressions(rawSolutions, true, false);

        // assert
        Assertions.assertEquals(expected, actual.get(0).getExpressions().toString());
    }

    @Test
    public void extractExpressions_buildsMatchDetails() {
        // test
        List<Solution> actual = DictionaryUtilities.extractExpressions(rawSolutions, true, true);

        // assert — matchDetails should contain non-unused/unknown expressions
        List<String> matchDetails = actual.get(0).getMatchDetails();
        Assertions.assertEquals(1, matchDetails.size(), "Only someExp should be in matchDetails (unused/unknown filtered)");
        Assertions.assertTrue(matchDetails.get(0).contains("\"test\""), "Should contain the matched input word");
        Assertions.assertTrue(matchDetails.get(0).contains("someExp"), "Should contain the expression name");
    }

    @Test
    public void extractExpressions_phraseMatchDetails() {
        // setup — create a phrase match
        Expressions phraseExpressions = createExpressions("greeting", "hello");
        LinkedList<RawSolution> phraseSolutions = new LinkedList<>();
        RawSolution rawSolution = new RawSolution(RawSolution.Match.FULLY);
        List<IDictionary.IFoundWord> entries = new LinkedList<>();
        Phrase phrase = new Phrase("good morning", phraseExpressions, "");
        entries.add(new FoundPhrase(phrase, false, 1.0));
        rawSolution.setDictionaryEntries(entries);
        phraseSolutions.add(rawSolution);

        // test
        List<Solution> actual = DictionaryUtilities.extractExpressions(phraseSolutions, true, true);

        // assert — matchDetails should include [phrase] tag
        List<String> matchDetails = actual.get(0).getMatchDetails();
        Assertions.assertFalse(matchDetails.isEmpty());
        Assertions.assertTrue(matchDetails.get(0).contains("[phrase]"), "Phrase matches should be tagged with [phrase]");
        Assertions.assertTrue(matchDetails.get(0).contains("good morning"), "Should contain the full phrase text");
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