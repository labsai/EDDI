package ai.labs.parser.extensions.normalizers;

import org.junit.Assert;
import org.junit.Test;

public class ContractedWordNormalizerTest {

    @Test
    public void normalize() {
        //setup
        final String testString = "don't doesn't didn't can't couldn't won't shouldn't it's";
        final String expected = "do not does not did not cannot could not would not should not it is";
        ContractedWordNormalizer normalizer = new ContractedWordNormalizer();


        //test
        String actual = normalizer.normalize(testString, "en");

        //assert
        Assert.assertEquals(expected, actual);
    }
}