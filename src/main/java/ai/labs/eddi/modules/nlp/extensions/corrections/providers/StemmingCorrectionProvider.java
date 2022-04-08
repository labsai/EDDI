package ai.labs.eddi.modules.nlp.extensions.corrections.providers;


import ai.labs.eddi.engine.lifecycle.IllegalExtensionConfigurationException;
import ai.labs.eddi.modules.nlp.extensions.corrections.ICorrection;
import ai.labs.eddi.modules.nlp.extensions.corrections.StemmingCorrection;
import io.quarkus.runtime.Startup;

import javax.enterprise.context.ApplicationScoped;
import java.util.Map;


/**
 * @author ginccc
 */
@Startup
@ApplicationScoped
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
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return "Grammar Stemming Correction";
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
