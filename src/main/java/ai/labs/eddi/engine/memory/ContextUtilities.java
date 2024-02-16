package ai.labs.eddi.engine.memory;

import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.configs.properties.model.Property;

import java.util.Map;

import static ai.labs.eddi.engine.memory.IConversationMemory.IConversationProperties;
import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

public class ContextUtilities {
    private static final String KEY_LANG = "lang";

    public static void storeContextLanguageInLongTermMemory(Map<String, Context> contextMap, IConversationMemory memory) {
        for (String contextKey : contextMap.keySet()) {
            if (KEY_LANG.equals(contextKey)) {
                Context context = contextMap.get(contextKey);
                if (context.getType().equals(Context.ContextType.string)) {
                    var contextValue = context.getValue();
                    if (!isNullOrEmpty(contextValue)) {
                        var property = new Property(KEY_LANG, contextValue.toString(), Property.Scope.longTerm);
                        memory.getConversationProperties().put(KEY_LANG, property);
                    }
                }
            }
        }
    }

    public static String retrieveContextLanguageFromLongTermMemory(IConversationProperties properties) {
        String lang = null;

        Property languageProperty = properties.get(KEY_LANG);
        if (languageProperty != null && languageProperty.getValueString() != null) {
            lang = languageProperty.getValueString();
        }

        return lang;
    }
}
