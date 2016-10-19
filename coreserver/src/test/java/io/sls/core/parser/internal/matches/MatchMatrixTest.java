package io.sls.core.parser.internal.matches;

/**
 * User: jarisch
 * Date: 06.11.12
 * Time: 15:11
 */
public class MatchMatrixTest {
    //TODO make DI work for tests
    /*@Test
    public void testCreatePossibleSolutions() throws Exception {
        //setup
        MatchMatrix matchMatrix = new MatchMatrix();
        MatchingResult matchingResult = new MatchingResult();
        List<Expression> expressions = Arrays.asList(new Expression("unused", new Expression("hello")));
        IDictionary.IWord word = new Word("helo", expressions, null, 0, false);
        matchingResult.addResult(new FoundWord(word, false, 1.0));
        matchMatrix.addMatchingResult("helo", matchingResult);

        //test
        Iterator<Suggestion> possibleSolutions = matchMatrix.iterator();

        //assert
        Assert.assertTrue(possibleSolutions.hasNext());
        Assert.assertArrayEquals(new IDictionary.IFoundWord[]{new FoundWord(word, false, 1.0)}, possibleSolutions.next().build().toArray());
        Assert.assertFalse(possibleSolutions.hasNext());
    }*/
}
