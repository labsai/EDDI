package ai.labs.behavior.impl.extensions;

import ai.labs.behavior.impl.BehaviorRule;
import ai.labs.memory.IConversationMemory;
import ai.labs.memory.IData;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * @author ginccc
 */
@NoArgsConstructor
public class ResultSize implements IBehaviorExtension {
    @Setter
    private int max = -1;
    @Setter
    private int min = -1;
    @Setter
    private int equal = -1;

    private ExecutionState state = ExecutionState.NOT_EXECUTED;
    private static final String ID = "resultSize";
    private final String minQualifier = "min";
    private final String maxQualifier = "max";
    private final String equalQualifier = "equal";

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public Map<String, String> getValues() {
        Map<String, String> values = new HashMap<>();

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
    public ExecutionState execute(final IConversationMemory memory, final List<BehaviorRule> trace) {
        if (min == -1 && max == -1 && equal == -1) {
            return ExecutionState.NOT_EXECUTED;
        }

        IData data = memory.getCurrentStep().getLatestData("result_size");
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

    public IBehaviorExtension clone() {
        IBehaviorExtension clone = new ResultSize();
        clone.setValues(getValues());
        return clone;
    }
}
