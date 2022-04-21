package ai.labs.eddi.modules.nlp.extensions.normalizers;

import ai.labs.eddi.utils.LanguageUtilities;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

@NoArgsConstructor
@AllArgsConstructor
@Setter
public class PunctuationNormalizer implements INormalizer {
    public static final String PUNCTUATION = "!?:.,;";
    private static final String REPLACE_REGEX = " $0 ";
    private final Pattern multipleSpacesPattern = Pattern.compile("\\s+");

    private Pattern punctuationPattern;
    private boolean removePunctuation;

    public String normalize(String input, String userLanguage) {
        List<String> inputParts = toStringList(input);

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

    private boolean containsPunctuation(String part) {
        for (int i = 0; i < part.length(); i++) {
            String punctuationChar = part.substring(i, i + 1);
            if (part.contains(punctuationChar)) {
                return true;
            }
        }
        return false;
    }
}
