package ai.labs.behavior.impl.extensions;

import ai.labs.behavior.impl.BehaviorRule;
import ai.labs.expressions.Expression;
import ai.labs.expressions.utilities.IExpressionProvider;
import ai.labs.lifecycle.model.Context;
import ai.labs.memory.IConversationMemory;
import ai.labs.memory.IData;
import ai.labs.utilities.CharacterUtilities;
import ai.labs.utilities.LanguageUtilities;
import io.restassured.path.json.JsonPath;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import javax.inject.Inject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author ginccc
 */
public class ContextMatcher implements IBehaviorExtension {
    private static final String ID = "contextmatcher";

    private String contextKey;
    private String contextType;
    private List<Expression> expressions;
    private ObjectValue object;
    private String string;
    private ExecutionState state = ExecutionState.NOT_EXECUTED;
    private final String contextKeyQualifier = "contextKey";
    private final String contextTypeQualifier = "contextType"; // string or expressions
    private final String expressionsQualifier = "expressions"; // string or expressions
    private final String objectQualifier = "object";
    private final String objectKeyPathQualifier = "objectKeyPath";
    private final String objectValueQualifier = "objectValue";
    private final String stringQualifier = "string"; // string or expressions
    private IExpressionProvider expressionProvider;

    @Inject
    private ContextMatcher(IExpressionProvider expressionProvider) {
        this.expressionProvider = expressionProvider;
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
            result.put(expressionsQualifier, CharacterUtilities.arrayToString(expressions, ","));
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
                    object = new ObjectValue(values.get(objectQualifier),
                            values.get(objectKeyPathQualifier), values.get(objectValueQualifier));
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
                        success = LanguageUtilities.containsArray(expressions,
                                expressionProvider.parseExpressions(
                                        context.getValue().toString())) != -1;
                        break;
                    case object:
                        success = object.getObjectValue().equals(
                                JsonPath.with(object.getObject()).get(object.getObjectKeyPath()).toString());
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


    @Override
    public ExecutionState getExecutionState() {
        return state;
    }

    @Override
    public IBehaviorExtension clone() throws CloneNotSupportedException {
        IBehaviorExtension clone = new ContextMatcher(expressionProvider);
        clone.setValues(getValues());
        return clone;
    }

    @AllArgsConstructor
    @Getter
    @Setter
    private static class ObjectValue {
        private String object;
        private String objectKeyPath;
        private String objectValue;
    }
}