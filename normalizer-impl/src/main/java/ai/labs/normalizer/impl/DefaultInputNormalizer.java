package ai.labs.normalizer.impl;

import ai.labs.utilities.CharacterUtilities;
import ai.labs.utilities.WordSplitter;

public class DefaultInputNormalizer implements InputNormalizer {
    public String normalizeInput(String sentence, String definedChars, boolean splitWords, boolean convertSpecialCharacter) {
        StringBuilder sb = new StringBuilder(sentence);

        if (convertSpecialCharacter) {
            CharacterUtilities.convertSpecialCharacter(sb);
        }
        CharacterUtilities.deleteUndefinedChars(sb, definedChars);

        sb.trimToSize();

        if (splitWords) {
            WordSplitter splitter = new WordSplitter(sb);
            splitter.splitWords();
        }

        return sb.toString();
    }
}
