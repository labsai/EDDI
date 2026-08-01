/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.extensions.corrections.providers;

import ai.labs.eddi.configs.workflows.model.ExtensionDescriptor.ConfigValue;
import ai.labs.eddi.modules.nlp.extensions.corrections.DamerauLevenshteinCorrection;
import ai.labs.eddi.modules.nlp.extensions.corrections.ICorrection;
import io.quarkus.runtime.Startup;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.HashMap;
import java.util.Map;

import static ai.labs.eddi.configs.workflows.model.ExtensionDescriptor.FieldType.INT;
import static ai.labs.eddi.modules.nlp.extensions.corrections.DamerauLevenshteinCorrection.DEFAULT_MAX_CANDIDATES;

/**
 * @author ginccc
 */
@Startup(1000)
@ApplicationScoped
public class DamerauLevenshteinCorrectionProvider implements ICorrectionProvider {
    public static final String ID = "ai.labs.parser.corrections.levenshtein";

    private static final String KEY_DISTANCE = "distance";
    private static final String KEY_MAX_CANDIDATES = "maxCandidates";
    public static final int DEFAULT_DISTANCE = 2;

    private static final Logger log = Logger.getLogger(DamerauLevenshteinCorrectionProvider.class);

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
        int distance = parsePositiveInt(config.get(KEY_DISTANCE), KEY_DISTANCE, DEFAULT_DISTANCE);
        int maxCandidates = parsePositiveInt(config.get(KEY_MAX_CANDIDATES), KEY_MAX_CANDIDATES, DEFAULT_MAX_CANDIDATES);

        boolean lookupIfKnown = extractLookupIfKnownParam(config);

        return new DamerauLevenshteinCorrection(distance, lookupIfKnown, maxCandidates);
    }

    /**
     * Config values arrive either as JSON strings or as JSON numbers depending on
     * how the workflow was authored, so parse the string form of whatever was
     * given. A malformed or non-positive value falls back to the default instead of
     * failing the whole workflow.
     */
    private static int parsePositiveInt(Object value, String key, int defaultValue) {
        if (value == null || value.toString().isBlank()) {
            return defaultValue;
        }

        try {
            int parsed = Integer.parseInt(value.toString().trim());
            if (parsed < 1) {
                log.warnf("'%s' must be greater than 0, but was %s. Falling back to %s.", key, parsed, defaultValue);
                return defaultValue;
            }

            return parsed;
        } catch (NumberFormatException e) {
            log.warnf("'%s' value '%s' is not a number. Falling back to %s.", key, value, defaultValue);
            return defaultValue;
        }
    }

    @Override
    public Map<String, ConfigValue> getConfigs() {
        Map<String, ConfigValue> ret = new HashMap<>();

        ret.put(KEY_DISTANCE, new ConfigValue("Distance", INT, true, DEFAULT_DISTANCE));
        ret.put(KEY_MAX_CANDIDATES, new ConfigValue("Max Correction Candidates", INT, true, DEFAULT_MAX_CANDIDATES));

        return ret;
    }
}
