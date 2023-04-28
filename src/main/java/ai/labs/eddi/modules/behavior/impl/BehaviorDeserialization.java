package ai.labs.eddi.modules.behavior.impl;

import ai.labs.eddi.configs.behavior.model.BehaviorConfiguration;
import ai.labs.eddi.configs.behavior.model.BehaviorRuleConditionConfiguration;
import ai.labs.eddi.datastore.serialization.DeserializationException;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.modules.behavior.bootstrap.BehaviorConditions;
import ai.labs.eddi.modules.behavior.impl.BehaviorGroup.ExecutionStrategy;
import ai.labs.eddi.modules.behavior.impl.conditions.*;
import ai.labs.eddi.modules.nlp.expressions.utilities.IExpressionProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static ai.labs.eddi.modules.behavior.impl.conditions.IBehaviorCondition.CONDITION_PREFIX;
import static ai.labs.eddi.utils.RuntimeUtilities.checkNotNull;
import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;
import static java.lang.String.format;

/**
 * @author ginccc
 */

@ApplicationScoped
public class BehaviorDeserialization implements IBehaviorDeserialization {
    private final ObjectMapper objectMapper;
    private final Map<String, Provider<IBehaviorCondition>> conditionProvider;

    private static final Logger log = Logger.getLogger(BehaviorDeserialization.class);
    private final IExpressionProvider expressionProvider;
    private final IJsonSerialization jsonSerialization;
    private final IMemoryItemConverter memoryItemConverter;

    @Inject
    public BehaviorDeserialization(ObjectMapper objectMapper,
                                   IExpressionProvider expressionProvider,
                                   IJsonSerialization jsonSerialization,
                                   IMemoryItemConverter memoryItemConverter,
                                   @BehaviorConditions Map<String, Provider<IBehaviorCondition>> conditionProvider) {
        this.objectMapper = objectMapper;
        this.expressionProvider = expressionProvider;
        this.conditionProvider = conditionProvider;
        this.jsonSerialization = jsonSerialization;
        this.memoryItemConverter = memoryItemConverter;
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
                        ).toList());

                        return behaviorGroup;
                    }
            ).toList());

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
                        var type = conditionConfiguration.getType();
                        checkNotNull(type, "behaviorRule.condition.type");

                        var conditionsKey = CONDITION_PREFIX + type;
                        if (!conditionProvider.containsKey(conditionsKey)) {
                            var errorMessage = format("behaviorRule.condition.type=%s does not exist", conditionsKey);
                            throw new IllegalArgumentException(errorMessage);
                        }
                        IBehaviorCondition condition = createCondition(conditionsKey);
                        var configs = conditionConfiguration.getConfigs();
                        if (condition != null) {
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
                        }

                        throw new DeserializationException(
                                format("No condition for type %s was created (%s)", type, conditionsKey));
                    } catch (CloneNotSupportedException | DeserializationException e) {
                        log.error(e.getLocalizedMessage(), e);
                        return null;
                    }
                }
        ).collect(Collectors.toList());
    }

    private IBehaviorCondition createCondition(String conditionsKey) {
        return switch (conditionsKey) {
            case CONDITION_PREFIX + InputMatcher.ID -> new InputMatcher(expressionProvider);
            case CONDITION_PREFIX + ActionMatcher.ID -> new ActionMatcher();
            case CONDITION_PREFIX + Connector.ID -> new Connector();
            case CONDITION_PREFIX + Negation.ID -> new Negation();
            case CONDITION_PREFIX + ContextMatcher.ID -> new ContextMatcher(expressionProvider, jsonSerialization);
            case CONDITION_PREFIX + Occurrence.ID -> new Occurrence();
            case CONDITION_PREFIX + DynamicValueMatcher.ID -> new DynamicValueMatcher(memoryItemConverter);
            case CONDITION_PREFIX + SizeMatcher.ID -> new SizeMatcher(memoryItemConverter);
            case CONDITION_PREFIX + Dependency.ID -> new Dependency();
            default -> null;
        };

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
