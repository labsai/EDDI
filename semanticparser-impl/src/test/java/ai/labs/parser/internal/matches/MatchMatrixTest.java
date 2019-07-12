package ai.labs.parser.internal.matches;

import ai.labs.expressions.Expression;
import ai.labs.parser.extensions.dictionaries.IDictionary;
import ai.labs.parser.model.FoundWord;
import ai.labs.parser.model.Word;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * @author ginccc
 */
public class MatchMatrixTest {
    @Test
    void testCreatePossibleSolutions() {
        //setup
        MatchMatrix matchMatrix = new MatchMatrix();
        MatchingResult matchingResult = new MatchingResult();
        List<Expression> expressions = Collections.singletonList(new Expression("unused", new Expression("hello")));
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
