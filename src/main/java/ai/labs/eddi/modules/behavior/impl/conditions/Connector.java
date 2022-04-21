package ai.labs.eddi.modules.behavior.impl.conditions;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.modules.behavior.impl.BehaviorRule;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;


/**
 * @author ginccc
 */
@NoArgsConstructor
public class Connector implements IBehaviorCondition {
    public static final String ID = "connector";
    private final String operatorQualifier = "operator";

    public enum Operator {
        OR, AND
    }

    private Operator operator;

    @Getter
    private final List<IBehaviorCondition> conditions = new LinkedList<>();

    private Connector(Operator operator) {
        this.operator = operator;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public Map<String, String> getConfigs() {
        Map<String, String> configs = new HashMap<>();
        configs.put(operatorQualifier, operator.name());
        return configs;
    }

    @Override
    public void setConfigs(Map<String, String> configs) {
        if (configs != null && !configs.isEmpty()) {
            if (configs.containsKey(operatorQualifier)) {
                String operator = configs.get(operatorQualifier);
                if (operator.equals(Operator.AND.name())) {
                    this.operator = Operator.AND;
                } else {
                    this.operator = Operator.OR;
                }
            }
        }
    }

    public ExecutionState execute(IConversationMemory memory, List<BehaviorRule> trace)
            throws BehaviorRule.InfiniteLoopException, BehaviorRule.RuntimeException {

        ExecutionState state;
        if (operator == Operator.OR) {
            state = ExecutionState.FAIL;

            for (IBehaviorCondition condition : conditions) {
                var executionState = condition.execute(memory, trace);
                if (executionState == ExecutionState.SUCCESS || executionState == ExecutionState.ERROR) {
                    state = executionState;
                    break;
                }
            }
        } else // if(operator == AND)
        {
            state = ExecutionState.SUCCESS;

            for (IBehaviorCondition condition : conditions) {
                var executionState = condition.execute(memory, trace);
                if (executionState == ExecutionState.FAIL || executionState == ExecutionState.ERROR) {
                    state = executionState;
                    break;
                }
            }
        }

        return state;
    }

    public boolean isEmpty() {
        return conditions.isEmpty();
    }

    @Override
    public IBehaviorCondition clone() throws CloneNotSupportedException {
        Connector clone = new Connector(operator);

        List<IBehaviorCondition> conditionClone = new LinkedList<>();
        for (IBehaviorCondition condition : conditions) {
            conditionClone.add(condition.clone());
        }

        clone.setConditions(conditionClone);
        clone.setConfigs(getConfigs());

        return clone;
    }

    @Override
    public void setConditions(List<IBehaviorCondition> conditions) {
        this.conditions.addAll(conditions);
    }
}
