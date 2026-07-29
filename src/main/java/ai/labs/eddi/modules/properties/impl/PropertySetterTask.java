/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.properties.impl;

import ai.labs.eddi.configs.workflows.model.ExtensionDescriptor;
import ai.labs.eddi.configs.properties.model.Property;
import ai.labs.eddi.configs.properties.model.PropertyInstruction;
import ai.labs.eddi.configs.propertysetter.model.PropertySetterConfiguration;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.lifecycle.ILifecycleTask;
import ai.labs.eddi.engine.lifecycle.TaskId;
import ai.labs.eddi.engine.lifecycle.exceptions.LifecycleException;
import ai.labs.eddi.engine.lifecycle.exceptions.WorkflowConfigurationException;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IConversationStepStack;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
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
    public static final TaskId TASK_ID = new TaskId(ID);

    private static final Logger LOGGER = Logger.getLogger(PropertySetterTask.class);
    private static final String EXPRESSIONS_PARSED_IDENTIFIER = "expressions:parsed";
    private static final String ACTIONS_IDENTIFIER = "actions";
    private static final String CATCH_ANY_INPUT_AS_PROPERTY_ACTION = "CATCH_ANY_INPUT_AS_PROPERTY";
    private static final String INPUT_INITIAL_IDENTIFIER = "input:initial";
    /**
     * Written by {@code InputParserTask} — which is always the FIRST workflow step
     * — into the SAME conversation step, so a scrub that only rewrites
     * {@code input:initial} leaves a verbatim copy of the plaintext behind.
     */
    private static final String INPUT_NORMALIZED_IDENTIFIER = "input:normalized";
    /** Conversation-output key holding the echoed user input. */
    private static final String INPUT_OUTPUT_KEY = "input";
    /**
     * Minimum length of the punctuation/whitespace-stripped form below which a
     * containment match is too loose to act on. Guards against scrubbing a whole
     * turn because a two-character input happens to appear inside the secret.
     */
    private static final int MIN_NORMALIZED_MATCH_LENGTH = 4;
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
                    } catch (LifecycleException e) {
                        // Already a lifecycle-level failure (e.g. a fail-closed secret
                        // vaulting error) — keep its message and cause intact.
                        throw e;
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
                    if (initialInputData != null) {
                        String initialInput = initialInputData.getResult();
                        if (initialInput != null && !initialInput.isEmpty()) {
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
     * <p>
     * <b>Agent designers never see vault references for auto-vaulted secrets.</b>
     * They simply write {@code { "name": "userApiKey", "scope": "secret" }} in the
     * PropertySetter config. This method transparently vaults the user input and
     * stores a vault reference in conversation properties. Templates use
     * {@code {properties.userApiKey}} — the SecretResolver resolves transparently.
     * <p>
     * The keyName is namespaced with the agentId to prevent cross-agent collisions:
     * {@code agentId.keyName}. Since the tenant is typically "default", the
     * short-form syntax is used: {@code ${vault:agentId.keyName}}.
     *
     * @param memory
     *            the conversation memory (used for agentId and input scrubbing)
     * @param keyName
     *            the property name used as the vault key
     * @param plaintext
     *            the secret value to store
     * @return the vault reference string, e.g. {@code ${vault:69c687.userApiKey}}
     * @throws LifecycleException
     *             when the vault is unavailable or disabled. This method fails
     *             CLOSED: the raw input is scrubbed first and the plaintext is
     *             never stored as a conversation property, so a
     *             {@code scope: "secret"} property can never silently degrade to a
     *             plaintext secret persisted twice (property +
     *             {@code input:initial} ) in the conversation document.
     */
    private String autoVaultSecret(IConversationMemory memory, String keyName, String plaintext) throws LifecycleException {
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
        // Namespace with agentId to prevent cross-agent collision
        String qualifiedKeyName = agentId + "." + keyName;
        var ref = new SecretReference(tenantId, qualifiedKeyName);

        // Store the plaintext in the vault (encrypted at rest)
        try {
            secretProvider.store(ref, plaintext, "Auto-vaulted from conversation", List.of(agentId));
        } catch (ISecretProvider.SecretProviderException e) {
            // Fail CLOSED. The previous behaviour returned the plaintext, which was then
            // persisted TWICE — as a conversation property and (because the scrub below
            // was skipped) as the raw input:initial data. A disabled vault is the default
            // (eddi.vault.master-key ships empty), so that was the common path.
            // Scrub the raw input BEFORE aborting so the plaintext cannot survive in the
            // conversation document that is persisted for the failed turn either.
            scrubSecretInput(memory, keyName, plaintext);
            LOGGER.errorf("Failed to store secret in vault for property '%s': %s", keyName, e.getMessage());
            throw new LifecycleException("Cannot store property '" + keyName + "' with scope 'secret': the secrets vault is unavailable or "
                    + "disabled (set EDDI_VAULT_MASTER_KEY). Refusing to persist the value in plaintext.", e);
        }

        scrubSecretInput(memory, keyName, plaintext);

        // Return the vault reference to be stored in properties instead of plaintext
        return ref.toReferenceString();
    }

    /**
     * Removes the plaintext of a {@code scope: "secret"} property from EVERY part
     * of the current conversation step, so it cannot survive in the conversation
     * document that gets persisted for this turn (successful or aborted).
     * <p>
     * Two earlier defects this closes:
     * <ol>
     * <li><strong>Only {@code input:initial} was rewritten.</strong>
     * {@code InputParserTask} is always the first workflow step and has already
     * copied the same text into {@code input:normalized} of the same step, and
     * {@code ConversationMemoryUtilities} serializes every datum of a step
     * (committed or not) into the stored document. Both keys — plus any other
     * datum, context value or conversation-output entry that happens to carry the
     * text — are rewritten now.</li>
     * <li><strong>The scrub was gated on byte equality with
     * {@code input:initial}.</strong> The canonical {@code valueString:
     * "{memory.current.input}"} resolves to the NORMALIZED input, so as soon as any
     * parser normalizer is configured the resolved secret is not byte-identical to
     * the raw input and the scrub silently did nothing — leaking exactly what the
     * fail-closed path claims to prevent. Matching is containment-based and
     * additionally normalization-insensitive (punctuation/whitespace
     * stripped).</li>
     * </ol>
     * A scrub that finds nothing is logged at WARN (never silently ignored): the
     * value may legitimately come from a static config literal or a non-string
     * context, but if it came from the user it means the raw input is still in the
     * document.
     *
     * @param keyName
     *            property name, for the diagnostic only — never the value
     */
    private void scrubSecretInput(IConversationMemory memory, String keyName, String plaintext) {
        if (isNullOrEmpty(plaintext)) {
            return;
        }
        var currentStep = memory.getCurrentStep();
        boolean inputScrubbed = false;
        boolean anythingScrubbed = false;

        // (1) The known input-carrying keys of this step.
        for (String inputKey : List.of(INPUT_INITIAL_IDENTIFIER, INPUT_NORMALIZED_IDENTIFIER)) {
            IData<String> inputData = currentStep.getLatestData(inputKey);
            if (inputData != null && carriesSecret(inputData.getResult(), plaintext)) {
                storeScrubbed(currentStep, inputKey, SECRET_INPUT_PLACEHOLDER);
                inputScrubbed = true;
                anythingScrubbed = true;
            }
        }

        // (2) Every other datum of the step that carries the plaintext verbatim —
        // including a `context:<key>` value, which is how a client-supplied secret
        // reaches a `{context.x}` property instruction.
        for (IData<?> data : currentStep.getAllElements()) {
            String key = data.getKey();
            if (INPUT_INITIAL_IDENTIFIER.equals(key) || INPUT_NORMALIZED_IDENTIFIER.equals(key)) {
                continue;
            }
            Object result = data.getResult();
            Object cleaned = result instanceof Context context
                    ? scrubContext(context, plaintext)
                    : scrubValue(result, plaintext);
            if (cleaned != null) {
                storeScrubbed(currentStep, key, cleaned);
                anythingScrubbed = true;
            }
        }

        // (3) The conversation output of the step — the projection returned to the
        // client and stored alongside the step data.
        var conversationOutput = currentStep.getConversationOutput();
        if (conversationOutput != null) {
            for (var outputEntry : conversationOutput.entrySet()) {
                Object cleaned = scrubValue(outputEntry.getValue(), plaintext);
                if (cleaned != null) {
                    outputEntry.setValue(cleaned);
                    anythingScrubbed = true;
                }
            }
        }

        if (inputScrubbed) {
            // The echoed input is replaced wholesale rather than patched: after a
            // normalizer the echoed form need not contain the resolved secret verbatim.
            currentStep.resetConversationOutput(INPUT_OUTPUT_KEY);
            currentStep.addConversationOutputString(INPUT_OUTPUT_KEY, SECRET_INPUT_PLACEHOLDER);
        }

        if (!anythingScrubbed) {
            LOGGER.warnf("Could not locate the plaintext of scope='secret' property '%s' anywhere in the current "
                    + "conversation step — nothing was scrubbed. If the value came from user input, the raw input may "
                    + "still be persisted in the conversation document.", keyName);
        }
    }

    /**
     * Whether {@code value} carries {@code plaintext}: verbatim, or equal/contained
     * once punctuation and whitespace are stripped from both. The second form is
     * what a configured parser normalizer produces — the resolved secret is the
     * NORMALIZED input, never byte-identical to the raw one.
     */
    private static boolean carriesSecret(String value, String plaintext) {
        if (isNullOrEmpty(value)) {
            return false;
        }
        if (value.contains(plaintext)) {
            return true;
        }
        String normalizedValue = alphanumericOnly(value);
        String normalizedSecret = alphanumericOnly(plaintext);
        if (normalizedValue.length() >= MIN_NORMALIZED_MATCH_LENGTH && normalizedSecret.contains(normalizedValue)) {
            return true;
        }
        return normalizedSecret.length() >= MIN_NORMALIZED_MATCH_LENGTH && normalizedValue.contains(normalizedSecret);
    }

    /** The letters and digits of {@code value}, in order. */
    private static String alphanumericOnly(String value) {
        var builder = new StringBuilder(value.length());
        value.codePoints().filter(Character::isLetterOrDigit).forEach(builder::appendCodePoint);
        return builder.toString();
    }

    /**
     * A copy of {@code context} with the plaintext removed from its value, or
     * {@code null} when it does not carry it. A client-supplied secret arrives this
     * way ({@code valueString: "{context.apiKey}"}) and {@code Conversation} stores
     * every context entry as a step datum, so it lands in the conversation document
     * just like the input does.
     */
    private static Object scrubContext(Context context, String plaintext) {
        Object cleaned = scrubValue(context.getValue(), plaintext);
        return cleaned != null ? new Context(context.getType(), cleaned) : null;
    }

    /**
     * Returns a copy of {@code value} with every occurrence of {@code plaintext}
     * replaced, or {@code null} when it does not carry the plaintext at all (so the
     * caller can tell "nothing to do" from "replaced").
     */
    private static Object scrubValue(Object value, String plaintext) {
        if (value instanceof String text) {
            return text.contains(plaintext) ? text.replace(plaintext, SECRET_INPUT_PLACEHOLDER) : null;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list);
            boolean changed = false;
            for (int i = 0; i < copy.size(); i++) {
                Object cleaned = scrubValue(copy.get(i), plaintext);
                if (cleaned != null) {
                    copy.set(i, cleaned);
                    changed = true;
                }
            }
            return changed ? copy : null;
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            boolean changed = false;
            for (var entry : map.entrySet()) {
                Object cleaned = scrubValue(entry.getValue(), plaintext);
                copy.put(String.valueOf(entry.getKey()), cleaned != null ? cleaned : entry.getValue());
                changed |= cleaned != null;
            }
            return changed ? copy : null;
        }
        return null;
    }

    /**
     * Replaces the datum stored under {@code key} with the scrubbed value.
     * Tolerates a null from the data factory so a partially stubbed step in a unit
     * test cannot turn a security scrub into an NPE.
     */
    private void storeScrubbed(IWritableConversationStep currentStep, String key, Object value) {
        IData<Object> replacement = dataFactory.createData(key, value);
        if (replacement != null) {
            currentStep.storeData(replacement);
        }
    }
}
