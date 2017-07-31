package ai.labs.behavior.impl;

import ai.labs.behavior.impl.extensions.IBehaviorExtension;
import ai.labs.memory.IConversationMemory;
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
    private List<IBehaviorExtension> extensions = new LinkedList<>();

    public BehaviorRule(String name) {
        this.name = name;
    }

    public IBehaviorExtension.ExecutionState execute(IConversationMemory memory, List<BehaviorRule> trace)
            throws InfiniteLoopException {
        if (trace.contains(this)) {
            // this is an infinite loop, thus throw error
            throw throwInfiniteLoopError(trace);
        } else {
            trace.add(this);

            IBehaviorExtension.ExecutionState state = IBehaviorExtension.ExecutionState.NOT_EXECUTED;
            for (IBehaviorExtension extension : extensions) {
                extension.execute(memory, trace);
                if (extension.getExecutionState() == IBehaviorExtension.ExecutionState.FAIL) {
                    state = IBehaviorExtension.ExecutionState.FAIL;
                    break;
                } else if (extension.getExecutionState() == IBehaviorExtension.ExecutionState.ERROR) {
                    state = IBehaviorExtension.ExecutionState.ERROR;
                    break;
                }
            }

            List<BehaviorRule> tmp = new LinkedList<>(trace);
            trace.clear();
            trace.addAll(tmp.subList(0, tmp.indexOf(this)));

            if (state != IBehaviorExtension.ExecutionState.NOT_EXECUTED)
                return state;

            return IBehaviorExtension.ExecutionState.SUCCESS;
        }
    }

    private InfiniteLoopException throwInfiniteLoopError(List<BehaviorRule> trace)
            throws InfiniteLoopException {
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

        List<IBehaviorExtension> executablesClone = new LinkedList<>();
        for (IBehaviorExtension extension : extensions) {
            IBehaviorExtension exClone = extension.clone();
            executablesClone.add(exClone);
        }
        clone.setExtensions(executablesClone);

        return clone;
    }

    @Override
    public String toString() {
        return name;
    }

    public class InfiniteLoopException extends Exception {
        InfiniteLoopException(String message) {
            super(message);
        }
    }
}
