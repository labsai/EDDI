package ai.labs.templateengine.impl;

import ai.labs.memory.IConversationMemory;
import ai.labs.memory.model.ConversationOutput;
import ai.labs.templateengine.IMemoryTemplateConverter;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

@Slf4j
public class MemoryTemplateConverter implements IMemoryTemplateConverter {

    public Map<String, Object> convert(IConversationMemory memory) {
        List<IData<Context>> contextDataList = memory.getCurrentStep().getAllData("context");
        Map<String, Object> contextMap = prepareContext(contextDataList);

        Map<String, Object> memoryForTemplate = convertMemoryForTemplating(memory);
        if (!memoryForTemplate.isEmpty()) {
            contextMap.put("memory", memoryForTemplate);
        }

        return contextMap;
    }

    private HashMap<String, Object> prepareContext(List<IData<Context>> contextDataList) {
        HashMap<String, Object> dynamicAttributesMap = new HashMap<>();
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

    @Override
    public Map<String, Object> convertMemoryForTemplating(IConversationMemory memory) {
        Map<String, Object> props = new HashMap<>();
        IConversationMemory.IWritableConversationStep currentStep;
        IConversationMemory.IConversationStep lastStep;

        currentStep = memory.getCurrentStep();
        ConversationOutput current = currentStep.getConversationOutput();
        props.put("current", current);

        ConversationOutput last = new ConversationOutput();
        if (memory.getPreviousSteps().size() > 0) {
            lastStep = memory.getPreviousSteps().get(0);
            last = lastStep.getConversationOutput();
        }
        props.put("last", last);

        List<ConversationOutput> past = memory.getConversationOutputs();
        if (past.size() > 1) {
            past = past.subList(1, past.size());
        } else {
            past = new LinkedList<>();
        }

        props.put("past", past);


        return props;
    }
}
