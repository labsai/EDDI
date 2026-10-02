/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.properties.impl;

import ai.labs.eddi.configs.workflows.model.ExtensionDescriptor;
import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.configs.properties.model.PropertyInstruction;
import ai.labs.eddi.configs.properties.model.PropertyValues;
import ai.labs.eddi.configs.propertysetter.model.PropertySetterConfiguration;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.TaskId;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.lifecycle.exceptions.WorkflowConfigurationException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IConversationStepStack;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IDataFactory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.configs.properties.model.Property.Scope;
import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.nlp.expressions.utilities.IExpressionProvider;
import ai.labs.eddi.modules.properties.IPropertySetter;
import ai.labs.eddi.modules.properties.model.SetOnActions;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URI;
import java.math.BigInteger;
import java.util.*;

import static ai.labs.eddi.configs.properties.model.Property.Scope.conversation;
import static ai.labs.eddi.utils.RuntimeUtilities.checkNotNull;
import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;
import static java.lang.Boolean.parseBoolean;

/**
 * @author ginccc
 */
@ApplicationScoped
public class PropertySetterTask implements ILifecycleTask {
    public static final String ID = "ai.labs.property";
    public static final TaskId TASK_ID = new TaskId(ID);

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
    private static final String VALUE_STRING = "valueString";
    private static final String VALUE_OBJECT = "valueObject";
    private static final String VALUE_LIST = "valueList";
    private static final String VALUE_INT = "valueInt";
    private static final String VALUE_FLOAT = "valueFloat";
    private static final String VALUE_LONG = "valueLong";
    private static final String VALUE_DOUBLE = "valueDouble";
    private static final String VALUE_BOOLEAN = "valueBoolean";
    private static final String FROM_OBJECT_PATH = "fromObjectPath";
    private static final String TO_OBJECT_PATH = "toObjectPath";
    private static final String CONVERT_TO_OBJECT = "convertToObject";
    private static final String SCOPE = "scope";
    private static final String OVERRIDE = "override";
    private static final String VISIBILITY = "visibility";
    private static final String KEY_URI = "uri";
    private final IExpressionProvider expressionProvider;
    private final IMemoryItemConverter memoryItemConverter;
    private final IDataFactory dataFactory;
    private final IResourceClientLibrary resourceClientLibrary;
    private final ObjectMapper objectMapper;
    private final PropertyInstructionExecutor propertyInstructionExecutor;

    @Inject
    public PropertySetterTask(IExpressionProvider expressionProvider, IMemoryItemConverter memoryItemConverter, ITemplatingEngine templatingEngine,
            IDataFactory dataFactory, IResourceClientLibrary resourceClientLibrary, ObjectMapper objectMapper,
            SecretPropertyVault secretPropertyVault) {
        this.expressionProvider = expressionProvider;
        this.memoryItemConverter = memoryItemConverter;
        this.dataFactory = dataFactory;
        this.resourceClientLibrary = resourceClientLibrary;
        this.objectMapper = objectMapper;
        this.propertyInstructionExecutor = new PropertyInstructionExecutor(templatingEngine, secretPropertyVault,
                json -> objectMapper.readValue(json, Object.class));
    }

    @Override
    public TaskId getId() {
        return TASK_ID;
    }

    @Override
    public String getType() {
        return PROPERTIES_IDENTIFIER;
    }

    @Override
    public void execute(IConversationMemory memory, Object component) throws LifecycleException {
        final var propertySetter = (IPropertySetter) component;

        IConversationMemory.IWritableConversationStep currentStep = memory.getCurrentStep();
        IData<String> expressionsData = currentStep.getLatestData(EXPRESSIONS_PARSED_IDENTIFIER);
        List<IData<Context>> contextDataList = currentStep.getAllData(CONTEXT_IDENTIFIER);
        IData<List<String>> actionsData = currentStep.getLatestData(ACTIONS_IDENTIFIER);

        if (expressionsData == null && contextDataList == null && actionsData == null) {
            return;
        }

        Expressions aggregatedExpressions = new Expressions();

        if (contextDataList != null) {
            aggregatedExpressions.addAll(extractContextProperties(contextDataList));
        }

        if (expressionsData != null) {
            aggregatedExpressions.addAll(expressionProvider.parseExpressions(expressionsData.getResult()));
        }

        var properties = propertySetter.extractProperties(aggregatedExpressions);

        var templateDataObjects = memoryItemConverter.convert(memory);
        var conversationProperties = memory.getConversationProperties();
        if (actionsData != null && !isNullOrEmpty(actionsData.getResult())) {
            var setOnActionsList = propertySetter.getSetOnActionsList();
            for (String action : actionsData.getResult()) {
                var propertyInstructions = new LinkedList<PropertyInstruction>();
                setOnActionsList.forEach(setOnAction -> {
                    List<String> actions = setOnAction.getActions();
                    if (actions.contains(action) || actions.contains("*")) {
                        setOnAction.getSetProperties().stream().filter(propertyInstruction -> !propertyInstructions.contains(propertyInstruction))
                                .forEach(propertyInstructions::add);
                    }
                });

                if (!isNullOrEmpty(propertyInstructions)) {
                    try {
                        for (PropertyInstruction property : propertyInstructions) {
                            checkNotNull(property.getName(), "property.name");
                            // One implementation for property.json and every postResponse /
                            // preRequest instruction — see PropertyInstructionExecutor.
                            propertyInstructionExecutor.apply(property, memory, templateDataObjects);
                        }
                    } catch (LifecycleException e) {
                        // Already a lifecycle-level failure — keep its message and cause intact.
                        throw e;
                    } catch (Exception e) {
                        // Including a fail-closed secret vaulting error: the turn fails rather
                        // than the value being persisted in plaintext.
                        throw new LifecycleException(e.getLocalizedMessage(), e);
                    }
                }
            }
        }

        // see if action "CATCH_ANY_INPUT_AS_PROPERTY" was in the last step, so we take
        // last user input into account
        IConversationStepStack previousSteps = memory.getPreviousSteps();
        if (previousSteps.size() > 0) {
            actionsData = previousSteps.get(0).getLatestData(ACTIONS_IDENTIFIER);
            if (actionsData != null) {
                List<String> actions = actionsData.getResult();
                if (actions != null && actions.contains(CATCH_ANY_INPUT_AS_PROPERTY_ACTION)) {
                    IData<String> initialInputData = currentStep.getLatestData(INPUT_INITIAL_IDENTIFIER);
                    if (initialInputData != null) {
                        String initialInput = initialInputData.getResult();
                        if (initialInput != null && !initialInput.isEmpty()
                                && !PropertyInstructionExecutor.isScrubbedPlaceholder(EXPRESSION_MEANING_USER_INPUT, initialInput, conversation)) {
                            properties.add(new Property(EXPRESSION_MEANING_USER_INPUT, initialInput, conversation));
                        }
                    }
                }
            }
        }

        if (!properties.isEmpty()) {
            currentStep.storeData(dataFactory.createData(PROPERTIES_EXTRACTED_IDENTIFIER, properties, true));
            properties.forEach(property -> conversationProperties.put(property.getName(), property));
        }
    }

    private Expressions extractContextProperties(List<IData<Context>> contextDataList) {
        Expressions ret = new Expressions();
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
        ExtensionDescriptor extensionDescriptor = new ExtensionDescriptor(new TaskId(ID));
        extensionDescriptor.setDisplayName("Property Extraction");
        return extensionDescriptor;
    }

    @Override
    public Object configure(Map<String, Object> configuration, Map<String, Object> extensions) throws WorkflowConfigurationException {

        List<SetOnActions> setOnActionsList = new LinkedList<>();

        if (configuration.containsKey(KEY_SET_ON_ACTIONS)) {
            setOnActionsList.addAll(parseRawConfig(configuration));
        }

        try {
            if (configuration.containsKey(KEY_URI)) {
                Object uriObj = configuration.get(KEY_URI);
                if (!isNullOrEmpty(uriObj) && uriObj.toString().startsWith("eddi")) {
                    URI uri = URI.create(uriObj.toString());
                    var propertySetterConfig = resourceClientLibrary.getResource(uri, PropertySetterConfiguration.class);
                    setOnActionsList.addAll(propertySetterConfig.getSetOnActions());
                }
            }
        } catch (ServiceException e) {
            String message = "Error while fetching PropertySetterConfiguration!\n" + e.getLocalizedMessage();
            throw new WorkflowConfigurationException(message, e);
        }

        return new PropertySetter(new LinkedList<>(setOnActionsList));
    }

    private List<SetOnActions> parseRawConfig(Map<String, Object> configuration) {
        var setOnActionsRaw = convertObjectToListOfMapsWithObjects(configuration.get(KEY_SET_ON_ACTIONS));

        List<SetOnActions> setOnActionsList = new LinkedList<>();
        if (!isNullOrEmpty(setOnActionsRaw)) {
            for (Map<String, Object> setOnAction : setOnActionsRaw) {
                Object actionsObj = setOnAction.get("actions");
                SetOnActions setOnActions = new SetOnActions();

                if (actionsObj instanceof String) {
                    actionsObj = Collections.singletonList(actionsObj);
                }
                if (actionsObj instanceof List) {
                    List<String> actions = convertObjectToList(actionsObj);

                    setOnActions.setActions(actions);

                    Object setPropertiesObj = setOnAction.get("setProperties");
                    if (setPropertiesObj instanceof List) {
                        setOnActions.setSetProperties(convertToProperties(convertObjectToListOfMapsWithObjects(setPropertiesObj)));
                    }
                }

                setOnActionsList.add(setOnActions);
            }
        }

        return setOnActionsList;
    }

    private List<String> convertObjectToList(Object actionsObj) {
        return objectMapper.convertValue(actionsObj, new TypeReference<>() {
        });
    }

    private List<Map<String, Object>> convertObjectToListOfMapsWithObjects(Object object) {
        return objectMapper.convertValue(object, new TypeReference<>() {
        });
    }

    private List<PropertyInstruction> convertToProperties(List<Map<String, Object>> properties) {
        return properties.stream().map(property -> {
            PropertyInstruction propertyInstruction = new PropertyInstruction();
            if (property.containsKey(NAME)) {
                propertyInstruction.setName(property.get(NAME).toString());
            }
            if (property.containsKey(VALUE_STRING)) {
                var o = property.get(VALUE_STRING);
                propertyInstruction.setValueString(o.toString());
            } else if (property.containsKey(VALUE_OBJECT) && property.get(VALUE_OBJECT) instanceof Map<?, ?>) {
                @SuppressWarnings("unchecked")
                var m = (Map<String, Object>) property.get(VALUE_OBJECT);
                propertyInstruction.setValueObject(m);
            } else if (property.containsKey(VALUE_LIST) && property.get(VALUE_LIST) instanceof List<?>) {
                @SuppressWarnings("unchecked")
                var l = (List<Object>) property.get(VALUE_LIST);
                propertyInstruction.setValueList(l);
            } else if (property.get(VALUE_INT) instanceof Number n) {
                // Number, not Integer: an inline config arrives through Jackson, which
                // reads 3 as an Integer but 3.0 or 3000000000 as something else, and the
                // value was dropped without a word. A whole number beyond the int range
                // goes to valueLong — intValue() would wrap it to a different number.
                if (n instanceof BigInteger big && big.bitLength() >= 64) {
                    throw new IllegalArgumentException("valueInt of property '" + propertyInstruction.getName() + "' is beyond the long range: " + n);
                }
                long whole = n.longValue();
                if (whole >= Integer.MIN_VALUE && whole <= Integer.MAX_VALUE) {
                    propertyInstruction.setValueInt((int) whole);
                } else {
                    propertyInstruction.setValueLong(whole);
                }
            } else if (property.get(VALUE_LONG) instanceof Number n) {
                propertyInstruction.setValueLong(n.longValue());
            } else if (property.get(VALUE_FLOAT) instanceof Number n) {
                // Jackson reads 1.5 as a Double, which "instanceof Float" never matched.
                propertyInstruction.setValueFloat(n.floatValue());
            } else if (property.get(VALUE_DOUBLE) instanceof Number n) {
                propertyInstruction.setValueDouble(n.doubleValue());
            } else if (property.containsKey(VALUE_BOOLEAN) && property.get(VALUE_BOOLEAN) instanceof Boolean b) {
                propertyInstruction.setValueBoolean(b);
            }

            if (property.containsKey(FROM_OBJECT_PATH)) {
                propertyInstruction.setFromObjectPath(property.get(FROM_OBJECT_PATH).toString());
            }
            if (property.get(TO_OBJECT_PATH) != null) {
                propertyInstruction.setToObjectPath(property.get(TO_OBJECT_PATH).toString());
            }
            if (property.get(CONVERT_TO_OBJECT) != null) {
                propertyInstruction.setConvertToObject(parseBoolean(property.get(CONVERT_TO_OBJECT).toString()));
            }
            if (property.containsKey(SCOPE)) {
                propertyInstruction.setScope(Scope.valueOf(property.getOrDefault(SCOPE, conversation).toString()));
            }

            if (property.get(VISIBILITY) != null) {
                String visibility = property.get(VISIBILITY).toString().trim();
                try {
                    propertyInstruction.setVisibility(Property.Visibility.valueOf(visibility));
                } catch (IllegalArgumentException e) {
                    throw new IllegalArgumentException("Invalid visibility '" + visibility + "' for property '" + propertyInstruction.getName()
                            + "' — expected self, group or global", e);
                }
            }

            propertyInstruction.setOverride(parseBoolean(property.getOrDefault(OVERRIDE, true).toString()));

            return propertyInstruction;
        }).toList();
    }

    /** Whether the instruction sets any value field other than valueString. */
    static boolean hasTypedValue(PropertyInstruction property) {
        return PropertyValues.hasTypedValue(property);
    }
}
