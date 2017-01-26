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
public class Occurrence implements IExtension {
    public static final String ID = "occurrence";
    private static final String BEHAVIOR_RULES_SUCCESS = "behavior_rules:success";

    private final String behaviorRuleNameQualifier = "behaviorRuleName";
    private String behaviorRuleName;

    private final String maxOccurrenceQualifier = "maxOccurrence";
    private int maxOccurrence = 1;

    private ExecutionState state = ExecutionState.NOT_EXECUTED;

    public Occurrence() {
    }

    public void setBehaviorRuleName(String behaviorRuleName) {
        this.behaviorRuleName = behaviorRuleName;
    }

    public void setMaxOccurrence(int maxOccurrence) {
        this.maxOccurrence = maxOccurrence;
    }

    public int countOccurrences(List<List<String>> allBehaviorRulesHistorical) {
        int occurrences = 0;
        for (List<String> history : allBehaviorRulesHistorical) {
            for (String behaviorRuleName : history) {
                if (this.behaviorRuleName.equals(behaviorRuleName)) {
                    occurrences++;
                }
            }
        }

        return occurrences;
    }

    private List<List<String>> getAllBehaviorRules(List<List<IData>> allData) {
        List<List<String>> allBehaviorRules = new LinkedList<List<String>>();
        for (List<IData> dataList : allData) {
            for (IData data : dataList) {
                allBehaviorRules.add((List<String>) data.getResult());
            }
        }

        return allBehaviorRules;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public Map<String, String> getValues() {
        HashMap<String, String> result = new HashMap<String, String>();
        result.put(maxOccurrenceQualifier, String.valueOf(maxOccurrence));
        result.put(behaviorRuleNameQualifier, behaviorRuleName);
        return result;
    }

    @Override
    public void setValues(Map<String, String> values) {
        if (values != null && !values.isEmpty()) {
            if (values.containsKey(behaviorRuleNameQualifier)) {
                behaviorRuleName = values.get(behaviorRuleNameQualifier);
            }

            if (values.containsKey(maxOccurrenceQualifier)) {
                String occurrenceValue = values.get(maxOccurrenceQualifier);
                if ("ever".equals(occurrenceValue)) {
                    setMaxOccurrence(-1);
                } else {
                    setMaxOccurrence(Integer.parseInt(occurrenceValue));
                }
            }
        }
    }

    @Override
    public IExtension[] getChildren() {
        return new IExtension[0];
    }

    @Override
    public void setChildren(IExtension... extensions) {
        //not implemented
    }

    @Override
    public ExecutionState execute(IConversationMemory memory, List<BehaviorRule> trace) {
        boolean success;
        List<List<IData>> allData = memory.getAllSteps().getAllData(BEHAVIOR_RULES_SUCCESS);
        if (allData != null) {
            int actualOccurrences = countOccurrences(getAllBehaviorRules(allData));
            if (maxOccurrence == -1) {
                //it did occurred at least once
                success = actualOccurrences > 0;
            } else {
                success = maxOccurrence >= actualOccurrences;
            }
        } else {
            success = false;
        }

        if (success) {
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

    @Override
    public IExtension clone() throws CloneNotSupportedException {
        IExtension occurrence = new Occurrence();
        occurrence.setValues(getValues());

        return occurrence;
    }

    @Override
    public void setContainingBehaviorRuleSet(BehaviorSet behaviorSet) {
        //not implemented
    }
}
