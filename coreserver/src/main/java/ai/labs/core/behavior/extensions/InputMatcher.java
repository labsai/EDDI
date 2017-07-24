package ai.labs.core.behavior.extensions;

import ai.labs.core.behavior.BehaviorRule;
import ai.labs.core.behavior.BehaviorSet;
import ai.labs.expressions.Expression;
import ai.labs.expressions.utilities.IExpressionProvider;
import ai.labs.memory.IConversationMemory;
import ai.labs.memory.IData;
import ai.labs.runtime.DependencyInjector;
import ai.labs.utilities.CharacterUtilities;
import ai.labs.utilities.LanguageUtilities;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * @author ginccc
 */
public class InputMatcher implements IExtension {
    public static final String ID = "inputmatcher";

    //todo add occurrence attribute
    private List<Expression> expressions;
    private boolean ignoreUnusedExpressions = false;

    private boolean ignorePunctuationExpressions = false;
    private ExecutionState state = ExecutionState.NOT_EXECUTED;
    private final String expressionsQualifier = "expressions";
    private IExpressionProvider expressionUtilities;

    public InputMatcher() {
        expressionUtilities = DependencyInjector.getInstance().getInstance(IExpressionProvider.class);
    }

    public InputMatcher(List<Expression> expression) {
        this.expressions = expression;
    }

    public InputMatcher(List<Expression> expressions,
                        boolean ignoreUnusedExpressions,
                        boolean ignorePunctuationExpressions) {
        this.expressions = expressions;
        this.ignoreUnusedExpressions = ignoreUnusedExpressions;
        this.ignorePunctuationExpressions = ignorePunctuationExpressions;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public Map<String, String> getValues() {
        HashMap<String, String> result = new HashMap<>();
        result.put(expressionsQualifier, CharacterUtilities.arrayToString(expressions, ","));
        //result.put("occurrence", ... );

        return result;
    }

    @Override
    public void setValues(Map<String, String> values) {
        if (values != null && !values.isEmpty()) {
            if (values.containsKey(expressionsQualifier)) {
                expressions = expressionUtilities.parseExpressions(values.get(expressionsQualifier));
            }

            /*if (values.containsKey(contextQualifier))
            {
                String context = values.get(contextQualifier);
                if(context.equals(OccurrenceEnum.last.name()))
                {
                    this.context = OccurrenceEnum.last;
                }
                else
                {
                    this.context = OccurrenceEnum.all;
                }
            }*/
        }
    }

    @Override
    public IExtension[] getChildren() {
        return new IExtension[0];
    }

    @Override
    public ExecutionState execute(IConversationMemory memory, List<BehaviorRule> trace) {
        List<Expression> inputExpressions;

        IData data = memory.getCurrentStep().getLatestData("expression");
        inputExpressions = data != null ? expressionUtilities.parseExpressions(data.getResult().toString()) : new LinkedList<>();
        inputExpressions = filterExpressions(inputExpressions);

        boolean isInputEmpty = expressions.size() == 1 &&
                expressions.get(0).getExpressionName().equals("empty") &&
                inputExpressions.size() == 0;


        state = isInputEmpty ||
                LanguageUtilities.containsArray(expressions, inputExpressions) > -1 ? ExecutionState.SUCCESS : ExecutionState.FAIL;

        return state;
    }

    public List<Expression> getExpressions() {
        return expressions;
    }

    public void setExpressions(List<Expression> expressions) {
        this.expressions = expressions;
    }

    public boolean isIgnorePunctuationExpressions() {
        return ignorePunctuationExpressions;
    }

    public void setIgnorePunctuationExpressions(boolean ignorePunctuationExpressions) {
        this.ignorePunctuationExpressions = ignorePunctuationExpressions;
    }

    public boolean isIgnoreUnusedExpressions() {
        return ignoreUnusedExpressions;
    }

    public void setIgnoreUnusedExpressions(boolean ignoreUnusedExpressions) {
        this.ignoreUnusedExpressions = ignoreUnusedExpressions;
    }

    public List<Expression> filterExpressions(List<Expression> expressions) {
        for (int i = 0; i < expressions.size(); i++) {
            Expression exp = expressions.get(i);
            if ((ignoreUnusedExpressions && exp.getExpressionName().equals("unused")) ||
                    (ignorePunctuationExpressions && exp.getExpressionName().equals("punctuation"))) {
                expressions.remove(i);
                i--;
            }
        }

        return expressions;
    }

    @Override
    public ExecutionState getExecutionState() {
        return state;
    }

    @Override
    public IExtension clone() throws CloneNotSupportedException {
        IExtension clone = new InputMatcher();
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