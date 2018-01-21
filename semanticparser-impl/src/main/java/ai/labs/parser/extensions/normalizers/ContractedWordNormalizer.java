package ai.labs.parser.extensions.normalizers;

import java.util.*;
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
        contractedWords.put("shouldn't", "should not");
        contractedWords.put("it's", "it is");
    }

    @Override
    public String normalize(String input) {
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
