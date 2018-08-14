package ai.labs.templateengine.impl;

import ai.labs.memory.IConversationMemory;
import ai.labs.memory.IData;
import ai.labs.templateengine.IMemoryTemplateConverter;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

@Slf4j
public class MemoryTemplateConverter implements IMemoryTemplateConverter {
    private static final String KEY_HTTP_CALLS = "httpCalls";
    private static final String KEY_CONTEXT = "context";
    private static final String KEY_PROPERTIES = "properties";

    @Override
    public Map<String, Object> convertMemoryForTemplating(IConversationMemory memory) {
        Map<String, Object> props = new HashMap<>();
        IConversationMemory.IWritableConversationStep currentStep;
        IConversationMemory.IConversationStep lastStep;


        currentStep = memory.getCurrentStep();
        Map<Object, Object> current = collectMemoryEntries(currentStep);
        props.put("current", current);

        Map<Object, Object> last = new HashMap<>();
        if (memory.getPreviousSteps().size() > 0) {
            lastStep = memory.getPreviousSteps().get(0);
            last = collectMemoryEntries(lastStep);
        }
        props.put("last", last);


        return props;
    }

    private Map<Object, Object> collectMemoryEntries(IConversationMemory.IConversationStep conversationStep) {
        Map<Object, Object> step;
        step = convertConversationStep(conversationStep, KEY_HTTP_CALLS, KEY_CONTEXT, KEY_PROPERTIES);
        step.putAll(convertExplicitMemoryEntries(KEY_HTTP_CALLS, conversationStep));
        step.putAll(convertExplicitMemoryEntries(KEY_CONTEXT, conversationStep));
        step.putAll(convertExplicitMemoryEntries(KEY_PROPERTIES, conversationStep));
        return step;
    }

    private static HashMap<Object, Object> convertConversationStep(IConversationMemory.IConversationStep conversationStep, String... ignoredRootKeys) {
        HashMap<Object, Object> ret = new HashMap<>();

        List<String> prefixKeys = getAllPrefixKeys(conversationStep.getAllKeys());
        for (String prefixKey : prefixKeys) {

            if (Arrays.stream(ignoredRootKeys).anyMatch(prefixKey::startsWith)) {
                continue;
            }

            IData data = conversationStep.getLatestData(prefixKey);
            if (data.getResult() != null) {
                ret.put(prefixKey, data.getResult());
            }
        }

        return ret;
    }

    private static HashMap<Object, Object> convertExplicitMemoryEntries(String rootKey, IConversationMemory.IConversationStep conversationStep) {
        HashMap<Object, Object> ret = new HashMap<>();

        Set<String> prefixKeys = conversationStep.getAllKeys();
        prefixKeys.stream().filter(key -> key.startsWith(rootKey)).forEach(prefixKey -> {
            IData data = conversationStep.getLatestData(prefixKey);
            if (data.getResult() != null) {
                HashMap<Object, Object> valueEntity = (HashMap<Object, Object>) ret.get(rootKey);
                if (ret.get(rootKey) == null) {
                    valueEntity = new HashMap<>();
                    ret.put(rootKey, valueEntity);
                }
                valueEntity.put(prefixKey.substring(prefixKey.indexOf(":") + 1), data.getResult());
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
