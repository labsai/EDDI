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
    private static final String EXPRESSIONS_PARSED_IDENTIFIER = "expressions:parsed";
    private static final String ACTIONS_IDENTIFIER = "actions";
    private static final String CATCH_ANY_INPUT_AS_PROPERTY_ACTION = "CATCH_ANY_INPUT_AS_PROPERTY";
    private static final String INPUT_INITIAL_IDENTIFIER = "input:initial";
    private static final String EXPRESSION_MEANING_USER_INPUT = "user_input";
    private static final String PROPERTIES_EXTRACTED_IDENTIFIER = "properties:extracted";
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
        IData<String> expressionsData = memory.getCurrentStep().getLatestData(EXPRESSIONS_PARSED_IDENTIFIER);

        if (expressionsData == null) {
            return;
        }
        List<PropertyEntry> properties = propertyDisposer.extractProperties(expressionsData.getResult());

        // see if action "CATCH_ANY_INPUT_AS_PROPERTY" was in the last step, so we take last user input into account
        IConversationMemory.IConversationStep lastStep = memory.getPreviousSteps().get(0);
        IData<List<String>> actionsData = lastStep.getLatestData(ACTIONS_IDENTIFIER);
        List<String> actions = actionsData.getResult();
        if (actions != null && actions.contains(CATCH_ANY_INPUT_AS_PROPERTY_ACTION)) {
            IData<String> initialInputData = lastStep.getLatestData(INPUT_INITIAL_IDENTIFIER);
            String initialInput = initialInputData.getResult();
            if (!initialInput.isEmpty()) {
                properties.add(new PropertyEntry(Collections.singletonList(EXPRESSION_MEANING_USER_INPUT), initialInput));
            }
        }

        memory.getCurrentStep().storeData(dataFactory.createData(PROPERTIES_EXTRACTED_IDENTIFIER, properties, true));
    }
}
