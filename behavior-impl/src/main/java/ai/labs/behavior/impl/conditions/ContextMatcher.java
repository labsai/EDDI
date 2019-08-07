package ai.labs.behavior.impl.conditions;

import ai.labs.behavior.impl.BehaviorRule;
import ai.labs.expressions.Expressions;
import ai.labs.expressions.utilities.IExpressionProvider;
import ai.labs.memory.IConversationMemory;
import ai.labs.memory.IData;
import ai.labs.models.Context;
import ai.labs.serialization.IJsonSerialization;
import ai.labs.utilities.StringUtilities;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author ginccc
 */
@Slf4j
public class ContextMatcher implements IBehaviorCondition {
    private static final String ID = "contextmatcher";
    private static final String CONTEXT = "context";

    enum ContextType {
        expressions,
        object,
        string
    }

    private String contextKey;
    private String contextType;
    private Expressions expressions;
    private ObjectValue object;
    private String string;
    private final String contextKeyQualifier = "contextKey";
    private final String contextTypeQualifier = "contextType";
    private final String expressionsQualifier = ContextType.expressions.toString();
    private final String objectQualifier = ContextType.object.toString();
    private final String objectKeyPathQualifier = "objectKeyPath";
    private final String objectValueQualifier = "objectValue";
    private final String stringQualifier = ContextType.string.toString();
    private final IExpressionProvider expressionProvider;
    private final IJsonSerialization jsonSerialization;

    @Inject
    ContextMatcher(IExpressionProvider expressionProvider,
                   IJsonSerialization jsonSerialization) {
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
                contextType = configs.get(contextTypeQualifier);
                if (configs.get(contextTypeQualifier).equals(expressionsQualifier)) {
                    expressions = expressionProvider.parseExpressions(configs.get(expressionsQualifier));
                } else if (configs.get(contextTypeQualifier).equals(objectQualifier)) {
                    object = new ObjectValue(configs.get(objectKeyPathQualifier), configs.get(objectValueQualifier));
                } else {
                    string = configs.get(stringQualifier);
                }
            }
        }
    }

    @Override
    public ExecutionState execute(IConversationMemory memory, List<BehaviorRule> trace) {
        List<IData<Context>> contextData = memory.getCurrentStep().getAllData(CONTEXT);

        ExecutionState state;
        boolean success = false;
        for (IData<Context> contextDatum : contextData) {
            Context context = contextDatum.getResult();
            if (contextDatum.getKey().equals(CONTEXT + ":" + contextKey)) {
                switch (context.getType()) {
                    case expressions:
                        Expressions contextExpressions = expressionProvider.
                                parseExpressions(context.getValue().toString());
                        success = Collections.indexOfSubList(contextExpressions, expressions) != -1;
                        break;
                    case object:
                        try {
                            if (object.getObjectKeyPath() != null) {
                                final String contextObjectAsJson = jsonSerialization.serialize(context.getValue());
                                Object foundObjectValue = findObjectValue(contextObjectAsJson);
                                if (foundObjectValue != null) { // key exists in context, so we continue
                                    success = object.getObjectValue() == null ||
                                            object.getObjectValue().equals(foundObjectValue.toString());
                                }
                            }
                        } catch (IOException e) {
                            log.error(e.getLocalizedMessage(), e);
                            success = false;
                        }
                        break;

                    default:
                    case string:
                        success = string.equals(context.getValue().toString());
                        break;
                }
            }
        }

        state = success ? ExecutionState.SUCCESS : ExecutionState.FAIL;
        return state;
    }

    private Object findObjectValue(String contextObjectAsJson) {
        try {
            return JsonPath.parse(contextObjectAsJson).read(object.getObjectKeyPath());
        } catch (PathNotFoundException e) {
            return null;
        }
    }

    @Override
    public IBehaviorCondition clone() {
        IBehaviorCondition clone = new ContextMatcher(expressionProvider, jsonSerialization);
        clone.setConfigs(getConfigs());
        return clone;
    }

    @AllArgsConstructor
    @Getter
    @Setter
    @EqualsAndHashCode
    static class ObjectValue {
        private String objectKeyPath;
        private String objectValue;
    }
}