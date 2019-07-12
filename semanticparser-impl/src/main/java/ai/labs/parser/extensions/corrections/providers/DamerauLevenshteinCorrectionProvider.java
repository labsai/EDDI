package ai.labs.parser.extensions.corrections.providers;

import ai.labs.parser.extensions.corrections.DamerauLevenshteinCorrection;
import ai.labs.parser.extensions.corrections.ICorrection;

import java.util.HashMap;
import java.util.Map;

import static ai.labs.models.ExtensionDescriptor.ConfigValue;
import static ai.labs.models.ExtensionDescriptor.FieldType.INT;

/**
 * @author ginccc
 */
public class DamerauLevenshteinCorrectionProvider implements ICorrectionProvider {
    public static final String ID = "ai.labs.parser.corrections.levenshtein";

    private static final String KEY_DISTANCE = "distance";
    public static final int DEFAULT_DISTANCE = 2;
    private DamerauLevenshteinCorrection damerauLevenshteinCorrection;

    @Override
    public ICorrection provide() {
        return damerauLevenshteinCorrection != null ? damerauLevenshteinCorrection :
                new DamerauLevenshteinCorrection();
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return "Damerau Levenshtein Correction";
    }

    @Override
    public void setConfig(Map<String, Object> config) {
        Object distanceObj = config.get(KEY_DISTANCE);
        int distance;
        distance = distanceObj == null ? DEFAULT_DISTANCE : Integer.valueOf((String) distanceObj);

        boolean lookupIfKnown = extractLookupIfKnownParam(config);
        damerauLevenshteinCorrection = new DamerauLevenshteinCorrection(distance, lookupIfKnown);
    }

    @Override
    public Map<String, ConfigValue> getConfigs() {
        Map<String, ConfigValue> ret = new HashMap<>();

        ret.put(KEY_DISTANCE, new ConfigValue("Distance", INT, true, 2));

        return ret;
    }
}
