package ai.labs.core.behavior.extensions;

import ai.labs.core.behavior.BehaviorRule;
import ai.labs.core.behavior.BehaviorSet;
import ai.labs.expressions.Expression;
import ai.labs.expressions.utilities.IExpressionProvider;
import ai.labs.memory.IConversationMemory;
import ai.labs.runtime.DependencyInjector;
import ai.labs.utilities.CharacterUtilities;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * @author ginccc
 */
public class OutputReference implements IExtension {
    public static final String PART = "part";
    public static final String EQUAL = "equal";

    private List<Expression> filter;      // any expression which should be checked; '*' is allowed
    private String inputValue = "";         // part|equal
    private String sessionValue = "";       // part|equal

    private ExecutionState state = ExecutionState.NOT_EXECUTED;
    private IConversationMemory memory;
    public static final String ID = "outputReference";
    private final String inputValueQualifier = "inputValue";
    private final String sessionValueQualifier = "sessionValue";
    private final String filterQualifier = "filter";
    private IExpressionProvider expressionUtilities;

    public OutputReference() {
        //empty default constructor
        expressionUtilities = DependencyInjector.getInstance().getInstance(IExpressionProvider.class);
    }

    public OutputReference(List<Expression> filter, String inputValue, String sessionValue) {
        this.filter = filter;
        this.inputValue = inputValue;
        this.sessionValue = sessionValue;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public Map<String, String> getValues() {
        Map<String, String> values = new HashMap<String, String>();

        values.put(inputValueQualifier, inputValue);
        values.put(sessionValueQualifier, sessionValue);
        values.put(filterQualifier, CharacterUtilities.arrayToString(filter, ","));

        return values;
    }

    @Override
    public void setValues(Map<String, String> values) {
        if (values != null && !values.isEmpty()) {
            if (values.containsKey(inputValueQualifier)) {
                inputValue = values.get(inputValueQualifier);
            }

            if (values.containsKey(sessionValueQualifier)) {
                sessionValue = values.get(sessionValueQualifier);
            }

            if (values.containsKey(filterQualifier)) {
                filter = expressionUtilities.parseExpressions(values.get(filterQualifier));
            }
        }
    }

    @Override
    public IExtension[] getChildren() {
        return new IExtension[0];
    }

    @Override
    public ExecutionState execute(IConversationMemory memory, List<BehaviorRule> trace) {
        /* todo
                if(inputValue.equals("") && sessionValue.equals(""))
                    return ExecutionState.FAIL;

                List<Expression> outputReferences = filter(filter, session.getConversationMemory().getMergedOutputReferences());
                List<Expression> inputExpressions = filter(filter, session.getConversationMemory().getExpression());
                List<Expression> sessionExpressions = filter(filter, session.getConversationMemory().getDomain().getAllCurrentProperties());

                boolean input = false;
                if(inputValue.equals(PART))
                    input = outputReferences.size() > 0 && inputExpressions.size() > 0 && outputReferences.containsAll(inputExpressions);
                else if(inputValue.equals(EQUAL))
                    input = inputExpressions.equals(outputReferences);

                boolean session = false;
                if(sessionValue.equals(PART))
                    session = outputReferences.size() > 0 && sessionExpressions.size() > 0 && outputReferences.containsAll(sessionExpressions);
                else if(sessionValue.equals(EQUAL))
                    session = sessionExpressions.equals(outputReferences);

                if(input || session)
                    state = ExecutionState.SUCCESS;
                else
                    state = ExecutionState.FAIL;
        */
        return state;
    }

    private List<Expression> filter(List<Expression> filter, List<Expression> list) {
        List<Expression> ret = new LinkedList<Expression>();
        for (Expression tmp : list)
            for (Expression exp : filter)
                if (exp.equals(tmp)) {
                    ret.add(tmp);
                    break;
                }

        return ret;
    }

    @Override
    public ExecutionState getExecutionState() {
        return state;
    }

    @Override
    public IExtension clone() throws CloneNotSupportedException {
        IExtension outputReference = new OutputReference();
        outputReference.setValues(getValues());
        return outputReference;
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
