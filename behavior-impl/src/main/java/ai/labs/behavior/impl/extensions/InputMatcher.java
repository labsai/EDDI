package ai.labs.behavior.impl.extensions;

import ai.labs.behavior.impl.BehaviorRule;
import ai.labs.expressions.Expression;
import ai.labs.expressions.utilities.IExpressionProvider;
import ai.labs.memory.IConversationMemory;
import ai.labs.memory.IData;
import ai.labs.utilities.CharacterUtilities;
import ai.labs.utilities.LanguageUtilities;
import lombok.Getter;
import lombok.Setter;

import javax.inject.Inject;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * @author ginccc
 */
public class InputMatcher implements IBehaviorExtension {
    private static final String ID = "inputmatcher";

    //todo add occurrence attribute
    @Getter
    @Setter
    private List<Expression> expressions;
    @Getter
    @Setter
    private boolean ignoreUnusedExpressions = false;
    @Getter
    @Setter
    private boolean ignorePunctuationExpressions = false;
    private ExecutionState state = ExecutionState.NOT_EXECUTED;
    private final String expressionsQualifier = "expressions";
    private IExpressionProvider expressionProvider;

    @Inject
    public InputMatcher(IExpressionProvider expressionProvider) {
        this.expressionProvider = expressionProvider;
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
                expressions = expressionProvider.parseExpressions(values.get(expressionsQualifier));
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
    public ExecutionState execute(IConversationMemory memory, List<BehaviorRule> trace) {
        List<Expression> inputExpressions;

        IData data = memory.getCurrentStep().getLatestData("expression");
        inputExpressions = data != null ? expressionProvider.parseExpressions(data.getResult().toString()) : new LinkedList<>();
        inputExpressions = filterExpressions(inputExpressions);

        boolean isInputEmpty = expressions.size() == 1 &&
                expressions.get(0).getExpressionName().equals("empty") &&
                inputExpressions.size() == 0;


        state = isInputEmpty ||
                LanguageUtilities.containsArray(expressions, inputExpressions) > -1 ? ExecutionState.SUCCESS : ExecutionState.FAIL;

        return state;
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
    public IBehaviorExtension clone() throws CloneNotSupportedException {
        IBehaviorExtension clone = new InputMatcher(expressionProvider);
        clone.setValues(getValues());
        return clone;
    }
}