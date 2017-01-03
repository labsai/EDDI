package ai.labs.parser.phrases;

/**
 * @author ginccc
 */
public class PhraseExpressionTest {
    /* @Test
    public void testParsePhraseWithExpression() throws Exception {
        //setup
        String lookupString = "day after tomorrow";
        TestDictionary testDictionary = new TestDictionary();
        testDictionary.addWord(new Word("after", "after", ExpressionUtilities.parseExpressions("unused(after)"), "", 0, false));
        testDictionary.addPhrase(new Phrase("day [exp:unused(after)] tomorrow", ExpressionUtilities.parseExpressions("phrase(day_after_tomorrow)"), ""));
        InputParser inputParser = new InputParser(Arrays.asList((IDictionary) testDictionary));

        //test
        List<Suggestion> solutions = inputParser.parse(lookupString);

        //assert
        Assert.assertEquals("day after tomorrow", solutions.get(0).build().get(0).getFoundValue());
    }

    @Test
    public void testParsePhraseWithExpression1() throws Exception {
        //setup
        String lookupString = "day after tomorrow";
        TestDictionary testDictionary = new TestDictionary();
        testDictionary.addWord(new Word("after", "after", ExpressionUtilities.parseExpressions("unused(af), unused(ter)"), "", 0, false));
        testDictionary.addPhrase(new Phrase("day [exp:unused(af),unused(ter)] tomorrow", ExpressionUtilities.parseExpressions("phrase(day_after_tomorrow)"), ""));
        InputParser inputParser = new InputParser(Arrays.asList((IDictionary) testDictionary));

        //test
        List<Suggestion> solutions = inputParser.parse(lookupString);

        //assert
        Assert.assertEquals("day after tomorrow", solutions.get(0).build().get(0).getFoundValue());
    }*/
}
