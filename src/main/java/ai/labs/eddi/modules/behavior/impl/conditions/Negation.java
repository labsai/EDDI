package ai.labs.eddi.modules.behavior.impl.conditions;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.modules.behavior.impl.BehaviorRule;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Collections;
import java.util.List;

import static ai.labs.eddi.modules.behavior.impl.conditions.IBehaviorCondition.ExecutionState.NOT_EXECUTED;


/**
 * @author ginccc
 */


@NoArgsConstructor
public class Negation implements IBehaviorCondition {
    public static final String ID = "negation";
    @Setter
    private IBehaviorCondition condition;

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
        ExecutionState state = NOT_EXECUTED;
        if (condition != null) {
            ExecutionState stateOfExecutable = condition.execute(memory, trace);

            if (stateOfExecutable == ExecutionState.SUCCESS)
                state = ExecutionState.FAIL;
            else if (stateOfExecutable == ExecutionState.FAIL)
                state = ExecutionState.SUCCESS;
        }

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
