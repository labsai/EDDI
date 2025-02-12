package ai.labs.eddi.engine.memory;

import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.model.Context;
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
    private static final String KEY_CONVERSATION_INFO = "conversationInfo";
    private static final String KEY_CONVERSATION_ID = "conversationId";
    private static final String KEY_BOT_ID = "botId";
    private static final String KEY_BOT_VERSION = "botVersion";
    private static final String KEY_CONVERSATION_LOG = "conversationLog";

    @Override
    public Map<String, Object> convert(IConversationMemory memory) {
        Map<String, Object> conversationDataObjects = new LinkedHashMap<>();
        List<IData<Context>> contextDataList = memory.getCurrentStep().getAllData(KEY_CONTEXT);
        var contextMap = prepareContext(contextDataList);
        var memoryMap = convertMemoryItems(memory);
        var conversationProperties = memory.getConversationProperties();

        if (!contextMap.isEmpty()) {
            conversationDataObjects.put(KEY_CONTEXT, contextMap);
            conversationDataObjects.putAll(contextMap);
        }

        if (!conversationProperties.isEmpty()) {
            conversationDataObjects.put(KEY_PROPERTIES, conversationProperties.toMap());
        }

        if (!memoryMap.isEmpty()) {
            conversationDataObjects.put(KEY_MEMORY, convertMemoryItems(memory));
        }

        addInfoObject(conversationDataObjects, memory.getUserId(), KEY_USER_INFO, KEY_USER_ID);
        addInfoObject(conversationDataObjects, memory.getConversationId(), KEY_CONVERSATION_INFO, KEY_CONVERSATION_ID);
        addInfoObject(conversationDataObjects, memory.getBotId(), KEY_CONVERSATION_INFO, KEY_BOT_ID);
        addInfoObject(conversationDataObjects, memory.getBotVersion().toString(), KEY_CONVERSATION_INFO, KEY_BOT_VERSION);

        conversationDataObjects.put(KEY_CONVERSATION_LOG, new ConversationLogGenerator(memory));

        return conversationDataObjects;
    }

    private void addInfoObject(Map<String, Object> ret, String id, String keyInfo, String keyId) {
        if (!isNullOrEmpty(id)) {
            if (ret.containsKey(keyInfo)) {
                Object o = ret.get(keyInfo);
                if (o instanceof Map map) {
                    map.put(keyId, id);
                }
            } else {
                var objectMap = new HashMap<>();
                objectMap.put(keyId, id);
                ret.put(keyInfo, objectMap);
            }
        }
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
