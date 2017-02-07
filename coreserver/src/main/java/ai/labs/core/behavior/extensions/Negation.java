package ai.labs.core.behavior.extensions;

import ai.labs.core.behavior.BehaviorRule;
import ai.labs.core.behavior.BehaviorSet;
import ai.labs.memory.IConversationMemory;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * @author ginccc
 */
public class Negation implements IExtension {
    public static final String ID = "negation";
    private IExtension extension;
    private ExecutionState state = ExecutionState.NOT_EXECUTED;

    public Negation() {
    }

    public Negation(IExtension extension) {
        this.extension = extension;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public Map<String, String> getValues() {
        return Collections.emptyMap();
    }

    @Override
    public void setValues(Map<String, String> values) {
        // no attributes, therefore not implemented
    }

    @Override
    public IExtension[] getChildren() {
        return new IExtension[]{extension};
    }

    @Override
    public ExecutionState execute(IConversationMemory memory, List<BehaviorRule> trace) {
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

    public void setExtension(IExtension extension) {
        this.extension = extension;
    }

    @Override
    public IExtension clone() throws CloneNotSupportedException {
        Negation negation = new Negation();
        negation.setExtension(extension.clone());
        return negation;
    }

    @Override
    public void setChildren(IExtension... extensions) {
        if (extensions.length == 1) {
            this.extension = extensions[0];
        }
    }

    @Override
    public void setContainingBehaviorRuleSet(BehaviorSet behaviorSet) {
        //not implemented
    }
}
