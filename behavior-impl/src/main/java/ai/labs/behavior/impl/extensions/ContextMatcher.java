package ai.labs.behavior.impl.extensions;

import ai.labs.behavior.impl.BehaviorRule;
import ai.labs.expressions.Expression;
import ai.labs.expressions.utilities.IExpressionProvider;
import ai.labs.lifecycle.model.Context;
import ai.labs.memory.IConversationMemory;
import ai.labs.memory.IData;
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
public class ContextMatcher implements IBehaviorExtension {
    private static final String ID = "contextmatcher";

    enum ContextType {
        expressions,
        object,
        string
    }

    private String contextKey;
    private String contextType;
    private List<Expression> expressions;
    private ObjectValue object;
    private String string;
    private ExecutionState state = ExecutionState.NOT_EXECUTED;
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
    public Map<String, String> getValues() {
        HashMap<String, String> result = new HashMap<>();
        result.put(contextKeyQualifier, contextKey);
        result.put(contextTypeQualifier, contextType);
        if (expressions != null) {
            result.put(expressionsQualifier, StringUtilities.joinStrings(",", expressions));
        }

        if (object != null) {
            result.put(objectKeyPathQualifier, object.objectKeyPath);
            result.put(objectValueQualifier, object.objectValue);
        }

        if (string != null) {
            result.put(stringQualifier, string);
        }

        return result;
    }

    @Override
    public void setValues(Map<String, String> values) {
        if (values != null && !values.isEmpty()) {
            if (values.containsKey(contextKeyQualifier)) {
                contextKey = values.get(contextKeyQualifier);
            }

            if (values.containsKey(contextTypeQualifier)) {
                contextType = values.get(contextTypeQualifier);
                if (values.get(contextTypeQualifier).equals(expressionsQualifier)) {
                    expressions = expressionProvider.parseExpressions(values.get(expressionsQualifier));
                } else if (values.get(contextTypeQualifier).equals(objectQualifier)) {
                    object = new ObjectValue(values.get(objectKeyPathQualifier), values.get(objectValueQualifier));
                } else {
                    string = values.get(stringQualifier);
                }
            }
        }
    }

    @Override
    public ExecutionState execute(IConversationMemory memory, List<BehaviorRule> trace) {
        List<IData<Context>> contextData = memory.getCurrentStep().getAllData("context");

        boolean success = false;
        for (IData<Context> contextDatum : contextData) {
            Context context = contextDatum.getResult();
            if (contextDatum.getKey().equals("context:" + contextKey)) {
                switch (context.getType()) {
                    case expressions:
                        List<Expression> contextExpressions = expressionProvider.
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
    public ExecutionState getExecutionState() {
        return state;
    }

    @Override
    public IBehaviorExtension clone() throws CloneNotSupportedException {
        IBehaviorExtension clone = new ContextMatcher(expressionProvider, jsonSerialization);
        clone.setValues(getValues());
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