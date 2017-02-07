package ai.labs.core.behavior.extensions;

import ai.labs.core.behavior.BehaviorRule;
import ai.labs.core.behavior.BehaviorSet;
import ai.labs.expressions.Expression;
import ai.labs.expressions.utilities.IExpressionProvider;
import ai.labs.memory.IConversationMemory;
import ai.labs.runtime.DependencyInjector;
import ai.labs.utilities.CharacterUtilities;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author ginccc
 */
public class PropertyMatcher implements IExtension {
    public static final String ID = "propertymatcher";

    private List<Expression> expressions;

    private ExecutionState state = ExecutionState.NOT_EXECUTED;
    private final String expressionsQualifier = "expressions";
    private IExpressionProvider expressionUtilities;

    public PropertyMatcher() {
        // empty default constructor
        expressionUtilities = DependencyInjector.getInstance().getInstance(IExpressionProvider.class);
    }

    public PropertyMatcher(List<Expression> expressions) {
        this.expressions = expressions;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public Map<String, String> getValues() {
        Map<String, String> result = new HashMap<String, String>();
        result.put(expressionsQualifier, CharacterUtilities.arrayToString(expressions, ","));
        return result;
    }

    @Override
    public void setValues(Map<String, String> values) {
        if (values != null && !values.isEmpty()) {
            if (values.containsKey(expressionsQualifier)) {
                expressions = expressionUtilities.parseExpressions(values.get(expressionsQualifier));
            }
        }
    }

    @Override
    public IExtension[] getChildren() {
        return new IExtension[0];
    }

    @Override
    public ExecutionState execute(IConversationMemory memory, List<BehaviorRule> trace) {
        return null;
    }

    @Override
    public ExecutionState getExecutionState() {
        return state;
    }

    @Override
    public IExtension clone() throws CloneNotSupportedException {
        IExtension clone = new PropertyMatcher();
        clone.setValues(getValues());
        return clone;
    }

    @Override
    public void setChildren(IExtension... extensions) {
        //not implemented
    }

    @Override
    public void setContainingBehaviorRuleSet(BehaviorSet behaviorSet) {
        //not implemented
    }
}
