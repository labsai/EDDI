package ai.labs.eddi.modules.nlp.matches;

import ai.labs.eddi.modules.nlp.expressions.Expression;
import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.nlp.extensions.dictionaries.IDictionary;
import ai.labs.eddi.modules.nlp.internal.matches.MatchMatrix;
import ai.labs.eddi.modules.nlp.internal.matches.MatchingResult;
import ai.labs.eddi.modules.nlp.internal.matches.Suggestion;
import ai.labs.eddi.modules.nlp.model.FoundWord;
import ai.labs.eddi.modules.nlp.model.Word;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Iterator;

/**
 * @author ginccc
 */
public class MatchMatrixTest {
    @Test
    public void testCreatePossibleSolutions() {
        //setup
        MatchMatrix matchMatrix = new MatchMatrix();
        MatchingResult matchingResult = new MatchingResult();
        Expressions expressions = new Expressions(new Expression("unused", new Expression("hello")));
        IDictionary.IWord word = new Word("helo", expressions, null, 0, false);
        matchingResult.addResult(new FoundWord(word, false, 1.0));
        matchMatrix.addMatchingResult(0, "helo", matchingResult);

        //test
        Iterator<Suggestion> possibleSolutions = matchMatrix.iterator();

        //assert
        Assertions.assertTrue(possibleSolutions.hasNext());
        Assertions.assertArrayEquals(new IDictionary.IFoundWord[]{new FoundWord(word, false, 1.0)}, possibleSolutions.next().build().toArray());
        Assertions.assertFalse(possibleSolutions.hasNext());
    }
}
