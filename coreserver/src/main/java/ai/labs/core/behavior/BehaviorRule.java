package ai.labs.core.behavior;

import ai.labs.core.behavior.extensions.IExtension;
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
    private List<IExtension> extensions = new LinkedList<>();

    public BehaviorRule(String name) {
        this.name = name;
    }

    public IExtension.ExecutionState execute(IConversationMemory memory, List<BehaviorRule> trace)
            throws InfiniteLoopException {
        if (trace.contains(this)) {
            // this is an infinite loop, thus throw error
            throw throwInfiniteLoopError(trace);
        } else {
            trace.add(this);

            IExtension.ExecutionState state = IExtension.ExecutionState.NOT_EXECUTED;
            for (IExtension extension : extensions) {
                extension.execute(memory, trace);
                if (extension.getExecutionState() == IExtension.ExecutionState.FAIL) {
                    state = IExtension.ExecutionState.FAIL;
                    break;
                } else if (extension.getExecutionState() == IExtension.ExecutionState.ERROR) {
                    state = IExtension.ExecutionState.ERROR;
                    break;
                }
            }

            List<BehaviorRule> tmp = new LinkedList<>(trace);
            trace.clear();
            trace.addAll(tmp.subList(0, tmp.indexOf(this)));

            if (state != IExtension.ExecutionState.NOT_EXECUTED)
                return state;

            return IExtension.ExecutionState.SUCCESS;
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

        List<IExtension> executablesClone = new LinkedList<>();
        for (IExtension extension : extensions) {
            IExtension exClone = extension.clone();
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
