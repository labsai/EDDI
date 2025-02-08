package ai.labs.eddi.modules.nlp.extensions;

import ai.labs.eddi.engine.lifecycle.exceptions.IllegalExtensionConfigurationException;

import java.util.Collections;
import java.util.Map;

import static ai.labs.eddi.configs.packages.model.ExtensionDescriptor.ConfigValue;

public interface IParserExtensionProvider<T> {
    String KEY_LOOKUP_IF_KNOWN = "lookupIfKnown";

    String getId();

    default String getDisplayName() {
        return getId();
    }

    default boolean extractLookupIfKnownParam(Map<String, Object> config) {
        Object lookupIfKnownObj = config.get(KEY_LOOKUP_IF_KNOWN);
        return lookupIfKnownObj != null && Boolean.parseBoolean((String) lookupIfKnownObj);
    }

    default T provide(Map<String, Object> config) throws IllegalExtensionConfigurationException {
        //to be overridden if needed

        return null;
    }

    default Map<String, ConfigValue> getConfigs() {
        return Collections.emptyMap();
    }
}
