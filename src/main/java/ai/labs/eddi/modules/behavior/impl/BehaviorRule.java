package ai.labs.eddi.modules.behavior.impl;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.modules.behavior.impl.conditions.IBehaviorCondition;
import ai.labs.eddi.modules.behavior.impl.conditions.IBehaviorCondition.ExecutionState;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */
@Slf4j
@NoArgsConstructor
@Getter
@Setter
public class BehaviorRule implements Cloneable {
    private String name;
    private List<String> actions = new LinkedList<>();
    private List<IBehaviorCondition> conditions = new LinkedList<>();

    public BehaviorRule(String name) {
        this.name = name;
    }

    public ExecutionState execute(IConversationMemory memory, List<BehaviorRule> trace)
            throws InfiniteLoopException, RuntimeException {
        if (trace.contains(this)) {
            // this is an infinite loop, thus throw error
            throw throwInfiniteLoopError(trace);
        } else {
            trace.add(this);

            ExecutionState state = ExecutionState.NOT_EXECUTED;
            for (IBehaviorCondition condition : conditions) {
                var executionState = condition.execute(memory, trace);
                if (executionState == ExecutionState.FAIL || executionState == ExecutionState.ERROR) {
                    state = executionState;
                    break;
                }
            }

            List<BehaviorRule> tmp = new LinkedList<>(trace);
            trace.clear();
            trace.addAll(tmp.subList(0, tmp.indexOf(this)));

            if (state != ExecutionState.NOT_EXECUTED) {
                return state;
            }

            return ExecutionState.SUCCESS;
        }
    }

    private InfiniteLoopException throwInfiniteLoopError(List<BehaviorRule> trace) {
        StringBuilder errorMessage = new StringBuilder();

        errorMessage.append("reached infinite  loop:\n");
        for (BehaviorRule status : trace) {
            errorMessage.append(" -> ").append(status.getName()).append("\n");
        }

        return new InfiniteLoopException(errorMessage.toString());
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BehaviorRule)) return false;

        BehaviorRule that = (BehaviorRule) o;

        return name.equals(that.getName());
    }

    @Override
    public BehaviorRule clone() throws CloneNotSupportedException {
        BehaviorRule clone = new BehaviorRule(name);

        List<IBehaviorCondition> executablesClone = new LinkedList<>();
        for (IBehaviorCondition condition : conditions) {
            IBehaviorCondition exClone = condition.clone();
            executablesClone.add(exClone);
        }
        clone.setConditions(executablesClone);

        return clone;
    }

    @Override
    public String toString() {
        return name;
    }

    public static class RuntimeException extends Exception {
        public RuntimeException(String message, Exception e) {
            super(message, e);
        }
    }

    public class InfiniteLoopException extends Exception {
        InfiniteLoopException(String message) {
            super(message);
        }
    }
}
