package ai.labs.eddi.modules.properties.impl;

import ai.labs.eddi.configs.workflows.model.ExtensionDescriptor;
import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.configs.properties.model.PropertyInstruction;
import ai.labs.eddi.configs.propertysetter.model.PropertySetterConfiguration;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
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
import ai.labs.eddi.secrets.ISecretProvider;
import ai.labs.eddi.secrets.model.SecretReference;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ai.labs.eddi.utils.PathNavigator;

import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.net.URI;
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
    private static final Logger LOGGER = Logger.getLogger(PropertySetterTask.class);
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
    private static final String VALUE_BOOLEAN = "valueBoolean";
    private static final String FROM_OBJECT_PATH = "fromObjectPath";
    private static final String SCOPE = "scope";
    private static final String OVERRIDE = "override";
    private static final String KEY_URI = "uri";
    private static final String SECRET_INPUT_PLACEHOLDER = "<secret input>";
    private final IExpressionProvider expressionProvider;
    private final IMemoryItemConverter memoryItemConverter;
    private final ITemplatingEngine templatingEngine;
    private final IDataFactory dataFactory;
    private final IResourceClientLibrary resourceClientLibrary;
    private final ObjectMapper objectMapper;
    private final ISecretProvider secretProvider;

    @Inject
    public PropertySetterTask(IExpressionProvider expressionProvider, IMemoryItemConverter memoryItemConverter, ITemplatingEngine templatingEngine,
            IDataFactory dataFactory, IResourceClientLibrary resourceClientLibrary, ObjectMapper objectMapper, ISecretProvider secretProvider) {
        this.expressionProvider = expressionProvider;
        this.memoryItemConverter = memoryItemConverter;
        this.templatingEngine = templatingEngine;
        this.dataFactory = dataFactory;
        this.resourceClientLibrary = resourceClientLibrary;
        this.objectMapper = objectMapper;
        this.secretProvider = secretProvider;
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
                            var name = property.getName();
                            checkNotNull(name, "property.name");
                            var fromObjectPath = property.getFromObjectPath();
                            var toObjectPath = property.getToObjectPath();
                            var scope = property.getScope();
                            name = templatingEngine.processTemplate(name, templateDataObjects);

                            String templateString;
                            Object templatedObj;
                            if (!conversationProperties.containsKey(name) || property.getOverride()) {
                                if (!isNullOrEmpty(fromObjectPath)) {
                                    templatedObj = PathNavigator.getValue(fromObjectPath, templateDataObjects);
                                    if (!isNullOrEmpty(toObjectPath)) {
                                        PathNavigator.setValue(toObjectPath, templateDataObjects, templatedObj);
                                    } else if (templatedObj instanceof String) {
                                        templateString = templatingEngine.processTemplate(templatedObj.toString(), templateDataObjects);
                                        conversationProperties.put(name, new Property(name, templateString, scope));
                                    } else if (templatedObj instanceof Map<?, ?>) {
                                        @SuppressWarnings("unchecked")
                                        var valueMap = (Map<String, Object>) templatedObj;
                                        conversationProperties.put(name, new Property(name, new LinkedHashMap<>(valueMap), scope));
                                    } else if (templatedObj instanceof List<?>) {
                                        @SuppressWarnings("unchecked")
                                        var valueList = (List<Object>) templatedObj;
                                        conversationProperties.put(name, new Property(name, new ArrayList<>(valueList), scope));
                                    } else if (templatedObj instanceof Integer valueInt) {
                                        conversationProperties.put(name, new Property(name, valueInt, scope));
                                    } else if (templatedObj instanceof Float valueFloat) {
                                        conversationProperties.put(name, new Property(name, valueFloat, scope));
                                    } else if (templatedObj instanceof Boolean valueBoolean) {
                                        conversationProperties.put(name, new Property(name, valueBoolean, scope));
                                    }
                                } else {
                                    var valueString = property.getValueString();
                                    if (!isNullOrEmpty(valueString)) {
                                        templateString = templatingEngine.processTemplate(valueString, templateDataObjects);
                                        if (scope == Scope.secret) {
                                            // Auto-vault: store the plaintext in the vault and
                                            // replace it with a vault reference in conversation properties.
                                            templateString = autoVaultSecret(memory, name, templateString);
                                            // Store as conversation-scoped (the vault ref, not the plaintext)
                                            conversationProperties.put(name, new Property(name, templateString, conversation));
                                        } else {
                                            // NOTE: Do NOT resolve vault references here — they must stay as-is
                                            // in conversation properties (which are persisted to DB).
                                            // Vault refs are resolved at point-of-use by downstream consumers
                                            // (ChatModelRegistry, ApiCallExecutor) to prevent secret leakage.
                                            conversationProperties.put(name, new Property(name, templateString, scope));
                                        }
                                    }

                                    var valueMap = property.getValueObject();
                                    if (valueMap != null) {
                                        conversationProperties.put(name, new Property(name, new LinkedHashMap<>(valueMap), scope));
                                    }

                                    var valueList = property.getValueList();
                                    if (valueList != null) {
                                        conversationProperties.put(name, new Property(name, new ArrayList<>(valueList), scope));
                                    }

                                    var valueInt = property.getValueInt();
                                    if (valueInt != null) {
                                        conversationProperties.put(name, new Property(name, valueInt, scope));
                                    }

                                    var valueFloat = property.getValueFloat();
                                    if (valueFloat != null) {
                                        conversationProperties.put(name, new Property(name, valueFloat, scope));
                                    }

                                    var valueBoolean = property.getValueBoolean();
                                    if (valueBoolean != null) {
                                        conversationProperties.put(name, new Property(name, valueBoolean, scope));
                                    }
                                }

                                templateDataObjects.put(PROPERTIES_IDENTIFIER, conversationProperties.toMap());
                            }
                        }
                    } catch (Exception e) {
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
            } else if (property.containsKey(VALUE_INT) && property.get(VALUE_INT) instanceof Integer i) {
                propertyInstruction.setValueInt(i);
            } else if (property.containsKey(VALUE_FLOAT) && property.get(VALUE_FLOAT) instanceof Float f) {
                propertyInstruction.setValueFloat(f);
            } else if (property.containsKey(VALUE_BOOLEAN) && property.get(VALUE_BOOLEAN) instanceof Boolean b) {
                propertyInstruction.setValueBoolean(b);
            }

            if (property.containsKey(FROM_OBJECT_PATH)) {
                propertyInstruction.setFromObjectPath(property.get(FROM_OBJECT_PATH).toString());
            }
            if (property.containsKey(SCOPE)) {
                propertyInstruction.setScope(Scope.valueOf(property.getOrDefault(SCOPE, conversation).toString()));
            }

            propertyInstruction.setOverride(parseBoolean(property.getOrDefault(OVERRIDE, true).toString()));

            return propertyInstruction;
        }).toList();
    }

    /**
     * Store a plaintext secret in the vault and return the vault reference string.
     * Also scrubs the raw user input from conversation memory to prevent leakage.
     *
     * @param memory
     *            the conversation memory (used for agentId and input scrubbing)
     * @param keyName
     *            the property name used as the vault key
     * @param plaintext
     *            the secret value to store
     * @return the vault reference string, e.g.
     *         {@code ${eddivault:default/agentId/keyName}}
     */
    private String autoVaultSecret(IConversationMemory memory, String keyName, String plaintext) {
        // Determine tenantId — use conversation property if set, else "default"
        var conversationProperties = memory.getConversationProperties();
        String tenantId = "default";
        if (conversationProperties.containsKey("tenantId")) {
            Property tenantProp = conversationProperties.get("tenantId");
            if (tenantProp.getValueString() != null) {
                tenantId = tenantProp.getValueString();
            }
        }

        String agentId = memory.getAgentId();
        var ref = new SecretReference(tenantId, agentId, keyName);

        // Store the plaintext in the vault (encrypted at rest)
        try {
            secretProvider.store(ref, plaintext);
        } catch (ISecretProvider.SecretProviderException e) {
            // If vault storage fails, log and return the plaintext as-is (degraded mode).
            // This prevents the PropertySetter from breaking the workflow.
            LOGGER.error("Failed to store secret in vault for key '" + keyName + "': " + e.getMessage());
            return plaintext;
        }

        // Scrub the raw user input from conversation memory so the plaintext
        // doesn't persist in the DB as part of the conversation history.
        var currentStep = memory.getCurrentStep();
        IData<String> inputData = currentStep.getLatestData(INPUT_INITIAL_IDENTIFIER);
        if (inputData != null && plaintext.equals(inputData.getResult())) {
            currentStep.storeData(dataFactory.createData(INPUT_INITIAL_IDENTIFIER, SECRET_INPUT_PLACEHOLDER));
            currentStep.resetConversationOutput("input");
            currentStep.addConversationOutputString("input", SECRET_INPUT_PLACEHOLDER);
        }

        // Return the vault reference to be stored in properties instead of plaintext
        return ref.toReferenceString();
    }
}
