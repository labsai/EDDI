package ai.labs.eddi.modules.nlp.extensions.corrections.providers;


import ai.labs.eddi.modules.nlp.extensions.corrections.ICorrection;
import ai.labs.eddi.modules.nlp.extensions.corrections.PhoneticCorrection;

import java.util.Map;


/**
 * @author ginccc
 */
public class PhoneticCorrectionProvider implements ICorrectionProvider {
    public static final String ID = "ai.labs.parser.corrections.phonetic";

    private PhoneticCorrection phoneticCorrection;

    @Override
    public ICorrection provide() {
        return phoneticCorrection != null ? phoneticCorrection : new PhoneticCorrection(false);
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return "Phonetic Matching Correction";
    }

    @Override
    public void setConfig(Map<String, Object> config) {
        boolean lookupIfKnownParam = extractLookupIfKnownParam(config);
        phoneticCorrection = new PhoneticCorrection(lookupIfKnownParam);
    }
}
