package ai.labs.parser.normalizers;

import ai.labs.memory.model.Deployment;
import ai.labs.parser.model.INormalizer;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class PunctuationNormalizerTest {

    private PunctuationNormalizer normalizer;
    private final String testString =
            "This is!just an:example,of a string,that needs? to be fixed.by inserting:a whitespace;" +
                    "after punctuation marks! ";

    @Before
    public void setUp() {
        normalizer = new PunctuationNormalizer();
    }

    @Test
    public void normalizeInsertWhitespaces() {
        //setup
        final String expected = "This is ! just an : example , of a string , that needs ? to be fixed . by inserting : a whitespace ; after punctuation marks !";
        normalizer.setRemovePunctuation(false);

        //test
        String actual = normalizer.normalize(testString);

        //assert
        Assert.assertEquals(expected, actual);
    }

    @Test
    public void normalizeRemovePunctuation() {
        //setup
        final String expected = "This is just an example of a string that needs to be fixed by inserting a whitespace after punctuation marks";
        normalizer.setRemovePunctuation(true);

        //test
        String actual = normalizer.normalize(testString);

        //assert
        Assert.assertEquals(expected, actual);
    }
}