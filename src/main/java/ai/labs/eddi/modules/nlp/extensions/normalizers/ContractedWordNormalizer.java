/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.extensions.normalizers;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

/**
 * Expands English contractions ("don't" to "do not"), so a dictionary needs
 * only the long form. Ambiguous ones ("I'd": had or would, "he's" as "he has")
 * keep their most common reading or are left alone.
 */
public class ContractedWordNormalizer implements INormalizer {
    private static final Map<String, String> contractedWords = new HashMap<>();

    static {
        contractedWords.put("don't", "do not");
        contractedWords.put("doesn't", "does not");
        contractedWords.put("didn't", "did not");
        contractedWords.put("can't", "cannot");
        contractedWords.put("couldn't", "could not");
        // was "would not"
        contractedWords.put("won't", "will not");
        contractedWords.put("wouldn't", "would not");
        contractedWords.put("isn't", "is not");
        contractedWords.put("aren't", "are not");
        contractedWords.put("wasn't", "was not");
        contractedWords.put("weren't", "were not");
        contractedWords.put("shouldn't", "should not");
        contractedWords.put("mustn't", "must not");
        contractedWords.put("needn't", "need not");
        contractedWords.put("haven't", "have not");
        contractedWords.put("hasn't", "has not");
        contractedWords.put("hadn't", "had not");
        contractedWords.put("i'm", "i am");
        contractedWords.put("you're", "you are");
        contractedWords.put("he's", "he is");
        contractedWords.put("she's", "she is");
        contractedWords.put("it's", "it is");
        contractedWords.put("that's", "that is");
        contractedWords.put("there's", "there is");
        contractedWords.put("what's", "what is");
        contractedWords.put("let's", "let us");
        contractedWords.put("they're", "they are");
        contractedWords.put("we're", "we are");
        contractedWords.put("i've", "i have");
        contractedWords.put("you've", "you have");
        contractedWords.put("we've", "we have");
        contractedWords.put("they've", "they have");
        contractedWords.put("i'll", "i will");
        contractedWords.put("you'll", "you will");
        contractedWords.put("he'll", "he will");
        contractedWords.put("she'll", "she will");
        contractedWords.put("we'll", "we will");
        contractedWords.put("they'll", "they will");
    }

    /**
     * Typographic apostrophes, as phone keyboards insert them ("don’t"). Without
     * folding them onto {@code '} no contraction typed on a phone was expanded.
     */
    private static final Pattern TYPOGRAPHIC_APOSTROPHE = Pattern.compile("[\u2019\u02BC\u2018]");

    @Override
    public String normalize(String input, String userLanguage) {
        List<String> inputParts = toStringList(input);

        IntStream.range(0, inputParts.size()).forEach(idx -> {
            String part = inputParts.get(idx);

            String key = TYPOGRAPHIC_APOSTROPHE.matcher(part.toLowerCase(Locale.ROOT)).replaceAll("'");
            String match = contractedWords.get(key);
            if (match != null) {
                inputParts.set(idx, match);
            }
        });

        return concatWords(inputParts);
    }
}
