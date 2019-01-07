package ai.labs.memory;

import ai.labs.memory.model.ConversationOutput;
import ai.labs.models.Context;
import lombok.extern.slf4j.Slf4j;

import java.util.*;

import static ai.labs.memory.IConversationMemory.IConversationStep;
import static ai.labs.memory.IConversationMemory.IWritableConversationStep;

@Slf4j
public class MemoryItemConverter implements IMemoryItemConverter {
    private static final String KEY_MEMORY = "memory";
    private static final String KEY_CONTEXT = "context";
    private static final String KEY_CURRENT = "current";
    private static final String KEY_LAST = "last";
    private static final String KEY_PAST = "past";
    private static final String KEY_PROPERTIES = "properties";

    @Override
    public Map<String, Object> convert(IConversationMemory memory) {
        Map<String, Object> ret = new LinkedHashMap<>();
        List<IData<Context>> contextDataList = memory.getCurrentStep().getAllData(KEY_CONTEXT);
        var contextMap = prepareContext(contextDataList);
        var memoryMap = convertMemoryItems(memory);
        var conversationProperties = memory.getConversationProperties();

        if (!contextMap.isEmpty()) {
            ret.put(KEY_CONTEXT, contextMap);
            ret.putAll(contextMap);
        }

        if (!conversationProperties.isEmpty()) {
            ret.put(KEY_PROPERTIES, conversationProperties.toMap());
        }

        if (!memoryMap.isEmpty()) {
            ret.put(KEY_MEMORY, convertMemoryItems(memory));
        }

        return ret;
    }

    private static Map<String, Object> prepareContext(List<IData<Context>> contextDataList) {
        Map<String, Object> dynamicAttributesMap = new HashMap<>();
        contextDataList.forEach(contextData -> {
            Context context = contextData.getResult();
            Context.ContextType contextType = context.getType();
            if (contextType.equals(Context.ContextType.object) || contextType.equals(Context.ContextType.string)) {
                String dataKey = contextData.getKey();
                dynamicAttributesMap.put(dataKey.substring(dataKey.indexOf(":") + 1), context.getValue());
            }
        });
        return dynamicAttributesMap;
    }

    private static Map<String, Object> convertMemoryItems(IConversationMemory memory) {
        Map<String, Object> props = new HashMap<>();
        IWritableConversationStep currentStep;
        IConversationStep lastStep;

        currentStep = memory.getCurrentStep();
        var current = currentStep.getConversationOutput();
        props.put(KEY_CURRENT, current);

        var last = new ConversationOutput();
        if (memory.getPreviousSteps().size() > 0) {
            lastStep = memory.getPreviousSteps().get(0);
            last = lastStep.getConversationOutput();
        }
        props.put(KEY_LAST, last);

        var past = memory.getConversationOutputs();
        if (past.size() > 1) {
            past = past.subList(1, past.size());
        } else {
            past = new LinkedList<>();
        }

        props.put(KEY_PAST, past);


        return props;
    }
}
