package ai.labs.property.impl;

import ai.labs.expressions.Expression;
import ai.labs.expressions.utilities.IExpressionProvider;
import ai.labs.lifecycle.ILifecycleTask;
import ai.labs.lifecycle.LifecycleException;
import ai.labs.memory.IConversationMemory;
import ai.labs.memory.IData;
import ai.labs.memory.IDataFactory;
import ai.labs.memory.IMemoryItemConverter;
import ai.labs.models.Context;
import ai.labs.models.Property;
import ai.labs.models.Property.Scope;
import ai.labs.models.PropertyInstruction;
import ai.labs.property.IPropertyDisposer;
import ai.labs.property.model.PropertyEntry;
import ai.labs.resources.rest.extensions.model.ExtensionDescriptor;
import ai.labs.templateengine.ITemplatingEngine;
import ai.labs.utilities.RuntimeUtilities;
import ognl.Ognl;

import javax.inject.Inject;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static ai.labs.memory.IConversationMemory.IConversationStepStack;
import static ai.labs.models.Property.Scope.conversation;
import static ai.labs.utilities.RuntimeUtilities.isNullOrEmpty;
import static java.lang.Boolean.parseBoolean;

/**
 * @author ginccc
 */
public class PropertyDisposerTask implements ILifecycleTask {
    private static final String ID = "ai.labs.property";
    private static final String EXPRESSIONS_PARSED_IDENTIFIER = "expressions:parsed";
    private static final String ACTIONS_IDENTIFIER = "actions";
    private static final String CATCH_ANY_INPUT_AS_PROPERTY_ACTION = "CATCH_ANY_INPUT_AS_PROPERTY";
    private static final String INPUT_INITIAL_IDENTIFIER = "input:initial";
    private static final String EXPRESSION_MEANING_USER_INPUT = "user_input";
    private static final String PROPERTIES_EXTRACTED_IDENTIFIER = "properties:extracted";
    private static final String CONTEXT_IDENTIFIER = "context";
    private static final String PROPERTIES_IDENTIFIER = "properties";
    private static final String KEY_SET_ON_ACTIONS = "setOnActions";
    private static final String NAME = "name";
    private static final String VALUE = "value";
    private static final String FROM_OBJECT_PATH = "fromObjectPath";
    private static final String SCOPE = "scope";
    private static final String OVERRIDE = "override";
    private final List<SetOnActions> setOnActionsList = new LinkedList<>();
    private final IPropertyDisposer propertyDisposer;
    private final IExpressionProvider expressionProvider;
    private final IMemoryItemConverter memoryItemConverter;
    private final ITemplatingEngine templatingEngine;
    private final IDataFactory dataFactory;

    @Inject
    public PropertyDisposerTask(IPropertyDisposer propertyDisposer,
                                IExpressionProvider expressionProvider,
                                IMemoryItemConverter memoryItemConverter,
                                ITemplatingEngine templatingEngine,
                                IDataFactory dataFactory) {
        this.propertyDisposer = propertyDisposer;
        this.expressionProvider = expressionProvider;
        this.memoryItemConverter = memoryItemConverter;
        this.templatingEngine = templatingEngine;
        this.dataFactory = dataFactory;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public Object getComponent() {
        return propertyDisposer;
    }

    @Override
    public void executeTask(IConversationMemory memory) throws LifecycleException {
        IConversationMemory.IWritableConversationStep currentStep = memory.getCurrentStep();
        IData<String> expressionsData = currentStep.getLatestData(EXPRESSIONS_PARSED_IDENTIFIER);
        List<IData<Context>> contextDataList = currentStep.getAllData(CONTEXT_IDENTIFIER);
        IData<List<String>> actionsData = currentStep.getLatestData(ACTIONS_IDENTIFIER);

        if (expressionsData == null && contextDataList == null && actionsData == null) {
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

        Map<String, Object> templateDataObjects = memoryItemConverter.convert(memory);
        if (actionsData != null && !isNullOrEmpty(actionsData.getResult())) {
            for (String action : actionsData.getResult()) {
                List<PropertyInstruction> propertyInstructions = new LinkedList<>();
                setOnActionsList.forEach(setOnAction -> {
                    List<String> actions = setOnAction.getActions();
                    if (actions.contains(action) || actions.contains("*")) {
                        setOnAction.getPropertyInstruction().stream().
                                filter(propertyInstruction -> !propertyInstructions.contains(propertyInstruction)).
                                forEach(propertyInstructions::add);
                    }
                });

                if (!isNullOrEmpty(propertyInstructions)) {
                    try {
                        for (PropertyInstruction property : propertyInstructions) {
                            String name = property.getName();
                            String fromObjectPath = property.getFromObjectPath();
                            Scope scope = property.getScope();
                            boolean override = property.isOverride();
                            RuntimeUtilities.checkNotNull(name, "property.name");

                            Object templatedObj;
                            if (!isNullOrEmpty(fromObjectPath)) {
                                templatedObj = Ognl.getValue(fromObjectPath, templateDataObjects);
                            } else {
                                var value = property.getValue();

                                if (!isNullOrEmpty(value) && value instanceof String) {
                                    value = templatingEngine.processTemplate((String) value, templateDataObjects);
                                }

                                templatedObj = value;
                            }

                            if (!override && memory.getConversationProperties().containsKey(name)) {
                                continue;
                            }

                            memory.getConversationProperties().put(name, new Property(name, templatedObj, scope));
                            templateDataObjects.put(PROPERTIES_IDENTIFIER, memory.getConversationProperties().toMap());
                        }
                    } catch (Exception e) {
                        throw new LifecycleException(e.getLocalizedMessage(), e);
                    }
                }
            }
        }

        // see if action "CATCH_ANY_INPUT_AS_PROPERTY" was in the last step, so we take last user input into account
        IConversationStepStack previousSteps = memory.getPreviousSteps();
        if (previousSteps.size() > 0) {
            actionsData = previousSteps.get(0).getLatestData(ACTIONS_IDENTIFIER);
            if (actionsData != null) {
                List<String> actions = actionsData.getResult();
                if (actions != null && actions.contains(CATCH_ANY_INPUT_AS_PROPERTY_ACTION)) {
                    IData<String> initialInputData = currentStep.getLatestData(INPUT_INITIAL_IDENTIFIER);
                    String initialInput = initialInputData.getResult();
                    if (!initialInput.isEmpty()) {
                        properties.add(new PropertyEntry(
                                Collections.singletonList(EXPRESSION_MEANING_USER_INPUT), initialInput));
                    }
                }
            }
        }

        if (!properties.isEmpty()) {
            currentStep.storeData(dataFactory.createData(PROPERTIES_EXTRACTED_IDENTIFIER, properties, true));
        }
    }

    private List<Expression> extractContextProperties(List<IData<Context>> contextDataList) {
        List<Expression> ret = new LinkedList<>();
        contextDataList.forEach(contextData -> {
            String contextKey = contextData.getKey();
            Context context = contextData.getResult();
            String key = contextKey.substring((CONTEXT_IDENTIFIER + ":").length());
            if (key.startsWith(PROPERTIES_IDENTIFIER) && context.getType().equals(Context.ContextType.expressions)) {
                ret.addAll(expressionProvider.parseExpressions(context.getValue().toString()));
            }
        });

        return ret;
    }

    @Override
    public ExtensionDescriptor getExtensionDescriptor() {
        ExtensionDescriptor extensionDescriptor = new ExtensionDescriptor(ID);
        extensionDescriptor.setDisplayName("Property Extraction");
        return extensionDescriptor;
    }

    @Override
    public void configure(Map<String, Object> configuration) {
        if (configuration.containsKey(KEY_SET_ON_ACTIONS)) {
            List<Map<String, Object>> setOnActionsRaw = (List<Map<String, Object>>) configuration.get(KEY_SET_ON_ACTIONS);

            if (!isNullOrEmpty(setOnActionsRaw)) {
                for (Map<String, Object> setOnAction : setOnActionsRaw) {
                    Object actionsObj = setOnAction.get("actions");
                    SetOnActions setOnActions = new SetOnActions();
                    this.setOnActionsList.add(setOnActions);
                    if (actionsObj instanceof String) {
                        actionsObj = Collections.singletonList(actionsObj);
                    }
                    if (actionsObj instanceof List) {
                        List<String> actions = (List<String>) actionsObj;

                        setOnActions.setActions(actions);

                        Object setPropertiesObj = setOnAction.get("setProperties");
                        if (setPropertiesObj instanceof List) {
                            setOnActions.setPropertyInstruction(
                                    convertToProperties((List<Map<String, Object>>) setPropertiesObj));
                        }
                    }
                }
            }
        }
    }

    private List<PropertyInstruction> convertToProperties(List<Map<String, Object>> properties) {
        return properties.stream().map(property -> {
            PropertyInstruction propertyInstruction = new PropertyInstruction();
            if (property.containsKey(NAME)) {
                propertyInstruction.setName(property.get(NAME).toString());
            }
            if (property.containsKey(VALUE)) {
                propertyInstruction.setValue(property.get(VALUE));
            }
            if (property.containsKey(FROM_OBJECT_PATH)) {
                propertyInstruction.setFromObjectPath(property.get(FROM_OBJECT_PATH).toString());
            }
            if (property.containsKey(SCOPE)) {
                propertyInstruction.setScope(Scope.valueOf(property.getOrDefault(SCOPE, conversation).toString()));
            }

            propertyInstruction.setOverride(parseBoolean(property.getOrDefault(OVERRIDE, true).toString()));

            return propertyInstruction;
        }).collect(Collectors.toList());
    }
}
