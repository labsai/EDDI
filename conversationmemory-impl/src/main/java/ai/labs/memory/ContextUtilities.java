package ai.labs.memory;

import ai.labs.models.Context;
import ai.labs.models.Property;

import java.util.List;

import static ai.labs.utilities.RuntimeUtilities.isNullOrEmpty;

public class ContextUtilities {
    private static final String KEY_LANG = "lang";
    private static final String KEY_CONTEXT = "context";
    public static final String DEFAULT_USER_LANGUAGE = "en";

    public static String retrieveAndStoreContextLanguageInLongTermMemory(IConversationMemory memory) {
        String lang = null;
        var currentStep = memory.getCurrentStep();
        List<IData<Context>> contextDataList = currentStep.getAllData(KEY_CONTEXT);
        for (IData<Context> contextData : contextDataList) {
            String contextKey = contextData.getKey();
            Context context = contextData.getResult();
            String key = contextKey.substring((KEY_CONTEXT + ":").length());
            if (key.startsWith(KEY_LANG) && context.getType().equals(Context.ContextType.string)) {
                var contextValue = context.getValue();
                if (!isNullOrEmpty(contextValue)) {
                    lang = contextValue.toString();
                    memory.getConversationProperties().put(KEY_LANG, new Property(KEY_LANG, lang, Property.Scope.longTerm));
                }
            }
        }

        if (isNullOrEmpty(lang)) {
            Property languageProperty = memory.getConversationProperties().get(KEY_LANG);
            lang = languageProperty != null ? languageProperty.getValue().toString() : DEFAULT_USER_LANGUAGE;
        }

        return lang;
    }
}
