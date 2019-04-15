package ai.labs.behavior.impl.conditions;

import ai.labs.behavior.impl.BehaviorRule;
import ai.labs.memory.IConversationMemory;
import ai.labs.memory.IMemoryItemConverter;
import lombok.extern.slf4j.Slf4j;
import ognl.Ognl;
import ognl.OgnlException;

import javax.inject.Inject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static ai.labs.utilities.RuntimeUtilities.isNullOrEmpty;

@Slf4j
public class DynamicValueMatcher implements IBehaviorCondition {
    private static final String ID = "dynamicvaluematcher";
    private final IMemoryItemConverter memoryItemConverter;

    @Inject
    public DynamicValueMatcher(IMemoryItemConverter memoryItemConverter) {
        this.memoryItemConverter = memoryItemConverter;
    }

    private ExecutionState state = ExecutionState.NOT_EXECUTED;
    private String valuePath;
    private String contains;
    private String equals;
    private static final String valuePathQualifier = "valuePath";
    private static final String containsQualifier = "contains";
    private static final String equalsQualifier = "equals";


    @Override
    public String getId() {
        return ID;
    }

    @Override
    public void setConfigs(Map<String, String> configs) {
        if (configs != null && !configs.isEmpty()) {
            if (configs.containsKey(valuePathQualifier)) {
                valuePath = configs.get(valuePathQualifier);
            }

            if (configs.containsKey(containsQualifier)) {
                contains = configs.get(containsQualifier);
            }

            if (configs.containsKey(equalsQualifier)) {
                equals = configs.get(equalsQualifier);
            }
        }
    }

    @Override
    public Map<String, String> getConfigs() {
        Map<String, String> configs = new HashMap<>();
        configs.put(valuePathQualifier, valuePath);
        configs.put(containsQualifier, contains);
        configs.put(equalsQualifier, equals);

        return configs;
    }

    @Override
    public ExecutionState execute(IConversationMemory memory, List<BehaviorRule> trace) {
        boolean success = false;
        try {
            if (!isNullOrEmpty(valuePath)) {
                Object value = Ognl.getValue(valuePath, memoryItemConverter.convert(memory));
                if (value != null) {
                    if (!isNullOrEmpty(equals) && equals.equals(value)) {
                        success = true;
                    } else if (!isNullOrEmpty(contains)) {
                        if (value instanceof String && ((String) value).contains(contains)) {
                            success = true;
                        } else if (value instanceof List && ((List) value).contains(contains)) {
                            success = true;
                        }
                    } else if (isNullOrEmpty(equals) && isNullOrEmpty(contains)) {
                        success = true;
                    }
                }
            }
        } catch (OgnlException ignored) {
        }

        state = success ? ExecutionState.SUCCESS : ExecutionState.FAIL;
        return state;
    }

    @Override
    public ExecutionState getExecutionState() {
        return state;
    }

    @Override
    public IBehaviorCondition clone() {
        DynamicValueMatcher clone = new DynamicValueMatcher(memoryItemConverter);
        clone.setConfigs(getConfigs());
        return clone;
    }
}
