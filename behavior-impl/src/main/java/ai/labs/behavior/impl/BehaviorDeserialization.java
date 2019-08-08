package ai.labs.behavior.impl;

import ai.labs.behavior.impl.BehaviorGroup.ExecutionStrategy;
import ai.labs.behavior.impl.conditions.IBehaviorCondition;
import ai.labs.resources.rest.config.behavior.model.BehaviorConfiguration;
import ai.labs.resources.rest.config.behavior.model.BehaviorRuleConditionConfiguration;
import ai.labs.serialization.DeserializationException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import javax.inject.Provider;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static ai.labs.utilities.RuntimeUtilities.checkNotNull;
import static ai.labs.utilities.RuntimeUtilities.isNullOrEmpty;

/**
 * @author ginccc
 */
@Slf4j
public class BehaviorDeserialization implements IBehaviorDeserialization {
    private final ObjectMapper objectMapper;
    private final Map<String, Provider<IBehaviorCondition>> conditionProvider;

    @Inject
    public BehaviorDeserialization(ObjectMapper objectMapper,
                                   Map<String, Provider<IBehaviorCondition>> conditionProvider) {
        this.objectMapper = objectMapper;
        this.conditionProvider = conditionProvider;
    }

    @Override
    public BehaviorSet deserialize(String json) throws DeserializationException {
        try {
            BehaviorSet behaviorSet = new BehaviorSet();
            BehaviorConfiguration behaviorJson = objectMapper.readerFor(BehaviorConfiguration.class).readValue(json);

            behaviorSet.getBehaviorGroups().addAll(behaviorJson.getBehaviorGroups().stream().map(
                    groupConfiguration -> {
                        BehaviorGroup behaviorGroup = new BehaviorGroup();
                        behaviorGroup.setName(groupConfiguration.getName());
                        ExecutionStrategy executionStrategy;
                        String executionStrategyString = groupConfiguration.getExecutionStrategy();
                        if (isNullOrEmpty(executionStrategyString)) {
                            executionStrategy = ExecutionStrategy.executeUntilFirstSuccess;
                        } else {
                            executionStrategy = ExecutionStrategy.valueOf(executionStrategyString);
                        }

                        behaviorGroup.setExecutionStrategy(executionStrategy);

                        behaviorGroup.getBehaviorRules().addAll(groupConfiguration.getBehaviorRules().stream().map(
                                behaviorRuleJson -> {
                                    BehaviorRule behaviorRule = new BehaviorRule(behaviorRuleJson.getName());
                                    behaviorRule.setActions(behaviorRuleJson.getActions());
                                    behaviorRule.setConditions(convert(behaviorRuleJson.getConditions(), behaviorSet));
                                    return behaviorRule;
                                }
                        ).collect(Collectors.toList()));

                        return behaviorGroup;
                    }
            ).collect(Collectors.toList()));

            return behaviorSet;
        } catch (IOException e) {
            throw new DeserializationException(e.getLocalizedMessage(), e);
        }
    }

    private List<IBehaviorCondition> convert(List<BehaviorRuleConditionConfiguration> conditionConfigs,
                                             BehaviorSet behaviorSet) {
        return conditionConfigs.stream().map(
                conditionConfiguration -> {
                    try {
                        String type = conditionConfiguration.getType();
                        checkNotNull(type, "behaviorRule.condition.type");

                        String key = IBehaviorCondition.CONDITION_PREFIX + type;
                        if (!conditionProvider.containsKey(key)) {
                            String errorMessage = String.format("behaviorRule.condition.type=%s does not exist", key);
                            throw new IllegalArgumentException(errorMessage);
                        }
                        IBehaviorCondition condition = conditionProvider.get(key).get();
                        var configs = conditionConfiguration.getConfigs();
                        if (!isNullOrEmpty(configs)) {
                            condition.setConfigs(configs);
                        }
                        var conditions = conditionConfiguration.getConditions();
                        if (!isNullOrEmpty(conditions)) {
                            var behaviorConditions = convert(conditions, behaviorSet);
                            List<IBehaviorCondition> conditionsClone = deepCopy(behaviorConditions);
                            condition.setConditions(conditionsClone);
                        }
                        condition.setContainingBehaviorRuleSet(behaviorSet);
                        return condition;
                    } catch (CloneNotSupportedException e) {
                        log.error(e.getLocalizedMessage(), e);
                        return null;
                    }
                }
        ).collect(Collectors.toList());
    }

    private List<IBehaviorCondition> deepCopy(List<IBehaviorCondition> behaviorConditionList)
            throws CloneNotSupportedException {
        List<IBehaviorCondition> executablesClone = new LinkedList<>();

        //deep copy
        for (IBehaviorCondition behaviorCondition : behaviorConditionList) {
            executablesClone.add(behaviorCondition.clone());
        }

        return executablesClone;
    }
}
