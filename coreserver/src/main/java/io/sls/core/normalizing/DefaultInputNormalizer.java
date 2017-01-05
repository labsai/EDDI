package io.sls.core.normalizing;

import io.sls.core.utilities.WordSplitter;
import io.sls.utilities.CharacterUtilities;

public class DefaultInputNormalizer implements InputNormalizer {
    private static final String DEFINED_CHARS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz <=>" +
                    "#€+&.,:;!?1234567890äÄüÜöÖßéáß";

    @Override
    public String normalizeInput(String sentence) {
        return normalizeInput(sentence, DEFINED_CHARS);
    }

    @Override
    public String normalizeInput(String sentence, String definedChars) {
        return normalizeInput(sentence, definedChars, true);
    }

    public String normalizeInput(String sentence, String definedChars, boolean splitWords) {
        StringBuilder sb = new StringBuilder(sentence);

        CharacterUtilities.convertSpecialCharacter(sb);
        CharacterUtilities.deleteUndefinedChars(sb, definedChars);

        sb.trimToSize();

        if (splitWords) {
            WordSplitter splitter = new WordSplitter(sb);
            splitter.splitWords();
        }

        return sb.toString();
    }
}
