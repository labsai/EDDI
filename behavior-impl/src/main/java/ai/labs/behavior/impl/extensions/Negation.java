package ai.labs.behavior.impl.extensions;

import ai.labs.behavior.impl.BehaviorRule;
import ai.labs.memory.IConversationMemory;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * @author ginccc
 */
@NoArgsConstructor
public class Negation implements IBehaviorExtension {
    private static final String ID = "negation";
    @Setter
    private IBehaviorExtension extension;
    private ExecutionState state = ExecutionState.NOT_EXECUTED;

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public IBehaviorExtension[] getChildren() {
        return new IBehaviorExtension[]{extension};
    }

    @Override
    public ExecutionState execute(IConversationMemory memory, List<BehaviorRule> trace)
            throws BehaviorRule.InfiniteLoopException {
        if (extension != null) {
            ExecutionState stateOfExecutable = extension.execute(memory, trace);

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
    public IBehaviorExtension clone() throws CloneNotSupportedException {
        Negation negation = new Negation();
        negation.setExtension(extension.clone());
        return negation;
    }

    @Override
    public void setChildren(IBehaviorExtension... extensions) {
        if (extensions.length == 1) {
            this.extension = extensions[0];
        }
    }
}
