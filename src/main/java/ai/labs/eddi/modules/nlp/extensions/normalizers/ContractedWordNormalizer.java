package ai.labs.eddi.modules.nlp.extensions.normalizers;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

public class ContractedWordNormalizer implements INormalizer {
    private static final Map<String, String> contractedWords = new HashMap<>();

    static {
        contractedWords.put("don't", "do not");
        contractedWords.put("doesn't", "does not");
        contractedWords.put("didn't", "did not");
        contractedWords.put("can't", "cannot");
        contractedWords.put("couldn't", "could not");
        contractedWords.put("won't", "would not");
        contractedWords.put("wasn't", "was not");
        contractedWords.put("weren't", "were not");
        contractedWords.put("shouldn't", "should not");
        contractedWords.put("haven't", "have not");
        contractedWords.put("hasn't", "has not");
        contractedWords.put("i'm", "i am");
        contractedWords.put("you're", "you are");
        contractedWords.put("he's", "he is");
        contractedWords.put("she's", "she is");
        contractedWords.put("it's", "it is");
        contractedWords.put("they're", "they are");
        contractedWords.put("we're", "we are");
    }

    @Override
    public String normalize(String input, String userLanguage) {
        List<String> inputParts = toStringList(input);

        IntStream.range(0, inputParts.size()).forEach(idx -> {
            String part = inputParts.get(idx);

            String match = contractedWords.get(part.toLowerCase());
            if (match != null) {
                inputParts.set(idx, match);
            }
        });

        return concatWords(inputParts);
    }
}
