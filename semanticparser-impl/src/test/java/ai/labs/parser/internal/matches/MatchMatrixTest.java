package ai.labs.parser.internal.matches;

import ai.labs.expressions.Expression;
import ai.labs.expressions.Expressions;
import ai.labs.parser.extensions.dictionaries.IDictionary;
import ai.labs.parser.model.FoundWord;
import ai.labs.parser.model.Word;
import org.junit.Assert;
import org.junit.Test;

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
        Assert.assertTrue(possibleSolutions.hasNext());
        Assert.assertArrayEquals(new IDictionary.IFoundWord[]{new FoundWord(word, false, 1.0)}, possibleSolutions.next().build().toArray());
        Assert.assertFalse(possibleSolutions.hasNext());
    }
}
