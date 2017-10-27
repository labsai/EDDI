package ai.labs.property.impl;

import ai.labs.lifecycle.ILifecycleTask;
import ai.labs.lifecycle.LifecycleException;
import ai.labs.memory.IConversationMemory;
import ai.labs.memory.IData;
import ai.labs.memory.IDataFactory;
import ai.labs.property.IPropertyDisposer;
import ai.labs.property.model.PropertyEntry;

import javax.inject.Inject;
import java.util.Collections;
import java.util.List;

/**
 * @author ginccc
 */
public class PropertyDisposerTask implements ILifecycleTask {
    private final IPropertyDisposer propertyDisposer;
    private final IDataFactory dataFactory;

    @Inject
    public PropertyDisposerTask(IPropertyDisposer propertyDisposer,
                                IDataFactory dataFactory) {
        this.propertyDisposer = propertyDisposer;
        this.dataFactory = dataFactory;
    }

    @Override
    public String getId() {
        return PropertyDisposerTask.class.getSimpleName();
    }

    @Override
    public Object getComponent() {
        return propertyDisposer;
    }

    @Override
    public void executeTask(IConversationMemory memory) throws LifecycleException {
        IData<String> expressionsData = memory.getCurrentStep().getLatestData("expressions:parsed");

        List<PropertyEntry> properties = propertyDisposer.extractProperties(expressionsData.getResult());

        // see if action "CATCH any input as property" was in the last step
        IConversationMemory.IConversationStep lastStep = memory.getPreviousSteps().get(0);
        IData<List<String>> actionsData = lastStep.getLatestData("actions");
        List<String> actions = actionsData.getResult();
        if (actions != null && actions.contains("CATCH_ANY_INPUT_AS_PROPERTY")) {
            IData<String> initialInputData = lastStep.getLatestData("input:initial");
            String initialInput = initialInputData.getResult();
            if (!initialInput.isEmpty()) {
                properties.add(new PropertyEntry(Collections.singletonList("user_input"), initialInput));
            }
        }

        memory.getCurrentStep().storeData(dataFactory.createData("properties:extracted", properties, true));
    }
}
