package ai.labs.parser.extensions.corrections.providers;

import ai.labs.parser.extensions.corrections.DamerauLevenshteinCorrection;
import ai.labs.parser.extensions.corrections.ICorrection;

import java.util.Map;

/**
 * @author ginccc
 */
public class DamerauLevenshteinCorrectionProvider implements ICorrectionProvider {
    public static final String ID = "ai.labs.parser.corrections.levenshtein";

    private static final String KEY_DISTANCE = "distance";
    private DamerauLevenshteinCorrection damerauLevenshteinCorrection;

    @Override
    public ICorrection provide() {
        return damerauLevenshteinCorrection != null ? damerauLevenshteinCorrection :
                new DamerauLevenshteinCorrection();
    }

    @Override
    public void setConfig(Map<String, Object> config) {
        Object distanceObj = config.get(KEY_DISTANCE);
        int distance;
        distance = distanceObj == null ? 2 : Integer.valueOf((String) distanceObj);

        boolean lookupIfKnown = extractLookupIfKnownParam(config);
        damerauLevenshteinCorrection = new DamerauLevenshteinCorrection(distance, lookupIfKnown);
    }
}
