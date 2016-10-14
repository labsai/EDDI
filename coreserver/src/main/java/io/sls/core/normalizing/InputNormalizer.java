package io.sls.core.normalizing;

/**
 * User: jarisch
 * Date: 11.08.2009
 * Time: 13:34:41
 */
public interface InputNormalizer {
    String normalizeInput(String sentence);

    String normalizeInput(String sentence, String definedChars);

    String normalizeInput(String sentence, String definedChars, boolean splitWords);
}
