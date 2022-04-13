package ai.labs.eddi.modules.behavior.impl.conditions;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.modules.behavior.impl.BehaviorRule;
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
public class Occurrence implements IBehaviorCondition {
    public static final String ID = "occurrence";
    private static final String BEHAVIOR_RULES_SUCCESS = "behavior_rules:success";

    private final String behaviorRuleNameQualifier = "behaviorRuleName";
    private String behaviorRuleName;

    private final String maxTimesOccurredQualifier = "maxTimesOccurred";
    private final String minTimesOccurredQualifier = "minTimesOccurred";
    @Setter
    private int maxTimesOccurred = -1;
    @Setter
    private int minTimesOccurred = -1;

    private int countTimesOccurred(List<List<String>> allBehaviorRulesHistorical) {
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

    private List<List<String>> getAllBehaviorRules(List<List<IData<List<String>>>> allData) {
        List<List<String>> allBehaviorRules = new LinkedList<>();
        for (List<IData<List<String>>> dataList : allData) {
            for (IData<List<String>> data : dataList) {
                allBehaviorRules.add(data.getResult());
            }
        }

        return allBehaviorRules;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public Map<String, String> getConfigs() {
        HashMap<String, String> configs = new HashMap<>();
        configs.put(maxTimesOccurredQualifier, String.valueOf(maxTimesOccurred));
        configs.put(minTimesOccurredQualifier, String.valueOf(minTimesOccurred));
        configs.put(behaviorRuleNameQualifier, behaviorRuleName);
        return configs;
    }

    @Override
    public void setConfigs(Map<String, String> configs) {
        if (configs != null && !configs.isEmpty()) {
            if (configs.containsKey(behaviorRuleNameQualifier)) {
                behaviorRuleName = configs.get(behaviorRuleNameQualifier);
            }

            if (configs.containsKey(maxTimesOccurredQualifier)) {
                int timesOccurred = Integer.parseInt(configs.get(maxTimesOccurredQualifier));
                setMaxTimesOccurred(timesOccurred);
            }

            if (configs.containsKey(minTimesOccurredQualifier)) {
                int timesOccurred = Integer.parseInt(configs.get(minTimesOccurredQualifier));
                setMinTimesOccurred(timesOccurred);
            }

        }
    }

    @Override
    public ExecutionState execute(IConversationMemory memory, List<BehaviorRule> trace) {
        boolean success;
        List<List<IData<List<String>>>> allData = memory.getAllSteps().getAllData(BEHAVIOR_RULES_SUCCESS);
        if (allData != null) {
            final int actualTimesOccurred = countTimesOccurred(getAllBehaviorRules(allData));
            boolean isMin = true;
            boolean isMax = true;

            if (minTimesOccurred != -1) {
                isMin = actualTimesOccurred >= minTimesOccurred;
            }

            if (maxTimesOccurred != -1) {
                isMax = actualTimesOccurred <= maxTimesOccurred;
            }

            success = isMin && isMax;
        } else {
            success = false;
        }

        return success ? ExecutionState.SUCCESS : ExecutionState.FAIL;
    }

    @Override
    public IBehaviorCondition clone() {
        IBehaviorCondition occurrence = new Occurrence();
        occurrence.setConfigs(getConfigs());

        return occurrence;
    }
}
