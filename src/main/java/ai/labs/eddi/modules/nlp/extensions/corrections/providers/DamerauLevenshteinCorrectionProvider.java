package ai.labs.eddi.modules.nlp.extensions.corrections.providers;


import ai.labs.eddi.configs.packages.model.ExtensionDescriptor.ConfigValue;
import ai.labs.eddi.modules.nlp.extensions.corrections.DamerauLevenshteinCorrection;
import ai.labs.eddi.modules.nlp.extensions.corrections.ICorrection;
import io.quarkus.runtime.Startup;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.HashMap;
import java.util.Map;

import static ai.labs.eddi.configs.packages.model.ExtensionDescriptor.FieldType.INT;

/**
 * @author ginccc
 */
@Startup(1000)
@ApplicationScoped
public class DamerauLevenshteinCorrectionProvider implements ICorrectionProvider {
    public static final String ID = "ai.labs.parser.corrections.levenshtein";

    private static final String KEY_DISTANCE = "distance";
    public static final int DEFAULT_DISTANCE = 2;

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return "Damerau Levenshtein Correction";
    }

    @Override
    public ICorrection provide(Map<String, Object> config) {
        Object distanceObj = config.get(KEY_DISTANCE);
        int distance = distanceObj == null ? DEFAULT_DISTANCE : Integer.parseInt((String) distanceObj);

        boolean lookupIfKnown = extractLookupIfKnownParam(config);

        return new DamerauLevenshteinCorrection(distance, lookupIfKnown);
    }

    @Override
    public Map<String, ConfigValue> getConfigs() {
        Map<String, ConfigValue> ret = new HashMap<>();

        ret.put(KEY_DISTANCE, new ConfigValue("Distance", INT, true, 2));

        return ret;
    }
}
