package ai.labs.core.behavior.extensions;

import ai.labs.core.behavior.BehaviorRule;
import ai.labs.core.behavior.BehaviorSet;
import ai.labs.memory.IConversationMemory;
import ai.labs.memory.IData;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * @author ginccc
 */
public class ResultSize implements IExtension {
    private int max = -1;
    private int min = -1;
    private int equal = -1;

    private ExecutionState state = ExecutionState.NOT_EXECUTED;
    private IConversationMemory memory;
    public static final String ID = "resultSize";
    private final String minQualifier = "min";
    private final String maxQualifier = "max";
    private final String equalQualifier = "equal";

    public ResultSize() {
    }

    public void setMax(int max) {
        this.max = max;
    }

    public void setMin(int min) {
        this.min = min;
    }

    public void setEqual(int equal) {
        this.equal = equal;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public Map<String, String> getValues() {
        Map<String, String> values = new HashMap<String, String>();

        values.put(minQualifier, String.valueOf(min));
        values.put(maxQualifier, String.valueOf(max));
        values.put(equalQualifier, String.valueOf(equal));

        return values;
    }

    @Override
    public void setValues(Map<String, String> values) {
        if (values != null && !values.isEmpty()) {
            if (values.containsKey(minQualifier)) {
                min = Integer.parseInt(values.get(minQualifier));
            }

            if (values.containsKey(maxQualifier)) {
                max = Integer.parseInt(values.get(maxQualifier));
            }

            if (values.containsKey(equalQualifier)) {
                equal = Integer.parseInt(values.get(equalQualifier));
            }
        }
    }

    @Override
    public IExtension[] getChildren() {
        return new IExtension[0];
    }

    @Override
    public ExecutionState execute(final IConversationMemory memory, final List<BehaviorRule> trace) {
        if (min == -1 && max == -1 && equal == -1) {
            return ExecutionState.NOT_EXECUTED;
        }

        IData data = memory.getCurrentStep().getLatestData("external_search");
        List result;
        if (data != null) {
            result = (List) data.getResult();
        } else {
            result = new LinkedList();
        }

        if (result == null) {
            return ExecutionState.FAIL;
        }

        boolean isMin = true;
        boolean isMax = true;
        boolean isEqual = true;

        if (min != -1) {
            isMin = result.size() >= min;
        }

        if (max != -1) {
            isMax = result.size() <= max;
        }

        if (equal != -1) {
            isEqual = result.size() == equal;
        }

        if (isMin && isMax && isEqual) {
            state = ExecutionState.SUCCESS;
        } else {
            state = ExecutionState.FAIL;
        }

        return state;
    }

    @Override
    public ExecutionState getExecutionState() {
        return state;
    }

    public IExtension clone() throws CloneNotSupportedException {
        IExtension clone = new ResultSize();
        clone.setValues(getValues());
        return clone;
    }

    @Override
    public void setChildren(IExtension... extensions) {
        //not implemented
    }

    @Override
    public void setContainingBehaviorRuleSet(BehaviorSet behaviorSet) {
        //not implemented
    }
}
