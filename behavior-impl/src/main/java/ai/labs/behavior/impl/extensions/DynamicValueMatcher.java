package ai.labs.behavior.impl.extensions;

import ai.labs.behavior.impl.BehaviorRule;
import ai.labs.memory.IConversationMemory;
import ai.labs.memory.IMemoryItemConverter;
import lombok.extern.slf4j.Slf4j;
import ognl.NoSuchPropertyException;
import ognl.Ognl;
import ognl.OgnlException;

import javax.inject.Inject;
import java.util.List;
import java.util.Map;

import static ai.labs.utilities.RuntimeUtilities.isNullOrEmpty;

@Slf4j
public class DynamicValueMatcher implements IBehaviorExtension {
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
    public void setValues(Map<String, String> values) {
        if (values != null && !values.isEmpty()) {
            if (values.containsKey(valuePathQualifier)) {
                valuePath = values.get(valuePathQualifier);
            }

            if (values.containsKey(containsQualifier)) {
                contains = values.get(containsQualifier);
            }

            if (values.containsKey(equalsQualifier)) {
                equals = values.get(equalsQualifier);
            }
        }
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
        } catch (NoSuchPropertyException e) {
            log.info(e.getLocalizedMessage());
        } catch (OgnlException e) {
            log.error(e.getLocalizedMessage(), e);
        }

        state = success ? ExecutionState.SUCCESS : ExecutionState.FAIL;
        return state;
    }

    @Override
    public ExecutionState getExecutionState() {
        return state;
    }

    @Override
    public IBehaviorExtension clone() {
        return new DynamicValueMatcher(memoryItemConverter);
    }
}
