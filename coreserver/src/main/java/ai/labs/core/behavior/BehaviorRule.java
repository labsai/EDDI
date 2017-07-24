package ai.labs.core.behavior;

import ai.labs.core.behavior.extensions.IExtension;
import ai.labs.memory.IConversationMemory;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */
@Slf4j
public class BehaviorRule implements Cloneable {
    private String name;
    private List<String> actions;
    private List<IExtension> extensions;

    public BehaviorRule() {
        actions = new LinkedList<>();
        extensions = new LinkedList<>();
    }

    public BehaviorRule(String name) {
        this.name = name;
        actions = new LinkedList<>();
        extensions = new LinkedList<>();
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<String> getActions() {
        return actions;
    }

    public void setActions(List<String> actions) {
        this.actions = actions;
    }

    public void setExtensions(List<IExtension> extensions) {
        this.extensions = extensions;
    }

    public List<IExtension> getExtensions() {
        return extensions;
    }

    public IExtension.ExecutionState execute(IConversationMemory memory, List<BehaviorRule> trace) {
        if (trace.contains(this)) {
            List<BehaviorRule> endlessLoop = trace.subList(trace.indexOf(this), trace.size());
            endlessLoop.add(this);

            log.error("reached endless loop:");
            for (BehaviorRule status : trace)
                log.error(" -> " + status.getName());

            return IExtension.ExecutionState.ERROR;
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

            List<BehaviorRule> tmp = new LinkedList<BehaviorRule>(trace);
            trace.clear();
            trace.addAll(tmp.subList(0, tmp.indexOf(this)));

            if (state != IExtension.ExecutionState.NOT_EXECUTED)
                return state;

            return IExtension.ExecutionState.SUCCESS;
        }
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

        List<IExtension> executablesClone = new LinkedList<IExtension>();
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
}
