package ai.labs.backupservice.impl;


import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

/**
 * @author ginccc
 */
class CallbackMatcherTest {
    @Test
    void testReplaceMatchesOneOccurrence() throws Exception {
        //setup
        CallbackMatcher callbackMatcher = new CallbackMatcher(Pattern.compile("testRegex"));

        //test
        String result = callbackMatcher.replaceMatches("This is the test: testRegex!", matchResult -> "works");

        //assert
        Assertions.assertEquals("This is the test: works!", result);
    }

    @Test
    void testReplaceMatchesTwoOccurrence() throws Exception {
        //setup
        CallbackMatcher callbackMatcher = new CallbackMatcher(Pattern.compile("testRegex"));

        //test
        String result = callbackMatcher.replaceMatches("This is the test: testRegex + testRegex!", matchResult -> "works");

        //assert
        Assertions.assertEquals("This is the test: works + works!", result);
    }
}