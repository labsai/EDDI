package ai.labs.memory;

import ai.labs.memory.model.ConversationOutput;
import ai.labs.models.Context;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static ai.labs.memory.ConversationMemoryUtilities.prepareContext;
import static ai.labs.memory.IConversationMemory.IConversationStep;
import static ai.labs.memory.IConversationMemory.IWritableConversationStep;
import static ai.labs.utilities.RuntimeUtilities.isNullOrEmpty;

@Slf4j
public class MemoryItemConverter implements IMemoryItemConverter {
    private static final String KEY_MEMORY = "memory";
    private static final String KEY_CONTEXT = "context";
    private static final String KEY_CURRENT = "current";
    private static final String KEY_LAST = "last";
    private static final String KEY_PAST = "past";
    private static final String KEY_PROPERTIES = "properties";
    private static final String KEY_USER_INFO = "userInfo";
    private static final String KEY_USER_ID = "userId";

    @Override
    public Map<String, Object> convert(IConversationMemory memory) {
        Map<String, Object> ret = new LinkedHashMap<>();
        List<IData<Context>> contextDataList = memory.getCurrentStep().getAllData(KEY_CONTEXT);
        var contextMap = prepareContext(contextDataList);
        var memoryMap = convertMemoryItems(memory);
        var userId = memory.getUserId();
        if (!isNullOrEmpty(userId)) {
            ret.put(KEY_USER_INFO, Map.of(KEY_USER_ID, userId));
        }

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
