/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rules.impl.conditions;

import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.modules.rules.impl.Rule;
import ai.labs.eddi.modules.nlp.expressions.Expressions;
import ai.labs.eddi.modules.nlp.expressions.utilities.IExpressionProvider;
import ai.labs.eddi.utils.StringUtilities;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author ginccc
 */

public class ContextMatcher implements IRuleCondition {
    public static final String ID = "contextmatcher";
    private static final String CONTEXT = "context";

    enum ContextType {
        expressions, object, string
    }

    private String contextKey;
    private String contextType;
    private Expressions expressions;
    private ObjectValue object;
    private String string;
    private final String contextKeyQualifier = "contextKey";
    private final String contextTypeQualifier = "contextType";
    private final String expressionsQualifier = ContextType.expressions.toString();
    private final String objectKeyPathQualifier = "objectKeyPath";
    private final String objectValueQualifier = "objectValue";
    private final String stringQualifier = ContextType.string.toString();
    private final IExpressionProvider expressionProvider;
    private final IJsonSerialization jsonSerialization;

    private static final Logger log = Logger.getLogger(ContextMatcher.class);

    public ContextMatcher(IExpressionProvider expressionProvider, IJsonSerialization jsonSerialization) {
        this.expressionProvider = expressionProvider;
        this.jsonSerialization = jsonSerialization;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public Map<String, String> getConfigs() {
        HashMap<String, String> configs = new HashMap<>();
        configs.put(contextKeyQualifier, contextKey);
        configs.put(contextTypeQualifier, contextType);
        if (expressions != null) {
            configs.put(expressionsQualifier, StringUtilities.joinStrings(",", expressions));
        }

        if (object != null) {
            configs.put(objectKeyPathQualifier, object.objectKeyPath);
            configs.put(objectValueQualifier, object.objectValue);
        }

        if (string != null) {
            configs.put(stringQualifier, string);
        }

        return configs;
    }

    @Override
    public void setConfigs(Map<String, String> configs) {
        if (configs != null && !configs.isEmpty()) {
            if (configs.containsKey(contextKeyQualifier)) {
                contextKey = configs.get(contextKeyQualifier);
            }

            if (configs.containsKey(contextTypeQualifier)) {
                ContextType parsedContextType = parseContextType(configs.get(contextTypeQualifier));
                contextType = parsedContextType.toString();
                switch (parsedContextType) {
                    case expressions -> {
                        String configuredExpressions = configs.get(expressionsQualifier);
                        expressions = configuredExpressions == null ? null : expressionProvider.parseExpressions(configuredExpressions);
                    }
                    case object -> object = new ObjectValue(configs.get(objectKeyPathQualifier), configs.get(objectValueQualifier));
                    case string -> string = configs.get(stringQualifier);
                }
            }
        }
    }

    /**
     * Rejects an unknown {@code contextType} instead of silently treating it as
     * {@code string} — the silent fallback left the type-specific field unset and
     * produced a NullPointerException at conversation time.
     */
    private ContextType parseContextType(String configuredContextType) {
        if (configuredContextType != null) {
            try {
                return ContextType.valueOf(configuredContextType.trim());
            } catch (IllegalArgumentException e) {
                // fall through to the shared error message below
            }
        }

        throw new IllegalArgumentException(String.format("Unknown '%s' value '%s' — legal values are %s.", contextTypeQualifier,
                configuredContextType, Arrays.toString(ContextType.values())));
    }

    @Override
    public void validateConfiguration() {
        if (contextKey == null || contextKey.isBlank()) {
            throw new IllegalArgumentException(String.format("'%s' requires a '%s' config value.", ID, contextKeyQualifier));
        }

        if (contextType == null) {
            throw new IllegalArgumentException(String.format("'%s' requires a '%s' config value — legal values are %s.", ID,
                    contextTypeQualifier, Arrays.toString(ContextType.values())));
        }

        switch (ContextType.valueOf(contextType)) {
            case expressions -> requireConfigValue(expressions, expressionsQualifier);
            case object -> requireConfigValue(object == null ? null : object.getObjectKeyPath(), objectKeyPathQualifier);
            case string -> requireConfigValue(string, stringQualifier);
        }
    }

    private void requireConfigValue(Object value, String qualifier) {
        if (value == null || (value instanceof List<?> list && list.isEmpty())) {
            throw new IllegalArgumentException(
                    String.format("'%s' with '%s' set to '%s' requires a '%s' config value.", ID, contextTypeQualifier, contextType, qualifier));
        }
    }

    @Override
    public ExecutionState execute(IConversationMemory memory, List<Rule> trace) {
        List<IData<Context>> contextData = memory.getCurrentStep().getAllData(CONTEXT);

        ExecutionState state;
        boolean success = false;
        for (IData<Context> contextDatum : contextData) {
            Context context = contextDatum.getResult();
            if (contextDatum.getKey().equals(CONTEXT + ":" + contextKey)) {
                if (!isMatchableContext(context)) {
                    continue;
                }

                switch (context.getType()) {
                    case expressions :
                        Expressions contextExpressions = expressionProvider.parseExpressions(context.getValue().toString());
                        success = expressions != null && Collections.indexOfSubList(contextExpressions, expressions) != -1;
                        break;
                    case object :
                        try {
                            if (object != null && object.getObjectKeyPath() != null) {
                                final String contextObjectAsJson = jsonSerialization.serialize(context.getValue());
                                Object foundObjectValue = findObjectValue(contextObjectAsJson);
                                if (foundObjectValue != null) { // key exists in context, so we continue
                                    success = object.getObjectValue() == null || object.getObjectValue().equals(foundObjectValue.toString());
                                }
                            }
                        } catch (IOException e) {
                            log.error(e.getLocalizedMessage(), e);
                            success = false;
                        }
                        break;

                    default :
                    case string :
                        success = string != null && string.equals(context.getValue().toString());
                        break;
                }
            }
        }

        state = success ? ExecutionState.SUCCESS : ExecutionState.FAIL;
        return state;
    }

    /**
     * The runtime context carries its own type, which may differ from the one this
     * matcher was configured with (only the configured type's field is populated).
     * A mismatch is a non-match, not a NullPointerException that kills the turn.
     */
    private boolean isMatchableContext(Context context) {
        if (context == null || context.getType() == null || context.getValue() == null) {
            log.debugf("Context '%s' is not evaluable (context, type or value is null) — treated as non-match.", contextKey);
            return false;
        }

        if (!isSupportedContextType(context.getType())) {
            // Not a plain type mismatch: no contextmatcher configuration can ever match
            // this context, so the author's rule silently never fires. Worth a warning.
            log.warnf("Context '%s' is of runtime type '%s', which '%s' cannot evaluate (supported types: %s)."
                    + " This condition can never match.", contextKey, context.getType(), ID, Arrays.toString(ContextType.values()));
            return false;
        }

        if (!context.getType().toString().equals(contextType)) {
            log.debugf("Context '%s' is of type '%s' but '%s' is configured for type '%s' — treated as non-match.", contextKey,
                    context.getType(), ID, contextType);
            return false;
        }

        return true;
    }

    /**
     * Whether a runtime context of this type can be evaluated by a contextmatcher
     * at all. {@link Context.ContextType} carries one value more than
     * {@link ContextType} — {@code array} — and a configured {@code contextType}
     * can only ever be one of the latter, so an array context is unmatchable by
     * construction rather than merely mismatched.
     */
    static boolean isSupportedContextType(Context.ContextType runtimeType) {
        return runtimeType != null && Arrays.stream(ContextType.values()).anyMatch(supported -> supported.name().equals(runtimeType.name()));
    }

    private Object findObjectValue(String contextObjectAsJson) {
        try {
            return JsonPath.parse(contextObjectAsJson).read(object.getObjectKeyPath());
        } catch (PathNotFoundException e) {
            return null;
        }
    }

    @Override
    public IRuleCondition clone() {
        IRuleCondition clone = new ContextMatcher(expressionProvider, jsonSerialization);
        clone.setConfigs(getConfigs());
        return clone;
    }

    static class ObjectValue {
        private String objectKeyPath;
        private String objectValue;

        public ObjectValue(String objectKeyPath, String objectValue) {
            this.objectKeyPath = objectKeyPath;
            this.objectValue = objectValue;
        }

        public String getObjectKeyPath() {
            return objectKeyPath;
        }

        public void setObjectKeyPath(String objectKeyPath) {
            this.objectKeyPath = objectKeyPath;
        }

        public String getObjectValue() {
            return objectValue;
        }

        public void setObjectValue(String objectValue) {
            this.objectValue = objectValue;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;
            ObjectValue that = (ObjectValue) o;
            return java.util.Objects.equals(objectKeyPath, that.objectKeyPath) && java.util.Objects.equals(objectValue, that.objectValue);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(objectKeyPath, objectValue);
        }
    }
}