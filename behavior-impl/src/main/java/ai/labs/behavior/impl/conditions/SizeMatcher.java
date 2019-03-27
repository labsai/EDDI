package ai.labs.behavior.impl.conditions;

import ai.labs.behavior.impl.BehaviorRule;
import ai.labs.memory.IConversationMemory;
import ai.labs.memory.IMemoryItemConverter;
import ognl.Ognl;
import ognl.OgnlException;

import javax.inject.Inject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author ginccc
 */

public class SizeMatcher implements IBehaviorCondition {
    private ExecutionState state = ExecutionState.NOT_EXECUTED;
    private static final String ID = "sizematcher";
    private static final String valuePathQualifier = "valuePath";
    private final String minQualifier = "min";
    private final String maxQualifier = "max";
    private final String equalQualifier = "equal";

    private String valuePath;
    private int max = -1;
    private int min = -1;
    private int equal = -1;

    private final IMemoryItemConverter memoryItemConverter;

    @Inject
    public SizeMatcher(IMemoryItemConverter memoryItemConverter) {
        this.memoryItemConverter = memoryItemConverter;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public Map<String, String> getValues() {
        Map<String, String> values = new HashMap<>();

        values.put(valuePathQualifier, valuePath);
        values.put(minQualifier, String.valueOf(min));
        values.put(maxQualifier, String.valueOf(max));
        values.put(equalQualifier, String.valueOf(equal));

        return values;
    }

    @Override
    public void setValues(Map<String, String> values) {
        if (values != null && !values.isEmpty()) {
            if (values.containsKey(valuePathQualifier)) {
                valuePath = values.get(valuePathQualifier);
            }

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
    public ExecutionState execute(final IConversationMemory memory, final List<BehaviorRule> trace)
            throws BehaviorRule.RuntimeException {
        if (min == -1 && max == -1 && equal == -1) {
            return ExecutionState.NOT_EXECUTED;
        }

        try {
            int size = Integer.parseInt(Ognl.getValue(valuePath, memoryItemConverter.convert(memory)).toString());

            boolean isMin = true;
            boolean isMax = true;
            boolean isEqual = true;

            if (min != -1) {
                isMin = size >= min;
            }

            if (max != -1) {
                isMax = size <= max;
            }

            if (equal != -1) {
                isEqual = size == equal;
            }

            if (isMin && isMax && isEqual) {
                state = ExecutionState.SUCCESS;
            } else {
                state = ExecutionState.FAIL;
            }

            return state;

        } catch (OgnlException e) {
            throw new BehaviorRule.RuntimeException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public ExecutionState getExecutionState() {
        return state;
    }

    public IBehaviorCondition clone() {
        IBehaviorCondition clone = new SizeMatcher(memoryItemConverter);
        clone.setValues(getValues());
        return clone;
    }
}
