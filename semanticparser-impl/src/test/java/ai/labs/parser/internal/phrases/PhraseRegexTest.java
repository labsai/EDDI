package ai.labs.parser.internal.phrases;

/**
 * @author ginccc
 */
public class PhraseRegexTest {
    /*@Test
    public void testParsePhraseWithRegex() throws Exception {
        //setup
        String lookupString = "day after tomorrow";
        TestDictionary testDictionary = new TestDictionary();
        testDictionary.addWord(new Word("after", "after", ExpressionProvider.parseExpressions("unused(after)"), "", 0, false));
        testDictionary.addPhrase(new Phrase("day [regex:[a-z]*] tomorrow", ExpressionProvider.parseExpressions("phrase(day_after_tomorrow)"), ""));
        InputParser inputParser = new InputParser(Arrays.asList((IDictionary) testDictionary));

        //test
        List<Suggestion> result = inputParser.parse(lookupString);

        //assert
        Assert.assertEquals("day after tomorrow", result.get(0).build().get(0).getFoundValue());
    }

    @Test
    public void testParsePhraseWithRegex1() throws Exception {
        //setup
        String lookupString = "day after tomorrow";
        TestDictionary testDictionary = new TestDictionary();
        testDictionary.addWord(new Word("after", "after", ExpressionProvider.parseExpressions("unused(after)"), "", 0, false));
        testDictionary.addPhrase(new Phrase("day [regex:[0-9]*] tomorrow", ExpressionProvider.parseExpressions("phrase(day_after_tomorrow)"), ""));
        InputParser inputParser = new InputParser(Arrays.asList((IDictionary) testDictionary));

        //test
        List<Suggestion> result = inputParser.parse(lookupString);

        //assert
        Assert.assertNotSame("day after tomorrow", result.get(0).build().get(0).getFoundValue());
    }*/
}
