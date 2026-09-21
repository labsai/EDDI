/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rules.impl.conditions;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.modules.rules.impl.Rule;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * @author ginccc
 */

public class Occurrence implements IRuleCondition {
    public static final String ID = "occurrence";
    private static final String BEHAVIOR_RULES_SUCCESS = "behavior_rules:success";

    private final String behaviorRuleNameQualifier = "behaviorRuleName";
    private String behaviorRuleName;

    private final String maxTimesOccurredQualifier = "maxTimesOccurred";
    private final String minTimesOccurredQualifier = "minTimesOccurred";
    private int maxTimesOccurred = -1;
    private int minTimesOccurred = -1;

    private int countTimesOccurred(List<List<String>> allRulesHistorical) {
        int occurrences = 0;
        for (List<String> history : allRulesHistorical) {
            for (String behaviorRuleName : history) {
                // validateConfiguration() rejects a missing name at deserialization time,
                // but rulesets stored before that check exist — compare from the argument
                // side so an unnamed occurrence counts nothing instead of throwing.
                if (behaviorRuleName != null && behaviorRuleName.equals(this.behaviorRuleName)) {
                    occurrences++;
                }
            }
        }

        return occurrences;
    }

    private List<List<String>> getAllRules(List<List<IData<List<String>>>> allData) {
        List<List<String>> allRules = new LinkedList<>();
        for (List<IData<List<String>>> dataList : allData) {
            for (IData<List<String>> data : dataList) {
                allRules.add(data.getResult());
            }
        }

        return allRules;
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

    /**
     * Without a {@code behaviorRuleName} there is nothing to count (and counting
     * would dereference a {@code null}); without at least one of
     * {@code minTimesOccurred} / {@code maxTimesOccurred} every count is
     * acceptable, so the condition degrades into "matches as soon as any rule ever
     * succeeded". Both are configuration mistakes and are refused when the ruleset
     * is read.
     */
    @Override
    public void validateConfiguration() {
        if (behaviorRuleName == null || behaviorRuleName.isBlank()) {
            throw new IllegalArgumentException(String.format("'%s' requires a '%s' config value.", ID, behaviorRuleNameQualifier));
        }

        if (minTimesOccurred == -1 && maxTimesOccurred == -1) {
            throw new IllegalArgumentException(String.format("'%s' requires at least one of '%s' or '%s' — without a bound it matches"
                    + " as soon as any behavior rule has ever succeeded.", ID, minTimesOccurredQualifier, maxTimesOccurredQualifier));
        }
    }

    @Override
    public ExecutionState execute(IConversationMemory memory, List<Rule> trace) {
        boolean success;
        List<List<IData<List<String>>>> allData = memory.getAllSteps().getAllData(BEHAVIOR_RULES_SUCCESS);
        if (allData != null) {
            final int actualTimesOccurred = countTimesOccurred(getAllRules(allData));
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
    public IRuleCondition clone() {
        IRuleCondition occurrence = new Occurrence();
        occurrence.setConfigs(getConfigs());

        return occurrence;
    }

    public Occurrence() {
    }

    public void setMinTimesOccurred(int minTimesOccurred) {
        this.minTimesOccurred = minTimesOccurred;
    }

    public void setMaxTimesOccurred(int maxTimesOccurred) {
        this.maxTimesOccurred = maxTimesOccurred;
    }
}
