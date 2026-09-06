/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.migration;

import ai.labs.eddi.modules.output.model.types.AgentFaceOutputItem;
import ai.labs.eddi.modules.output.model.types.TextOutputItem;
import org.bson.Document;
import org.jboss.logging.Logger;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;
import static java.lang.Boolean.parseBoolean;

/**
 * The v5 → v6 document transforms, as backend-neutral {@link Document} →
 * {@link Document} functions with no storage backend behind them.
 * <p>
 * <strong>They mutate the {@link Document} they are given</strong>, in place,
 * along with the nested lists and maps inside it — the return value is the same
 * instance, or {@code null} to mean "nothing needed changing". Pass a freshly
 * deserialized, mutable document: an immutable one throws, and a shared one is
 * visibly rewritten under whoever else is holding it. Both callers satisfy this
 * today, {@code MigrationManager} from a Mongo cursor and
 * {@code RestImportService} from a just-parsed ZIP entry.
 * <p>
 * They live here rather than on {@link MigrationManager} because they have two
 * callers with different lifetimes. {@code MigrationManager} runs them once, as
 * a MongoDB collection sweep at first startup — that part genuinely is
 * Mongo-specific. {@code RestImportService} runs the same three transforms on
 * every uploaded agent ZIP, and a 5.x ZIP is just as legacy-shaped whether the
 * deployment stores it in MongoDB or in PostgreSQL.
 * <p>
 * Keeping them on the backend-selected {@code IMigrationManager} bean meant
 * {@code PostgresMigrationManager} answered {@code document -> null} for all
 * three, so the identical ZIP imported differently per backend: a legacy output
 * set threw {@code InvalidTypeIdException} (missing type id) instead of being
 * upgraded, and a legacy {@code targetServer} was silently dropped, leaving
 * every HTTP call in the agent without a base URL. Both
 * {@code IMigrationManager} implementations now delegate here, so the two
 * backends cannot drift apart again.
 */
public final class LegacyDocumentMigrations {

    private static final Logger LOGGER = Logger.getLogger(LegacyDocumentMigrations.class);

    static final String FIELD_NAME_HTTP_CALLS = "httpCalls";
    static final String FIELD_NAME_OUTPUTS = "outputs";
    static final String FIELD_NAME_OUTPUT_SET = "outputSet";
    static final String FIELD_NAME_SET_PROPERTIES = "setProperties";
    static final String FIELD_NAME_PRE_REQUEST = "preRequest";
    static final String FIELD_NAME_POST_RESPONSE = "postResponse";
    static final String FIELD_NAME_PROPERTY_INSTRUCTIONS = "propertyInstructions";
    static final String FIELD_NAME_TYPE = "type";
    static final String FIELD_NAME_VALUE_ALTERNATIVES = "valueAlternatives";
    static final String FIELD_NAME_TEXT = "text";
    static final String FIELD_NAME_URI = "uri";
    static final String FIELD_NAME_IMAGE = "image";
    static final String FIELD_NAME_EXPRESSIONS = "expressions";
    static final String FIELD_NAME_QUICK_REPLY = "quickReply";
    static final String FIELD_NAME_OTHER = "other";
    static final String FIELD_NAME_VALUE_STRING = "valueString";
    static final String FIELD_NAME_VALUE_OBJECT = "valueObject";
    static final String FIELD_NAME_VALUE_INT = "valueInt";
    static final String FIELD_NAME_VALUE_FLOAT = "valueFloat";
    static final String FIELD_NAME_VALUE_LIST = "valueList";
    static final String FIELD_NAME_VALUE_BOOLEAN = "valueBoolean";
    static final String FIELD_NAME_VALUE = "value";
    static final String FIELD_NAME_SET_ON_ACTIONS = "setOnActions";
    static final String FIELD_NAME_CONVERSATION_PROPERTIES = "conversationProperties";
    static final String FIELD_NAME_BUTTON = "button";
    static final String FIELD_NAME_LABEL = "label";
    static final String FIELD_NAME_DEFAULT_VALUE = "defaultValue";
    static final String FIELD_NAME_PLACEHOLDER = "placeholder";
    static final String FIELD_NAME_BUTTON_TYPE = "buttonType";
    static final String FIELD_NAME_ON_PRESS = "onPress";
    static final String FIELD_NAME_INPUT_FIELD = "inputField";
    static final String FIELD_NAME_ALT = "alt";
    static final String FIELD_NAME_DELAY = "delay";
    static final String FIELD_NAME_TARGET_SERVER_URL = "targetServerUrl";
    static final String OLD_FIELD_NAME_TARGET_SERVER = "targetServer";
    static final String FIELD_NAME_VALIDATION = "validation";
    static final String FIELD_NAME_SUB_TYPE = "subType";
    static final String OLD_FIELD_NAME_IS_PASSWORD = "isPassword";
    private static final String LEGACY_UUID_EXPRESSION = "[[${@java.util.UUID@randomUUID()}]]";
    private static final String QUTE_UUID_EXPRESSION = "{uuidUtils:generateUUID()}";

    private LegacyDocumentMigrations() {
        // static transforms only
    }

    @SuppressWarnings("unchecked")
    public static IDocumentMigration propertySetter() {
        return document -> {
            try {
                boolean convertedPropertySetter = false;
                if (document.containsKey(FIELD_NAME_SET_ON_ACTIONS)) {
                    var setOnActions = (List<Map<String, Object>>) document.get(FIELD_NAME_SET_ON_ACTIONS);
                    for (var setOnActionContainer : setOnActions) {
                        if (setOnActionContainer.containsKey(FIELD_NAME_SET_PROPERTIES)) {
                            var setProperties = (List<Map<String, Object>>) setOnActionContainer.get(FIELD_NAME_SET_PROPERTIES);

                            for (var setProperty : setProperties) {
                                convertedPropertySetter = convertPropertyInstructions(setProperty) || convertedPropertySetter;
                            }
                        }
                    }
                }

                return convertedPropertySetter ? document : null;
            } catch (Exception e) {
                LOGGER.error(e.getLocalizedMessage(), e);
                return null;
            }
        };
    }

    @SuppressWarnings("unchecked")
    public static IDocumentMigration apiCalls() {
        return document -> {
            try {
                boolean convertedApiCalls = false;
                if (document.containsKey(OLD_FIELD_NAME_TARGET_SERVER)) {
                    document.put(FIELD_NAME_TARGET_SERVER_URL, document.get(OLD_FIELD_NAME_TARGET_SERVER));
                    document.remove(OLD_FIELD_NAME_TARGET_SERVER);
                    convertedApiCalls = true;
                }
                String differentOldFieldName = OLD_FIELD_NAME_TARGET_SERVER + "Uri";
                if (document.containsKey(differentOldFieldName)) {
                    document.put(FIELD_NAME_TARGET_SERVER_URL, document.get(differentOldFieldName));
                    document.remove(differentOldFieldName);
                    convertedApiCalls = true;
                }
                if (document.containsKey(FIELD_NAME_HTTP_CALLS)) {
                    var httpCalls = (List<Map<String, Object>>) document.get(FIELD_NAME_HTTP_CALLS);
                    for (var httpCall : httpCalls) {
                        if (httpCall.containsKey(FIELD_NAME_PRE_REQUEST)) {
                            var preRequest = (Map<String, List<Map<String, Object>>>) httpCall.get(FIELD_NAME_PRE_REQUEST);
                            convertedApiCalls = convertPreAndPostProcessing(preRequest) || convertedApiCalls;
                        }

                        if (httpCall.containsKey(FIELD_NAME_POST_RESPONSE)) {
                            var postResponse = (Map<String, List<Map<String, Object>>>) httpCall.get(FIELD_NAME_POST_RESPONSE);
                            convertedApiCalls = convertPreAndPostProcessing(postResponse) || convertedApiCalls;
                        }
                    }
                }

                return convertedApiCalls ? document : null;
            } catch (Exception e) {
                LOGGER.error(e.getLocalizedMessage(), e);
                return null;
            }
        };
    }

    private static boolean convertPreAndPostProcessing(Map<String, List<Map<String, Object>>> preRequest) {
        boolean converted = false;
        if (preRequest.containsKey(FIELD_NAME_PROPERTY_INSTRUCTIONS)) {
            for (var propertyInstruction : preRequest.get(FIELD_NAME_PROPERTY_INSTRUCTIONS)) {
                converted = convertPropertyInstructions(propertyInstruction) || converted;
            }
        }
        return converted;
    }

    /**
     * Moves the legacy untyped {@code value} field onto the typed field the v6
     * property model expects. Every BSON type that model can hold is mapped; a
     * value it cannot hold (dates, binary, Decimal128, out-of-int-range longs, …)
     * is left untouched under its original {@code value} key and reported —
     * removing it without a replacement would erase the value irrecoverably.
     *
     * @return true if the instruction was rewritten
     */
    static boolean convertPropertyInstructions(Map<String, Object> propertyInstruction) {
        if (!propertyInstruction.containsKey(FIELD_NAME_VALUE)) {
            return false;
        }

        Object value = propertyInstruction.get(FIELD_NAME_VALUE);
        Object migratedValue = value;
        String targetField;

        if (value == null || value instanceof String) {
            targetField = FIELD_NAME_VALUE_STRING;
            if (LEGACY_UUID_EXPRESSION.equals(value)) {
                migratedValue = QUTE_UUID_EXPRESSION;
            }
        } else if (value instanceof Map<?, ?>) {
            targetField = FIELD_NAME_VALUE_OBJECT;
        } else if (value instanceof List<?>) {
            targetField = FIELD_NAME_VALUE_LIST;
        } else if (value instanceof Boolean) {
            targetField = FIELD_NAME_VALUE_BOOLEAN;
        } else if (value instanceof Integer || value instanceof Short || value instanceof Byte) {
            targetField = FIELD_NAME_VALUE_INT;
            migratedValue = ((Number) value).intValue();
        } else if (value instanceof Long longValue && longValue >= Integer.MIN_VALUE && longValue <= Integer.MAX_VALUE) {
            targetField = FIELD_NAME_VALUE_INT;
            migratedValue = longValue.intValue();
        } else if (value instanceof Float || value instanceof Double) {
            targetField = FIELD_NAME_VALUE_FLOAT;
        } else {
            LOGGER.warnf("Keeping legacy property field '%s' of unsupported type %s as is — the property model has no "
                    + "field for it and dropping it would lose the value. Migrate this document manually.", FIELD_NAME_VALUE,
                    value.getClass().getName());
            return false;
        }

        propertyInstruction.put(targetField, migratedValue);
        propertyInstruction.remove(FIELD_NAME_VALUE);

        return true;
    }

    @SuppressWarnings("unchecked")
    public static IDocumentMigration output() {
        return document -> {
            try {
                boolean convertedOutput = false;
                if (document.containsKey(FIELD_NAME_OUTPUT_SET)) {
                    var outputSet = (List<Map<String, Object>>) document.get(FIELD_NAME_OUTPUT_SET);
                    for (var outputContainer : outputSet) {
                        if (outputContainer.containsKey(FIELD_NAME_OUTPUTS)) {
                            var outputs = (List<Map<String, Object>>) outputContainer.get(FIELD_NAME_OUTPUTS);
                            for (var output : outputs) {
                                output.remove(FIELD_NAME_TYPE);
                                if (output.containsKey(FIELD_NAME_VALUE_ALTERNATIVES)) {
                                    var valueAlternatives = (List<Object>) output.get(FIELD_NAME_VALUE_ALTERNATIVES);
                                    for (int i = 0; i < valueAlternatives.size(); i++) {
                                        Object valueAlternative = valueAlternatives.get(i);
                                        if (valueAlternative instanceof String) {
                                            var textOutput = new TextOutputItem(valueAlternative.toString());
                                            valueAlternatives.set(i, textOutput);
                                            convertedOutput = true;
                                        } else if (valueAlternative instanceof Map<?, ?>) {
                                            var outputValue = (Map<String, Object>) valueAlternative;
                                            // The avatar item's type id was renamed in v6.
                                            // OutputItem still ACCEPTS the retired id — an
                                            // unresolvable type id is fatal to the whole
                                            // document, not just the one item — and that alias
                                            // is what carries every read. This rewrite is the
                                            // narrower half: it reaches a never-migrated
                                            // MongoDB database and every imported ZIP, but NOT
                                            // an already-migrated deployment, whose startup
                                            // sweep is gated on a log row it already has (see
                                            // AgentFaceOutputItem.LEGACY_TYPE_ID). So this
                                            // normalizes what it can see; it does not make the
                                            // alias redundant.
                                            if (AgentFaceOutputItem.LEGACY_TYPE_ID.equals(outputValue.get(FIELD_NAME_TYPE))) {
                                                outputValue.put(FIELD_NAME_TYPE, AgentFaceOutputItem.TYPE_ID);
                                                convertedOutput = true;
                                            }
                                            var type = outputValue.get(FIELD_NAME_TYPE);
                                            if (isNullOrEmpty(type) || type.equals(FIELD_NAME_OTHER)) {
                                                if (!isNullOrEmpty(outputValue.get(FIELD_NAME_TEXT))) {
                                                    outputValue.put(FIELD_NAME_TYPE, FIELD_NAME_TEXT);
                                                } else if (!isNullOrEmpty(outputValue.get(FIELD_NAME_URI))) {
                                                    outputValue.put(FIELD_NAME_TYPE, FIELD_NAME_IMAGE);
                                                } else if (!isNullOrEmpty(outputValue.get(FIELD_NAME_EXPRESSIONS))) {
                                                    outputValue.put(FIELD_NAME_TYPE, FIELD_NAME_QUICK_REPLY);
                                                } else if (!isNullOrEmpty(outputValue.get(FIELD_NAME_PLACEHOLDER))) {
                                                    outputValue.put(FIELD_NAME_TYPE, FIELD_NAME_INPUT_FIELD);
                                                    if (outputValue.containsKey(OLD_FIELD_NAME_IS_PASSWORD)) {
                                                        var isPassword = parseBoolean(outputValue.get(OLD_FIELD_NAME_IS_PASSWORD).toString());
                                                        if (isPassword) {
                                                            outputValue.put(FIELD_NAME_SUB_TYPE, "password");
                                                        }
                                                    }
                                                } else if (!isNullOrEmpty(outputValue.get(FIELD_NAME_ON_PRESS))) {
                                                    outputValue.put(FIELD_NAME_TYPE, FIELD_NAME_BUTTON);
                                                } else {
                                                    outputValue.put(FIELD_NAME_TYPE, FIELD_NAME_OTHER);
                                                }

                                                convertedOutput = true;
                                            }

                                            type = outputValue.get(FIELD_NAME_TYPE);

                                            if (type.equals(FIELD_NAME_TEXT)) {
                                                removeNonSupportedProperties(outputValue, FIELD_NAME_TEXT, FIELD_NAME_DELAY);
                                            }

                                            if (type.equals(FIELD_NAME_IMAGE)) {
                                                removeNonSupportedProperties(outputValue, FIELD_NAME_URI, FIELD_NAME_ALT);
                                            }

                                            if (type.equals(FIELD_NAME_INPUT_FIELD)) {
                                                removeNonSupportedProperties(outputValue, FIELD_NAME_SUB_TYPE, FIELD_NAME_LABEL,
                                                        FIELD_NAME_DEFAULT_VALUE, FIELD_NAME_PLACEHOLDER, FIELD_NAME_VALIDATION);
                                            }

                                            if (type.equals(FIELD_NAME_BUTTON)) {
                                                removeNonSupportedProperties(outputValue, FIELD_NAME_BUTTON_TYPE, FIELD_NAME_LABEL,
                                                        FIELD_NAME_ON_PRESS);
                                            }

                                            if (type.equals(FIELD_NAME_OTHER)) {
                                                removeNonStringProperties(outputValue);
                                            }
                                        }
                                    }

                                    output.put(FIELD_NAME_TYPE, null);
                                }
                            }
                        }
                    }
                }

                return convertedOutput ? document : null;
            } catch (Exception e) {
                LOGGER.error(e.getLocalizedMessage(), e);
                return null;
            }
        };
    }

    /**
     * Package-private, not private, so the tests that pin it can call it instead of
     * reaching for it reflectively. Reflection over a helper is what made this
     * extraction break {@code MigrationManagerBranchTest} at runtime rather than at
     * compile time; a direct call fails the build the next time it moves.
     */
    static void removeNonStringProperties(Map<String, Object> outputValue) {
        var toBeRemoved = new LinkedList<String>();
        for (String outputKey : outputValue.keySet()) {
            var value = outputValue.get(outputKey);
            if (value != null && !(value instanceof String)) {
                toBeRemoved.add(outputKey);
            }
        }

        toBeRemoved.forEach(outputValue::remove);
    }

    /**
     * Package-private for the same reason as {@link #removeNonStringProperties}.
     */
    static void removeNonSupportedProperties(Map<String, Object> outputValue, String... fieldNames) {
        var toBeRemoved = new LinkedList<String>();
        for (String outputKey : outputValue.keySet()) {
            if (!outputKey.equals(FIELD_NAME_TYPE) && !Arrays.asList(fieldNames).contains(outputKey)) {
                toBeRemoved.add(outputKey);
            }
        }

        toBeRemoved.forEach(outputValue::remove);
    }

    @SuppressWarnings("unchecked")
    public static IDocumentMigration conversationMemory() {
        return document -> {
            try {
                boolean convertedConversationMemory = false;

                if (document.containsKey(FIELD_NAME_CONVERSATION_PROPERTIES)) {
                    var conversationProperties = (Map<String, Map<String, Object>>) document.get(FIELD_NAME_CONVERSATION_PROPERTIES);

                    for (var propertyKey : conversationProperties.keySet()) {
                        var conversationProperty = conversationProperties.get(propertyKey);
                        convertedConversationMemory = convertPropertyInstructions(conversationProperty) || convertedConversationMemory;
                    }
                }

                return convertedConversationMemory ? document : null;
            } catch (Exception e) {
                LOGGER.error(e.getLocalizedMessage(), e);
                return null;
            }
        };
    }
}
