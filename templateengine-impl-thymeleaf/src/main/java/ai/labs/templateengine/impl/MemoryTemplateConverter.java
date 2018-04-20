package ai.labs.templateengine.impl;

import ai.labs.memory.IConversationMemory;
import ai.labs.memory.IData;
import ai.labs.templateengine.IMemoryTemplateConverter;

import java.util.*;

public class MemoryTemplateConverter implements IMemoryTemplateConverter {
    private static final String KEY_HTTP_CALLS = "httpCalls";

    @Override
    public Map<String, Object> convertMemoryForTemplating(IConversationMemory memory) {
        Map<String, Object> props = new HashMap<>();

        IConversationMemory.IWritableConversationStep currentStep = memory.getCurrentStep();
        Map<Object, Object> current = convertConversationStep(currentStep);
        current.putAll(convertHttpCalls(currentStep));
        props.put("current", current);

        Map<Object, Object> last = new HashMap<>();
        if (memory.getPreviousSteps().size() > 0) {
            IConversationMemory.IConversationStep lastStep = memory.getPreviousSteps().get(0);
            last = convertConversationStep(lastStep);
            last.putAll(convertHttpCalls(lastStep));
        }
        props.put("last", last);


        return props;
    }

    private static HashMap<Object, Object> convertConversationStep(IConversationMemory.IConversationStep conversationStep) {
        HashMap<Object, Object> ret = new HashMap<>();

        List<String> prefixKeys = getAllPrefixKeys(conversationStep.getAllKeys());
        for (String prefixKey : prefixKeys) {
            if(prefixKey.startsWith(KEY_HTTP_CALLS)) continue;
            IData data = conversationStep.getLatestData(prefixKey);
            if (data.getResult() != null) {
                ret.put(prefixKey, data.getResult());
            }
        }

        return ret;
    }

    private static HashMap<Object, Object> convertHttpCalls(IConversationMemory.IConversationStep conversationStep) {
        HashMap<Object, Object> ret = new HashMap<>();

        Set<String> prefixKeys = conversationStep.getAllKeys();
        prefixKeys.stream().filter(key -> key.startsWith(KEY_HTTP_CALLS)).forEach(prefixKey -> {
            IData data = conversationStep.getLatestData(prefixKey);
            if (data.getResult() != null) {
                HashMap<Object, Object> httpCalls = (HashMap<Object, Object>) ret.get(KEY_HTTP_CALLS);
                if (ret.get(KEY_HTTP_CALLS) == null) {
                    httpCalls = new HashMap<>();
                    ret.put(KEY_HTTP_CALLS, httpCalls);
                }
                httpCalls.put(prefixKey.substring(prefixKey.indexOf(":") + 1, prefixKey.length()), data.getResult());
            }
        });

        return ret;
    }

    private static List<String> getAllPrefixKeys(Set<String> allKeys) {
        List<String> ret = new LinkedList<>();
        for (String key : allKeys) {
            if (key.contains(":")) {
                key = key.substring(0, key.indexOf(":"));
            }

            if (!ret.contains(key)) {
                ret.add(key);
            }
        }
        return ret;
    }
}
