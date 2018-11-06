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
