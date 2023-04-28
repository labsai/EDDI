package ai.labs.eddi.engine.memory;

import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.models.Context;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.*;

import static ai.labs.eddi.engine.memory.ConversationMemoryUtilities.prepareContext;
import static ai.labs.eddi.engine.memory.IConversationMemory.IConversationStep;
import static ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

@ApplicationScoped
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

        if (!isNullOrEmpty(userId)) {
            if (ret.containsKey(KEY_USER_INFO)) {
                Object o = ret.get(KEY_USER_INFO);
                if (o instanceof Map) {
                    ((Map) o).put(KEY_USER_ID, userId);
                }
            } else {
                ret.put(KEY_USER_INFO, Map.of(KEY_USER_ID, userId));
            }
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
