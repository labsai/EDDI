/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.extensions.normalizers;

import ai.labs.eddi.modules.nlp.extensions.normalizers.providers.PunctuationNormalizerProvider;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

public class PunctuationNormalizerTest {

    private static final String DEFAULT_USER_LANGUAGE = "en";
    private PunctuationNormalizer normalizer;
    private final String testString = "This is!just an:example,of a string,that needs? to be fixed.by inserting:a whitespace;"
            + "after punctuation marks! ";

    @BeforeEach
    public void setUp() {
        normalizer = new PunctuationNormalizer(new PunctuationNormalizerProvider().toRegexPattern(PunctuationNormalizer.PUNCTUATION), false);
    }

    @Test
    public void normalizeInsertWhitespaces() {
        // setup
        final String expected = "This is ! just an : example , of a string , that needs ? to be fixed . by inserting : a whitespace ; after punctuation marks !";
        normalizer.setRemovePunctuation(false);

        // test
        String actual = normalizer.normalize(testString, DEFAULT_USER_LANGUAGE);

        // assert
        Assertions.assertEquals(expected, actual);
    }

    @Test
    public void containsPunctuationOnlyMatchesActualPunctuation() {
        // an unpunctuated token must not be reported as containing punctuation
        Assertions.assertFalse(normalizer.containsPunctuation("hello"));
        Assertions.assertFalse(normalizer.containsPunctuation("21st"));
        Assertions.assertFalse(normalizer.containsPunctuation(""));

        Assertions.assertTrue(normalizer.containsPunctuation("hello,"));
        Assertions.assertTrue(normalizer.containsPunctuation("what?"));
    }

    @Test
    public void containsPunctuationHonoursConfiguredPattern() {
        // setup — an agent designer may override the punctuation characters
        var customNormalizer = new PunctuationNormalizer(new PunctuationNormalizerProvider().toRegexPattern("|"), false);

        // test & assert
        Assertions.assertTrue(customNormalizer.containsPunctuation("a|b"));
        Assertions.assertFalse(customNormalizer.containsPunctuation("a,b"));
    }

    @Test
    public void normalizeRemovePunctuation() {
        // setup
        final String expected = "This is just an example of a string that needs to be fixed by inserting a whitespace after punctuation marks";
        normalizer.setRemovePunctuation(true);

        // test
        String actual = normalizer.normalize(testString, DEFAULT_USER_LANGUAGE);

        // assert
        Assertions.assertEquals(expected, actual);
    }

    @Test
    public void providerDoesNotLeakOneParsersPatternIntoTheNext() {
        var provider = new PunctuationNormalizerProvider();

        // a parser with its own pattern...
        var custom = (PunctuationNormalizer) provider.provide(Map.of("punctuationRegexPattern", "|"));
        Assertions.assertTrue(custom.containsPunctuation("a|b"));

        // ...must not change the default of a parser built afterwards
        var plain = (PunctuationNormalizer) provider.provide(Map.of());
        Assertions.assertFalse(plain.containsPunctuation("a|b"));
        Assertions.assertTrue(plain.containsPunctuation("a,b"));
        Assertions.assertEquals(PunctuationNormalizer.PUNCTUATION,
                provider.getConfigs().get("punctuationRegexPattern").getDefaultValue());
    }

    @Test
    public void aPatternThatBacktracksCatastrophicallyIsCutOffNotAwaited() {
        // "]" closes the generated character class, so the rest is arbitrary regex
        var pathological = new PunctuationNormalizer(new PunctuationNormalizerProvider().toRegexPattern("x]|(a+)+$|[y"), false);
        String input = "a".repeat(60) + "!";

        long start = System.nanoTime();
        String result = pathological.normalize(input, DEFAULT_USER_LANGUAGE);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;

        Assertions.assertNotNull(result);
        Assertions.assertTrue(elapsedMs < 5_000, "normalize took " + elapsedMs + "ms");
    }
}
