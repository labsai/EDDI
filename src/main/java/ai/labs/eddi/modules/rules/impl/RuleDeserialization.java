/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rules.impl;

import ai.labs.eddi.configs.rules.model.RuleSetConfiguration;
import ai.labs.eddi.configs.rules.model.RuleConditionConfiguration;
import ai.labs.eddi.datastore.serialization.DeserializationException;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.modules.rules.impl.RuleGroup.ExecutionStrategy;
import ai.labs.eddi.modules.rules.impl.conditions.*;
import ai.labs.eddi.configs.agents.CapabilityRegistryService;
import ai.labs.eddi.modules.nlp.expressions.utilities.IExpressionProvider;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;

import static ai.labs.eddi.modules.rules.impl.conditions.IRuleCondition.CONDITION_PREFIX;
import static ai.labs.eddi.utils.LogSanitizer.sanitize;
import static ai.labs.eddi.utils.RuntimeUtilities.checkNotNull;
import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;
import static java.lang.String.format;

/**
 * @author ginccc
 */

@ApplicationScoped
public class RuleDeserialization implements IRuleDeserialization {
    private final ObjectMapper objectMapper;

    private static final Logger log = Logger.getLogger(RuleDeserialization.class);
    private final IExpressionProvider expressionProvider;
    private final IJsonSerialization jsonSerialization;
    private final IMemoryItemConverter memoryItemConverter;
    private final ITemplatingEngine templatingEngine;
    private final CapabilityRegistryService capabilityRegistryService;

    @Inject
    public RuleDeserialization(ObjectMapper objectMapper, IExpressionProvider expressionProvider, IJsonSerialization jsonSerialization,
            IMemoryItemConverter memoryItemConverter,
            CapabilityRegistryService capabilityRegistryService, ITemplatingEngine templatingEngine) {
        this.objectMapper = objectMapper;
        this.expressionProvider = expressionProvider;
        this.jsonSerialization = jsonSerialization;
        this.memoryItemConverter = memoryItemConverter;
        this.templatingEngine = templatingEngine;
        this.capabilityRegistryService = capabilityRegistryService;
    }

    @Override
    public RuleSet deserialize(String json, String rulesetName) throws DeserializationException {
        return deserialize(json, rulesetName, false);
    }

    @Override
    public void validateForSave(String json) throws DeserializationException {
        deserialize(json, null, true);
    }

    /**
     * @param forSave
     *            {@code true} when an author is saving the ruleset: a retired
     *            condition config is refused. {@code false} when a stored ruleset
     *            is read to run (or an existing one is imported): it is accepted
     *            with a WARN, so an agent that deployed before the config was
     *            retired keeps deploying, and behaving, as before.
     */
    private RuleSet deserialize(String json, String rulesetName, boolean forSave) throws DeserializationException {
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
                    behaviorRule.setConditions(
                            convert(rulesetName, behaviorRuleJson.getName(), behaviorRuleJson.getConditions(), behaviorSet, forSave));
                    return behaviorRule;
                }).toList());

                return behaviorGroup;
            }).toList());

            return behaviorSet;
        } catch (IOException e) {
            throw new DeserializationException(e.getLocalizedMessage(), e);
        }
    }

    private List<IRuleCondition> convert(String rulesetName, String ruleName, List<RuleConditionConfiguration> conditionConfigs,
                                         RuleSet behaviorSet, boolean forSave) {
        return conditionConfigs.stream().map(conditionConfiguration -> {
            try {
                var type = conditionConfiguration.getType();
                checkNotNull(type, "behaviorRule.condition.type");

                var conditionsKey = CONDITION_PREFIX + type;

                // Factory creates all known condition types
                IRuleCondition condition = createCondition(conditionsKey);

                if (condition != null) {
                    // An unusable config must be refused here — a condition that cannot
                    // evaluate itself would otherwise degrade into an always-matching rule.
                    try {
                        var configs = conditionConfiguration.getConfigs();
                        if (!isNullOrEmpty(configs)) {
                            condition.setConfigs(configs);
                        }
                        var conditions = conditionConfiguration.getConditions();
                        if (!isNullOrEmpty(conditions)) {
                            var behaviorConditions = convert(rulesetName, ruleName, conditions, behaviorSet, forSave);
                            List<IRuleCondition> conditionsClone = deepCopy(behaviorConditions);
                            condition.setConditions(conditionsClone);
                        }
                        condition.setContainingRuleSet(behaviorSet);
                        condition.validateConfiguration();
                        String retired = condition.retiredConfiguration();
                        if (retired != null) {
                            if (forSave) {
                                throw new IllegalArgumentException(retired);
                            }
                            warnRetired(rulesetName, ruleName, type, retired);
                        }
                    } catch (IllegalArgumentException e) {
                        throw new DeserializationException(
                                format("Invalid '%s' condition in behavior rule '%s': %s", type, ruleName, e.getMessage()), e);
                    }

                    return condition;
                }

                throw new DeserializationException(format("No condition for type %s was created (%s)", type, conditionsKey));
            } catch (CloneNotSupportedException | DeserializationException e) {
                log.error(e.getLocalizedMessage(), e);
                throw new IllegalArgumentException("Failed to instantiate rule condition: " + e.getMessage(), e);
            }
        }).filter(Objects::nonNull).toList();
    }

    /**
     * The ruleset is stored already, so refusing it would stop the agent from
     * deploying as a whole. The condition keeps evaluating as it did before the
     * config was retired; the next save of the ruleset refuses it.
     */
    private static void warnRetired(String rulesetName, String ruleName, String type, String reason) {
        log.warnf("Ruleset %s, behavior rule '%s': retired '%s' condition config accepted because the ruleset is already stored;"
                + " it evaluates as before (the condition never matches). Saving this ruleset is refused until it is fixed: %s",
                rulesetName == null ? "(unnamed)" : sanitize(rulesetName), sanitize(String.valueOf(ruleName)), sanitize(type), reason);
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
            case CONDITION_PREFIX + CapabilityMatchCondition.ID ->
                new CapabilityMatchCondition(capabilityRegistryService, memoryItemConverter, templatingEngine);
            case CONDITION_PREFIX + ContentTypeMatcher.ID -> new ContentTypeMatcher();
            case CONDITION_PREFIX + DeploymentContextCondition.ID -> new DeploymentContextCondition();
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
