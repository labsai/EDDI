/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.extensions.normalizers;

import ai.labs.eddi.modules.nlp.extensions.normalizers.providers.PunctuationNormalizerProvider;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;

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
    public void configuredPatternCannotCloseTheCharacterClass() {
        // "!]|(a+)+$|[!" used to compile to [!]|(a+)+$|[!] — an alternation with a
        // catastrophically backtracking branch, run against every token of every
        // user message. Now it is read as the literal characters it contains.
        var provider = new PunctuationNormalizerProvider();
        Pattern pattern = provider.toRegexPattern("!]|(a+)+$|[!");
        String token = "a".repeat(40) + "b";

        Assertions.assertTimeoutPreemptively(Duration.ofSeconds(5), () -> Assertions.assertTrue(pattern.matcher(token).find()));
        Assertions.assertTrue(pattern.matcher("x|y").find(), "the characters themselves are the punctuation");
        Assertions.assertTrue(pattern.matcher("x$y").find());
        // Not "plain": the setting's literal characters include 'a'.
        Assertions.assertFalse(pattern.matcher("query").find());
    }

    @Test
    public void classSyntaxTheSettingAlwaysAcceptedStillWorks() {
        var provider = new PunctuationNormalizerProvider();

        Pattern bracketed = provider.toRegexPattern("[.!?]");
        Assertions.assertTrue(bracketed.matcher("hi!").find());
        Assertions.assertFalse(bracketed.matcher("a,b").find());
        Assertions.assertFalse(bracketed.matcher("[x]").find(), "the brackets of a nested class are not punctuation");

        Assertions.assertTrue(provider.toRegexPattern("\\p{Punct}").matcher("a;b").find());
        Assertions.assertTrue(provider.toRegexPattern("x-z").matcher("y").find());
        Assertions.assertTrue(provider.toRegexPattern("\\]").matcher("a]").find());
        Assertions.assertTrue(provider.toRegexPattern("z-a").matcher("-").find(), "an invalid range falls back to its literal characters");
        Assertions.assertTrue(provider.toRegexPattern("\\Q]|x\\E").matcher("|").find(), "a \\Q quote is read literally");
        Assertions.assertFalse(provider.toRegexPattern("\\Q]|x\\E").matcher("y").find());
        Assertions.assertFalse(provider.toRegexPattern("").matcher("a,b").find());
    }

    @Test
    public void customPunctuationDoesNotLeakIntoTheNextParser() {
        var provider = new PunctuationNormalizerProvider();
        provider.provide(Map.<String, Object>of("punctuationRegexPattern", "|"));

        var defaults = (PunctuationNormalizer) provider.provide(Map.<String, Object>of());

        Assertions.assertTrue(defaults.containsPunctuation("a,b"), "the provider is a shared singleton; the default must stay the default");
        Assertions.assertFalse(defaults.containsPunctuation("a|b"));
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
