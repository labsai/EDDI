package ai.labs.behavior.impl.conditions;

import ai.labs.behavior.impl.BehaviorRule;
import ai.labs.memory.IConversationMemory;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Collections;
import java.util.List;

/**
 * @author ginccc
 */
@NoArgsConstructor
public class Negation implements IBehaviorCondition {
    private static final String ID = "negation";
    @Setter
    private IBehaviorCondition condition;
    private ExecutionState state = ExecutionState.NOT_EXECUTED;

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public List<IBehaviorCondition> getConditions() {
        return Collections.singletonList(condition);
    }

    @Override
    public ExecutionState execute(IConversationMemory memory, List<BehaviorRule> trace)
            throws BehaviorRule.InfiniteLoopException, BehaviorRule.RuntimeException {
        if (condition != null) {
            ExecutionState stateOfExecutable = condition.execute(memory, trace);

            if (stateOfExecutable == ExecutionState.SUCCESS)
                state = ExecutionState.FAIL;
            else if (stateOfExecutable == ExecutionState.FAIL)
                state = ExecutionState.SUCCESS;
        } else
            state = ExecutionState.NOT_EXECUTED;

        return state;
    }

    @Override
    public ExecutionState getExecutionState() {
        return state;
    }

    @Override
    public IBehaviorCondition clone() throws CloneNotSupportedException {
        Negation negation = new Negation();
        negation.setCondition(condition.clone());
        return negation;
    }

    @Override
    public void setConditions(List<IBehaviorCondition> conditions) {
        if (conditions.size() == 1) {
            this.condition = conditions.get(0);
        }
    }
}
