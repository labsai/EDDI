package ai.labs.parser.extensions.normalizers;

import ai.labs.parser.extensions.normalizers.providers.PunctuationNormalizerProvider;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

public class PunctuationNormalizerTest {

    private static final String DEFAULT_USER_LANGUAGE = "en";
    private PunctuationNormalizer normalizer;
    private final String testString =
            "This is!just an:example,of a string,that needs? to be fixed.by inserting:a whitespace;" +
                    "after punctuation marks! ";

    @Before
    public void setUp() {
        normalizer = new PunctuationNormalizer(
                new PunctuationNormalizerProvider().
                        toRegexPattern(PunctuationNormalizer.PUNCTUATION),
                false);
    }

    @Test
    public void normalizeInsertWhitespaces() {
        //setup
        final String expected = "This is ! just an : example , of a string , that needs ? to be fixed . by inserting : a whitespace ; after punctuation marks !";
        normalizer.setRemovePunctuation(false);

        //test
        String actual = normalizer.normalize(testString, DEFAULT_USER_LANGUAGE);

        //assert
        Assert.assertEquals(expected, actual);
    }

    @Test
    public void normalizeRemovePunctuation() {
        //setup
        final String expected = "This is just an example of a string that needs to be fixed by inserting a whitespace after punctuation marks";
        normalizer.setRemovePunctuation(true);

        //test
        String actual = normalizer.normalize(testString, DEFAULT_USER_LANGUAGE);

        //assert
        Assert.assertEquals(expected, actual);
    }
}