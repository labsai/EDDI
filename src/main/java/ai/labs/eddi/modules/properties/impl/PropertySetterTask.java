package ai.labs.eddi.modules.properties.impl;

import ai.labs.eddi.configs.propertysetter.model.PropertySetterConfiguration;
import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.LifecycleException;
import ai.labs.eddi.engine.lifecycle.PackageConfigurationException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IConversationStepStack;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.IDataFactory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.runtime.service.ServiceException;
import ai.labs.eddi.models.*;
import ai.labs.eddi.models.Property.Scope;
import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.nlp.expressions.utilities.IExpressionProvider;
import ai.labs.eddi.modules.properties.IPropertySetter;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ognl.Ognl;

import javax.enterprise.context.RequestScoped;
import javax.inject.Inject;
import java.net.URI;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static ai.labs.eddi.models.Property.Scope.conversation;
import static ai.labs.eddi.utils.CharacterUtilities.isNumber;
import static ai.labs.eddi.utils.RuntimeUtilities.checkNotNull;
import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;
import static java.lang.Boolean.parseBoolean;

/**
 * @author ginccc
 */
@RequestScoped
public class PropertySetterTask implements ILifecycleTask {
    public static final String ID = "ai.labs.property";
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
    private static final String KEY_URI = "uri";
    private final List<SetOnActions> setOnActionsList = new LinkedList<>();
    private final IPropertySetter propertySetter;
    private final IExpressionProvider expressionProvider;
    private final IMemoryItemConverter memoryItemConverter;
    private final ITemplatingEngine templatingEngine;
    private final IDataFactory dataFactory;
    private final IResourceClientLibrary resourceClientLibrary;
    private final ObjectMapper objectMapper;

    @Inject
    public PropertySetterTask(IPropertySetter propertySetter,
                              IExpressionProvider expressionProvider,
                              IMemoryItemConverter memoryItemConverter,
                              ITemplatingEngine templatingEngine,
                              IDataFactory dataFactory,
                              IResourceClientLibrary resourceClientLibrary,
                              ObjectMapper objectMapper) {
        this.propertySetter = propertySetter;
        this.expressionProvider = expressionProvider;
        this.memoryItemConverter = memoryItemConverter;
        this.templatingEngine = templatingEngine;
        this.dataFactory = dataFactory;
        this.resourceClientLibrary = resourceClientLibrary;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getType() {
        return PROPERTIES_IDENTIFIER;
    }

    @Override
    public Object getComponent() {
        return propertySetter;
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
            for (String action : actionsData.getResult()) {
                var propertyInstructions = new LinkedList<PropertyInstruction>();
                setOnActionsList.forEach(setOnAction -> {
                    List<String> actions = setOnAction.getActions();
                    if (actions.contains(action) || actions.contains("*")) {
                        setOnAction.getSetProperties().stream().
                                filter(propertyInstruction -> !propertyInstructions.contains(propertyInstruction)).
                                forEach(propertyInstructions::add);
                    }
                });

                if (!isNullOrEmpty(propertyInstructions)) {
                    try {
                        for (PropertyInstruction property : propertyInstructions) {
                            var name = property.getName();
                            checkNotNull(name, "property.name");
                            var fromObjectPath = property.getFromObjectPath();
                            var toObjectPath = property.getToObjectPath();
                            var scope = property.getScope();
                            name = templatingEngine.processTemplate(name, templateDataObjects);

                            Object templatedObj = null;
                            if (!isNullOrEmpty(fromObjectPath)) {
                                templatedObj = Ognl.getValue(fromObjectPath, templateDataObjects);
                                if (!isNullOrEmpty(templatedObj) && !isNullOrEmpty(toObjectPath)) {
                                    Ognl.setValue(toObjectPath, templateDataObjects, templatedObj);
                                }
                            } else {
                                var value = property.getValue();
                                if (!isNullOrEmpty(value) && value instanceof String) {
                                    value = templatingEngine.processTemplate((String) value, templateDataObjects);
                                }

                                if (value != null) {
                                    var valueString = value.toString();
                                    if (isNumber(valueString, false)) {
                                        if (isNumber(valueString, true)) {
                                            templatedObj = Double.parseDouble(valueString);
                                        } else {
                                            templatedObj = Integer.parseInt(valueString);
                                        }
                                    } else {
                                        templatedObj = value;
                                    }
                                }
                            }

                            if (!conversationProperties.containsKey(name) || property.getOverride()) {
                                conversationProperties.put(name, new Property(name, templatedObj, scope));
                                templateDataObjects.put(PROPERTIES_IDENTIFIER, conversationProperties.toMap());
                            }
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
                        properties.add(new Property(EXPRESSION_MEANING_USER_INPUT, initialInput, conversation));
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
        ExtensionDescriptor extensionDescriptor = new ExtensionDescriptor(ID);
        extensionDescriptor.setDisplayName("Property Extraction");
        return extensionDescriptor;
    }

    @Override
    public void configure(Map<String, Object> configuration) throws PackageConfigurationException {
        if (configuration.containsKey(KEY_SET_ON_ACTIONS)) {
            parseRawConfig(configuration);
        }

        if (configuration.containsKey(KEY_URI)) {
            try {
                Object uriObj = configuration.get(KEY_URI);
                if (!isNullOrEmpty(uriObj) && uriObj.toString().startsWith("eddi")) {
                    URI uri = URI.create(uriObj.toString());
                    var propertySetterConfig =
                            resourceClientLibrary.getResource(uri, PropertySetterConfiguration.class);
                    var setOnActions = propertySetterConfig.getSetOnActions();
                    this.setOnActionsList.addAll(setOnActions);
                } else {
                    throw new ServiceException("No resource URI has been defined! [PropertySetterConfiguration]");
                }
            } catch (ServiceException e) {
                String message = "Error while fetching PropertySetterConfiguration!\n" + e.getLocalizedMessage();
                throw new PackageConfigurationException(message, e);
            }
        }
    }

    private void parseRawConfig(Map<String, Object> configuration) {
        var setOnActionsRaw = convertObjectToListOfMapsWithObjects(configuration.get(KEY_SET_ON_ACTIONS));

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
                        setOnActions.setSetProperties(
                                convertToProperties(convertObjectToListOfMapsWithObjects(setPropertiesObj)));
                    }
                }

                this.setOnActionsList.add(setOnActions);
            }
        }
    }

    private List<String> convertObjectToList(Object actionsObj) {
        return objectMapper.convertValue(actionsObj, new TypeReference<>() {});
    }

    private List<Map<String, Object>> convertObjectToListOfMapsWithObjects(Object object) {
        return objectMapper.convertValue(object, new TypeReference<>() {});
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
