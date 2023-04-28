package ai.labs.eddi.modules.nlp.extensions.corrections.providers;


import ai.labs.eddi.engine.lifecycle.exceptions.IllegalExtensionConfigurationException;
import ai.labs.eddi.modules.nlp.extensions.corrections.ICorrection;
import ai.labs.eddi.modules.nlp.extensions.corrections.StemmingCorrection;
import io.quarkus.runtime.Startup;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;


/**
 * @author ginccc
 */
@Startup(1000)
@ApplicationScoped
public class StemmingCorrectionProvider implements ICorrectionProvider {
    public static final String ID = "ai.labs.parser.corrections.stemming";
    private static final String KEY_LANGUAGE = "language";

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return "Grammar Stemming Correction";
    }

    @Override
    public ICorrection provide(Map<String, Object> config) throws IllegalExtensionConfigurationException {
        Object languageObj = config.get(KEY_LANGUAGE);
        String language;
        if (languageObj == null) {
            throw new IllegalExtensionConfigurationException("Param 'language' is not defined. [StemmingCorrection]");
        } else {
            language = (String) languageObj;
        }

        var lookupIfKnown = extractLookupIfKnownParam(config);

        return new StemmingCorrection(language, lookupIfKnown);
    }
}
