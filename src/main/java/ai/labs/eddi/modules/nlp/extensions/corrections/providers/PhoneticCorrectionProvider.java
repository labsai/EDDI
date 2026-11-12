/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.nlp.extensions.corrections.providers;

import ai.labs.eddi.configs.workflows.model.ExtensionDescriptor.ConfigValue;
import ai.labs.eddi.modules.nlp.extensions.corrections.ICorrection;
import ai.labs.eddi.modules.nlp.extensions.corrections.PhoneticCorrection;
import io.quarkus.runtime.Startup;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.HashMap;
import java.util.Map;

import static ai.labs.eddi.configs.workflows.model.ExtensionDescriptor.FieldType.BOOLEAN;

/**
 * @author ginccc
 */
@Startup(1000)
@ApplicationScoped
public class PhoneticCorrectionProvider implements ICorrectionProvider {
    public static final String ID = "ai.labs.parser.corrections.phonetic";

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return "Phonetic Matching Correction";
    }

    @Override
    public ICorrection provide(Map<String, Object> config) {
        boolean lookupIfKnownParam = extractLookupIfKnownParam(config);
        return new PhoneticCorrection(lookupIfKnownParam);
    }

    @Override
    public Map<String, ConfigValue> getConfigs() {
        Map<String, ConfigValue> ret = new HashMap<>();

        ret.put(KEY_LOOKUP_IF_KNOWN, new ConfigValue("Correct Known Words Too", BOOLEAN, true, false));

        return ret;
    }
}
