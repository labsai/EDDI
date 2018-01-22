package ai.labs.parser.extensions;

import ai.labs.lifecycle.IllegalExtensionConfigurationException;

import java.util.Map;

public interface IParserExtensionProvider<T> {
    String KEY_LOOKUP_IF_KNOWN = "lookupIfKnown";

    default boolean extractLookupIfKnownParam(Map<String, Object> config) {
        Object lookupIfKnownObj = config.get(KEY_LOOKUP_IF_KNOWN);
        return lookupIfKnownObj == null ? false : Boolean.valueOf((String) lookupIfKnownObj);
    }

    default void setConfig(Map<String, Object> config) throws IllegalExtensionConfigurationException {
        //to be overridden if needed
    }

    T provide();
}
