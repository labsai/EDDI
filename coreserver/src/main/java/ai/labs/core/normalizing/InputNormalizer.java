package ai.labs.core.normalizing;

/**
 * @author ginccc
 */
public interface InputNormalizer {
    String DEFAULT_DEFINED_CHARS =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz <=>#€+&.,:;!?1234567890äÄüÜöÖßéáß";

    String normalizeInput(String sentence, String definedChars, boolean splitWords, boolean convertSpecialCharacter);
}
