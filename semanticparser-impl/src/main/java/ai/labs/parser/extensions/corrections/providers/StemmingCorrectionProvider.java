package ai.labs.parser.extensions.corrections.providers;

import ai.labs.lifecycle.IllegalExtensionConfigurationException;
import ai.labs.parser.extensions.corrections.ICorrection;
import ai.labs.parser.extensions.corrections.StemmingCorrection;

import java.util.Map;


/**
 * @author ginccc
 */
public class StemmingCorrectionProvider implements ICorrectionProvider {
    public static final String ID = "ai.labs.parser.corrections.stemming";

    private static final String KEY_LANGUAGE = "language";
    private StemmingCorrection stemmingCorrection;

    @Override
    public ICorrection provide() {
        return stemmingCorrection != null ? stemmingCorrection :
                new StemmingCorrection("english", false);
    }

    @Override
    public void setConfig(Map<String, Object> config) throws IllegalExtensionConfigurationException {
        Object languageObj = config.get(KEY_LANGUAGE);
        String language;
        if (languageObj == null) {
            throw new IllegalExtensionConfigurationException("Param 'language' is not defined. [StemmingCorrection]");
        } else {
            language = (String) languageObj;
        }

        Boolean lookupIfKnown = extractLookupIfKnownParam(config);

        stemmingCorrection = new StemmingCorrection(language, lookupIfKnown);
    }
}
