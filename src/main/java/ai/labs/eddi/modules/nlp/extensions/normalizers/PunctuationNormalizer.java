/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.extensions.normalizers;

import ai.labs.eddi.modules.nlp.model.RegEx;
import ai.labs.eddi.utils.LanguageUtilities;
import ai.labs.eddi.utils.RegexDeadline;
import ai.labs.eddi.utils.RegexDeadline.MatchDeadlineExceededException;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.IntStream;

public class PunctuationNormalizer implements INormalizer {
    private static final Logger LOGGER = Logger.getLogger(PunctuationNormalizer.class);
    public static final String PUNCTUATION = "!?:.,;";
    private static final String REPLACE_REGEX = " $0 ";
    private final Pattern multipleSpacesPattern = Pattern.compile("\\s+");

    private Pattern punctuationPattern;
    private boolean removePunctuation;

    public String normalize(String input, String userLanguage) {
        List<String> inputParts = toStringList(input);

        final String replacement = removePunctuation ? BLANK_CHAR : REPLACE_REGEX;
        // One budget for the whole input: the pattern is configuration an editor
        // wrote (it can leave its character class), and an input cap bounds the text,
        // not the backtracking.
        final long deadline = System.nanoTime() + RegEx.MATCH_BUDGET_NANOS;
        try {
            IntStream.range(0, inputParts.size()).forEach(idx -> {
                String part = inputParts.get(idx);

                if (containsPunctuation(part, deadline) && !isTimeExpression(part) && !isOrdinalNumber(part)) {
                    part = RegexDeadline.matcher(punctuationPattern, part, deadline).replaceAll(replacement);
                    part = multipleSpacesPattern.matcher(part).replaceAll(BLANK_CHAR);
                    inputParts.set(idx, part);
                }
            });
        } catch (MatchDeadlineExceededException e) {
            LOGGER.warnf(
                    "Punctuation pattern did not finish within its time budget on a %d-character input; the remaining tokens are left as they are",
                    input.length());
        }

        return concatWords(inputParts);
    }

    private boolean isOrdinalNumber(String part) {
        return LanguageUtilities.extractOrdinalValue(part).isPresent();
    }

    private boolean isTimeExpression(String part) {
        return LanguageUtilities.isTimeExpression(part) != null;
    }

    /**
     * Whether the given token contains at least one character matching the
     * configured punctuation pattern (defaults to {@link #PUNCTUATION}).
     * Package-private so it can be asserted on directly in tests.
     */
    boolean containsPunctuation(String part) {
        return containsPunctuation(part, System.nanoTime() + RegEx.MATCH_BUDGET_NANOS);
    }

    private boolean containsPunctuation(String part, long deadline) {
        return RegexDeadline.matcher(punctuationPattern, part, deadline).find();
    }

    public PunctuationNormalizer() {
    }

    public PunctuationNormalizer(Pattern punctuationPattern, boolean removePunctuation) {
        this.punctuationPattern = punctuationPattern;
        this.removePunctuation = removePunctuation;
    }

    public void setPunctuationPattern(Pattern punctuationPattern) {
        this.punctuationPattern = punctuationPattern;
    }

    public void setRemovePunctuation(boolean removePunctuation) {
        this.removePunctuation = removePunctuation;
    }
}
