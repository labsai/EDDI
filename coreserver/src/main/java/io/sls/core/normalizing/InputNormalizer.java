package io.sls.core.normalizing;

/**
 * @author ginccc
 */
public interface InputNormalizer {
    String normalizeInput(String sentence);

    String normalizeInput(String sentence, String definedChars);

    String normalizeInput(String sentence, String definedChars, boolean splitWords);
}
