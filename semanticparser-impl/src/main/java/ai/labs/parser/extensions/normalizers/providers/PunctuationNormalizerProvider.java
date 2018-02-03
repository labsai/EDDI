package ai.labs.parser.extensions.normalizers.providers;

import ai.labs.parser.extensions.normalizers.INormalizer;
import ai.labs.parser.extensions.normalizers.PunctuationNormalizer;

import java.util.Map;
import java.util.regex.Pattern;

public class PunctuationNormalizerProvider implements INormalizerProvider {
    public static final String ID = "ai.labs.parser.normalizers.punctuation";

    private static final String KEY_REMOVE_PUNCTUATION = "removePunctuation";
    private static final String KEY_PUNCTUATION_REGEX_PATTERN = "punctuationRegexPattern";
    private boolean removePunctuation;
    private String punctuationRegexPattern = PunctuationNormalizer.PUNCTUATION;

    @Override
    public void setConfig(Map<String, Object> config) {
        if (config.containsKey(KEY_REMOVE_PUNCTUATION)) {
            removePunctuation = Boolean.parseBoolean(config.get(KEY_REMOVE_PUNCTUATION).toString());
        }

        if (config.containsKey(KEY_PUNCTUATION_REGEX_PATTERN)) {
            punctuationRegexPattern = config.get(KEY_PUNCTUATION_REGEX_PATTERN).toString();
        }
    }

    @Override
    public INormalizer provide() {
        return new PunctuationNormalizer(toRegexPattern(punctuationRegexPattern), removePunctuation);
    }

    public Pattern toRegexPattern(String punctuation) {
        return Pattern.compile("[" + punctuation + "]");
    }
}
