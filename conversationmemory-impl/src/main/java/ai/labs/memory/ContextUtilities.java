package ai.labs.memory;

import ai.labs.models.Context;
import ai.labs.models.Property;

import java.util.List;

public class ContextUtilities {
    private static final String KEY_LANG = "lang";
    private static final String KEY_CONTEXT = "context";

    public static String retrieveAndStoreContextLanguageInLongTermMemory(IConversationMemory memory) {
        var lang = "en";
        var currentStep = memory.getCurrentStep();
        List<IData<Context>> contextDataList = currentStep.getAllData(KEY_CONTEXT);
        for (IData<Context> contextData : contextDataList) {
            String contextKey = contextData.getKey();
            Context context = contextData.getResult();
            String key = contextKey.substring((KEY_CONTEXT + ":").length());
            if (key.startsWith(KEY_LANG) && context.getType().equals(Context.ContextType.string)) {
                lang = context.getValue().toString();
                memory.getConversationProperties().put(KEY_LANG, new Property(KEY_LANG, lang, Property.Scope.longTerm));
            }
        }

        return lang;
    }
}
