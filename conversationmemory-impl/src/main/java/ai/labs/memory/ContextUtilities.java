package ai.labs.memory;

import ai.labs.models.Context;
import ai.labs.models.Property;

import java.util.Map;

import static ai.labs.memory.IConversationMemory.IConversationProperties;
import static ai.labs.utilities.RuntimeUtilities.isNullOrEmpty;

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
        if (languageProperty != null && languageProperty.getValue() != null) {
            lang = languageProperty.getValue().toString();
        }

        return lang;
    }
}
