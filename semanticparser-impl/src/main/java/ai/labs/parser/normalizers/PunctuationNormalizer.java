package ai.labs.parser.normalizers;

import ai.labs.parser.model.INormalizer;
import ai.labs.utilities.LanguageUtilities;
import lombok.Setter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

public class PunctuationNormalizer implements INormalizer {
    private static final String PUNCTUATION = "!?:.,;";
    private static final String BLANK_CHAR = " ";
    private static final String REPLACE_REGEX = " $0 ";
    private final Pattern multipleSpacesPattern = Pattern.compile("\\s+");

    private Pattern punctuationPattern = setPunctuation(PUNCTUATION);
    @Setter
    private boolean removePunctuation = false;

    public String normalize(String input) {
        List<String> inputParts = new ArrayList<>(Arrays.asList(input.split(BLANK_CHAR)));

        final String replacement = removePunctuation ? BLANK_CHAR : REPLACE_REGEX;
        IntStream.range(0, inputParts.size()).forEach(idx -> {
            String part = inputParts.get(idx);

            if (containsPunctuation(part) && !isTimeExpression(part) && !isOrdinalNumber(part)) {
                part = punctuationPattern.matcher(part).replaceAll(replacement);
                part = multipleSpacesPattern.matcher(part).replaceAll(BLANK_CHAR);
                inputParts.set(idx, part);
            }
        });

        return concatWords(inputParts);
    }

    private boolean isOrdinalNumber(String part) {
        return LanguageUtilities.isOrdinalNumber(part) != null;
    }

    private boolean isTimeExpression(String part) {
        return LanguageUtilities.isTimeExpression(part) != null;
    }

    private String concatWords(List<String> inputParts) {
        StringBuilder newInput = new StringBuilder();
        inputParts.forEach(part -> newInput.append(part.trim()).append(BLANK_CHAR));
        return newInput.toString();
    }

    private boolean containsPunctuation(String part) {
        for (int i = 0; i < part.length(); i++) {
            String punctuationChar = part.substring(i, i + 1);
            if (part.contains(punctuationChar)) {
                return true;
            }
        }
        return false;
    }

    public Pattern setPunctuation(String punctuation) {
        punctuationPattern = Pattern.compile("[" + punctuation + "]");
        return punctuationPattern;
    }
}
