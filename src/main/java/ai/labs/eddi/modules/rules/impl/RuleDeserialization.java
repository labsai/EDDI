package ai.labs.eddi.modules.rules.impl;

import ai.labs.eddi.configs.rules.model.RuleSetConfiguration;
import ai.labs.eddi.configs.rules.model.RuleConditionConfiguration;
import ai.labs.eddi.datastore.serialization.DeserializationException;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.modules.rules.bootstrap.RuleConditions;
import ai.labs.eddi.modules.rules.impl.RuleGroup.ExecutionStrategy;
import ai.labs.eddi.modules.rules.impl.conditions.*;
import ai.labs.eddi.configs.agents.CapabilityRegistryService;
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

import static ai.labs.eddi.modules.rules.impl.conditions.IRuleCondition.CONDITION_PREFIX;
import static ai.labs.eddi.utils.RuntimeUtilities.checkNotNull;
import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;
import static java.lang.String.format;

/**
 * @author ginccc
 */

@ApplicationScoped
public class RuleDeserialization implements IRuleDeserialization {
    private final ObjectMapper objectMapper;
    private final Map<String, Provider<IRuleCondition>> conditionProvider;

    private static final Logger log = Logger.getLogger(RuleDeserialization.class);
    private final IExpressionProvider expressionProvider;
    private final IJsonSerialization jsonSerialization;
    private final IMemoryItemConverter memoryItemConverter;
    private final CapabilityRegistryService capabilityRegistryService;

    @Inject
    public RuleDeserialization(ObjectMapper objectMapper, IExpressionProvider expressionProvider, IJsonSerialization jsonSerialization,
            IMemoryItemConverter memoryItemConverter, @RuleConditions Map<String, Provider<IRuleCondition>> conditionProvider,
            CapabilityRegistryService capabilityRegistryService) {
        this.objectMapper = objectMapper;
        this.expressionProvider = expressionProvider;
        this.conditionProvider = conditionProvider;
        this.jsonSerialization = jsonSerialization;
        this.memoryItemConverter = memoryItemConverter;
        this.capabilityRegistryService = capabilityRegistryService;
    }

    @Override
    public RuleSet deserialize(String json) throws DeserializationException {
        try {
            RuleSet behaviorSet = new RuleSet();
            RuleSetConfiguration behaviorJson = objectMapper.readerFor(RuleSetConfiguration.class).readValue(json);

            behaviorSet.getRuleGroups().addAll(behaviorJson.getBehaviorGroups().stream().map(groupConfiguration -> {
                RuleGroup behaviorGroup = new RuleGroup();
                behaviorGroup.setName(groupConfiguration.getName());
                ExecutionStrategy executionStrategy;
                String executionStrategyString = groupConfiguration.getExecutionStrategy();
                if (isNullOrEmpty(executionStrategyString)) {
                    executionStrategy = ExecutionStrategy.executeUntilFirstSuccess;
                } else {
                    executionStrategy = ExecutionStrategy.valueOf(executionStrategyString);
                }

                behaviorGroup.setExecutionStrategy(executionStrategy);

                behaviorGroup.getRules().addAll(groupConfiguration.getRules().stream().map(behaviorRuleJson -> {
                    Rule behaviorRule = new Rule(behaviorRuleJson.getName());
                    behaviorRule.setActions(behaviorRuleJson.getActions());
                    behaviorRule.setConditions(convert(behaviorRuleJson.getConditions(), behaviorSet));
                    return behaviorRule;
                }).toList());

                return behaviorGroup;
            }).toList());

            return behaviorSet;
        } catch (IOException e) {
            throw new DeserializationException(e.getLocalizedMessage(), e);
        }
    }

    private List<IRuleCondition> convert(List<RuleConditionConfiguration> conditionConfigs, RuleSet behaviorSet) {
        return conditionConfigs.stream().map(conditionConfiguration -> {
            try {
                var type = conditionConfiguration.getType();
                checkNotNull(type, "behaviorRule.condition.type");

                var conditionsKey = CONDITION_PREFIX + type;

                // Try direct factory first (handles both CDI and non-CDI conditions)
                IRuleCondition condition = createCondition(conditionsKey);

                // Fall back to CDI provider map for extensibility
                if (condition == null && conditionProvider.containsKey(conditionsKey)) {
                    condition = conditionProvider.get(conditionsKey).get();
                }

                if (condition != null) {
                    var configs = conditionConfiguration.getConfigs();
                    if (!isNullOrEmpty(configs)) {
                        condition.setConfigs(configs);
                    }
                    var conditions = conditionConfiguration.getConditions();
                    if (!isNullOrEmpty(conditions)) {
                        var behaviorConditions = convert(conditions, behaviorSet);
                        List<IRuleCondition> conditionsClone = deepCopy(behaviorConditions);
                        condition.setConditions(conditionsClone);
                    }
                    condition.setContainingRuleSet(behaviorSet);
                    return condition;
                }

                throw new DeserializationException(format("No condition for type %s was created (%s)", type, conditionsKey));
            } catch (CloneNotSupportedException | DeserializationException e) {
                log.error(e.getLocalizedMessage(), e);
                return null;
            }
        }).toList();
    }

    private IRuleCondition createCondition(String conditionsKey) {
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
            case CONDITION_PREFIX + CapabilityMatchCondition.ID -> new CapabilityMatchCondition(capabilityRegistryService);
            case CONDITION_PREFIX + ContentTypeMatcher.ID -> new ContentTypeMatcher();
            default -> null;
        };

    }

    private List<IRuleCondition> deepCopy(List<IRuleCondition> behaviorConditionList) throws CloneNotSupportedException {
        List<IRuleCondition> executablesClone = new LinkedList<>();

        // deep copy
        for (IRuleCondition behaviorCondition : behaviorConditionList) {
            executablesClone.add(behaviorCondition.clone());
        }

        return executablesClone;
    }
}
