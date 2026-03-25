package ai.labs.eddi.modules.rules.impl.conditions;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.modules.rules.impl.Rule;

import java.util.Collections;
import java.util.List;

import static ai.labs.eddi.modules.rules.impl.conditions.IRuleCondition.ExecutionState.NOT_EXECUTED;

/**
 * @author ginccc
 */

public class Negation implements IRuleCondition {
    public static final String ID = "negation";
    private IRuleCondition condition;

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public List<IRuleCondition> getConditions() {
        return Collections.singletonList(condition);
    }

    @Override
    public ExecutionState execute(IConversationMemory memory, List<Rule> trace) throws Rule.InfiniteLoopException, Rule.RuntimeException {
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
    public IRuleCondition clone() throws CloneNotSupportedException {
        Negation negation = new Negation();
        negation.setCondition(condition.clone());
        return negation;
    }

    @Override
    public void setConditions(List<IRuleCondition> conditions) {
        if (conditions.size() == 1) {
            this.condition = conditions.get(0);
        }
    }

    public Negation() {
    }

    public void setCondition(IRuleCondition condition) {
        this.condition = condition;
    }
}
