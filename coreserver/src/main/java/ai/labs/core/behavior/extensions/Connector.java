package ai.labs.core.behavior.extensions;

import ai.labs.core.behavior.BehaviorRule;
import ai.labs.core.behavior.BehaviorSet;
import ai.labs.memory.IConversationMemory;

import java.util.*;


/**
 * @author ginccc
 */
public class Connector implements IExtension {
    public static final String ID = "connector";
    private final String operatorQualifier = "operator";

    public enum Operator {
        OR, AND
    }

    protected Operator operator;

    private List<IExtension> extensions = new LinkedList<IExtension>();

    private ExecutionState state = ExecutionState.NOT_EXECUTED;

    public Connector() {
        this(Operator.OR); // default: OR
    }

    public Connector(String operator) {
        this(operator.equals("or") ? Operator.OR : Operator.AND);
    }

    public Connector(Operator operator) {
        this.operator = operator;
    }

    public void setExtensions(List<IExtension> extensions) {
        this.extensions = extensions;
    }

    public void addExecutable(IExtension extension) {
        extensions.add(extension);
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public Map<String, String> getValues() {
        Map<String, String> values = new HashMap<String, String>();
        values.put(operatorQualifier, operator.name());
        return values;
    }

    @Override
    public void setValues(Map<String, String> values) {
        if (values != null && !values.isEmpty()) {
            if (values.containsKey(operatorQualifier)) {
                String operator = values.get(operatorQualifier);
                if (operator.equals(Operator.AND.name())) {
                    this.operator = Operator.AND;
                } else {
                    this.operator = Operator.OR;
                }
            }
        }
    }


    @Override
    public IExtension[] getChildren() {
        return extensions.toArray(new IExtension[extensions.size()]);
    }

    public ExecutionState execute(IConversationMemory memory, List<BehaviorRule> trace) {
        if (operator == Operator.OR) {
            state = ExecutionState.FAIL;

            for (IExtension extension : extensions) {
                extension.execute(memory, trace);
                if (extension.getExecutionState() == ExecutionState.SUCCESS) {
                    state = ExecutionState.SUCCESS;
                    break;
                } else if (extension.getExecutionState() == ExecutionState.ERROR) {
                    state = ExecutionState.ERROR;
                    break;
                }
            }
        } else // if(operator == AND)
        {
            state = ExecutionState.SUCCESS;

            for (IExtension extension : extensions) {
                extension.execute(memory, trace);
                if (extension.getExecutionState() == ExecutionState.FAIL) {
                    state = ExecutionState.FAIL;
                    break;
                } else if (extension.getExecutionState() == ExecutionState.ERROR) {
                    state = ExecutionState.ERROR;
                    break;
                }
            }
        }

        return state;
    }

    public boolean isEmpty() {
        return extensions.isEmpty();
    }

    @Override
    public ExecutionState getExecutionState() {
        return state;
    }

    @Override
    public IExtension clone() throws CloneNotSupportedException {
        Connector clone = new Connector(operator);

        List<IExtension> extensionClone = new LinkedList<IExtension>();
        for (IExtension extension : extensions) {
            extensionClone.add(extension.clone());
        }

        clone.setExtensions(extensionClone);
        clone.setValues(getValues());

        return clone;
    }

    @Override
    public void setChildren(IExtension... extensions) {
        this.extensions.addAll(Arrays.asList(extensions));
    }

    @Override
    public void setContainingBehaviorRuleSet(BehaviorSet behaviorSet) {
        //not implemented
    }
}
