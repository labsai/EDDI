package ai.labs.parser.extensions;

import ai.labs.lifecycle.IllegalExtensionConfigurationException;
import ai.labs.resources.rest.extensions.model.ExtensionDescriptor;

import java.util.Collections;
import java.util.Map;

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

    default void setConfig(Map<String, Object> config) throws IllegalExtensionConfigurationException {
        //to be overridden if needed
    }

    T provide();

    default Map<String, ExtensionDescriptor.ConfigValue> getConfigs() {
        return Collections.emptyMap();
    }
}
