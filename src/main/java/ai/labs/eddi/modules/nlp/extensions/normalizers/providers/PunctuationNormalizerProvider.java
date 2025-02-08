package ai.labs.eddi.modules.nlp.extensions.normalizers.providers;

import ai.labs.eddi.configs.packages.model.ExtensionDescriptor.ConfigValue;
import ai.labs.eddi.modules.nlp.extensions.normalizers.INormalizer;
import ai.labs.eddi.modules.nlp.extensions.normalizers.PunctuationNormalizer;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;

import static ai.labs.eddi.configs.packages.model.ExtensionDescriptor.FieldType.BOOLEAN;
import static ai.labs.eddi.configs.packages.model.ExtensionDescriptor.FieldType.STRING;

@ApplicationScoped
public class PunctuationNormalizerProvider implements INormalizerProvider {
    public static final String ID = "ai.labs.parser.normalizers.punctuation";

    private static final String KEY_REMOVE_PUNCTUATION = "removePunctuation";
    private static final String KEY_PUNCTUATION_REGEX_PATTERN = "punctuationRegexPattern";
    private String punctuationRegexPattern = PunctuationNormalizer.PUNCTUATION;

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return "Punctuation Normalizer";
    }

    @Override
    public INormalizer provide(Map<String, Object> config) {
        boolean removePunctuation = false;

        if (config.containsKey(KEY_REMOVE_PUNCTUATION)) {
            removePunctuation = Boolean.parseBoolean(config.get(KEY_REMOVE_PUNCTUATION).toString());
        }

        if (config.containsKey(KEY_PUNCTUATION_REGEX_PATTERN)) {
            punctuationRegexPattern = config.get(KEY_PUNCTUATION_REGEX_PATTERN).toString();
        }

        return new PunctuationNormalizer(toRegexPattern(punctuationRegexPattern), removePunctuation);
    }

    @Override
    public Map<String, ConfigValue> getConfigs() {
        Map<String, ConfigValue> ret = new HashMap<>();

        ret.put(KEY_REMOVE_PUNCTUATION, new ConfigValue("Remove Punctuation", BOOLEAN, true, false));
        ret.put(KEY_PUNCTUATION_REGEX_PATTERN, new ConfigValue("Punctuation RegEx Pattern", STRING, true, punctuationRegexPattern));

        return ret;
    }

    public Pattern toRegexPattern(String punctuation) {
        return Pattern.compile("[" + punctuation + "]");
    }
}
