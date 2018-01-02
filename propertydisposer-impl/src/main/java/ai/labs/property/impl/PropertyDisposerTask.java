package ai.labs.property.impl;

import ai.labs.expressions.Expression;
import ai.labs.expressions.utilities.IExpressionProvider;
import ai.labs.lifecycle.ILifecycleTask;
import ai.labs.lifecycle.model.Context;
import ai.labs.memory.IConversationMemory;
import ai.labs.memory.IData;
import ai.labs.memory.IDataFactory;
import ai.labs.property.IPropertyDisposer;
import ai.labs.property.model.PropertyEntry;

import javax.inject.Inject;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;

import static ai.labs.memory.IConversationMemory.IConversationStepStack;

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
    private static final String CONTEXT_IDENTIFIER = "context";
    private static final String PROPERTIES_IDENTIFIER = "properties";
    private final IPropertyDisposer propertyDisposer;
    private final IExpressionProvider expressionProvider;
    private final IDataFactory dataFactory;

    @Inject
    public PropertyDisposerTask(IPropertyDisposer propertyDisposer,
                                IExpressionProvider expressionProvider,
                                IDataFactory dataFactory) {
        this.propertyDisposer = propertyDisposer;
        this.expressionProvider = expressionProvider;
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
    public void executeTask(IConversationMemory memory) {
        IData<String> expressionsData = memory.getCurrentStep().getLatestData(EXPRESSIONS_PARSED_IDENTIFIER);
        List<IData<Context>> contextDataList = memory.getCurrentStep().getAllData(CONTEXT_IDENTIFIER);

        if (expressionsData == null && contextDataList == null) {
            return;
        }

        List<Expression> aggregatedExpressions = new LinkedList<>();

        if (contextDataList != null) {
            aggregatedExpressions.addAll(extractContextProperties(contextDataList));
        }

        if (expressionsData != null) {
            aggregatedExpressions.addAll(expressionProvider.parseExpressions(expressionsData.getResult()));
        }

        List<PropertyEntry> properties = propertyDisposer.extractProperties(aggregatedExpressions);

        // see if action "CATCH_ANY_INPUT_AS_PROPERTY" was in the last step, so we take last user input into account
        IConversationStepStack previousSteps = memory.getPreviousSteps();
        if (previousSteps.size() > 0) {
            IData<List<String>> actionsData = previousSteps.get(0).getLatestData(ACTIONS_IDENTIFIER);
            if (actionsData != null) {
                List<String> actions = actionsData.getResult();
                if (actions != null && actions.contains(CATCH_ANY_INPUT_AS_PROPERTY_ACTION)) {
                    IData<String> initialInputData = memory.getCurrentStep().getLatestData(INPUT_INITIAL_IDENTIFIER);
                    String initialInput = initialInputData.getResult();
                    if (!initialInput.isEmpty()) {
                        properties.add(new PropertyEntry(
                                Collections.singletonList(EXPRESSION_MEANING_USER_INPUT), initialInput));
                    }
                }
            }
        }

        if (!properties.isEmpty()) {
            memory.getCurrentStep().storeData(dataFactory.createData(PROPERTIES_EXTRACTED_IDENTIFIER, properties, true));
        }
    }

    private List<Expression> extractContextProperties(List<IData<Context>> contextDataList) {
        List<Expression> ret = new LinkedList<>();
        contextDataList.forEach(contextData -> {
            String contextKey = contextData.getKey();
            Context context = contextData.getResult();
            String key = contextKey.substring((CONTEXT_IDENTIFIER + ":").length(), contextKey.length());
            if (key.startsWith(PROPERTIES_IDENTIFIER) && context.getType().equals(Context.ContextType.expressions)) {
                ret.addAll(expressionProvider.parseExpressions(context.getValue().toString()));
            }
        });

        return ret;
    }
}
