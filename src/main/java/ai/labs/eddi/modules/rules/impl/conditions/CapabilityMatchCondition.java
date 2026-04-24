/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rules.impl.conditions;

import ai.labs.eddi.configs.agents.CapabilityRegistryService;
import ai.labs.eddi.configs.agents.CapabilityRegistryService.CapabilityMatch;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.modules.rules.impl.Rule;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Behavior rule condition that matches agents with a required capability skill.
 * <p>
 * When this condition evaluates to SUCCESS, the matching agent IDs are stored
 * in the conversation memory as {@code capabilityMatch.results} so downstream
 * tasks (e.g., group conversation orchestration) can use the discovered agents.
 * <p>
 * Config values support template variables (Jinja2 syntax), resolved against
 * the current conversation memory at execution time. For example:
 * <ul>
 * <li>{@code skill} can be
 * {@code {{properties.requiredSkill.valueString}}}</li>
 * <li>{@code strategy} can be {@code {{context.routingStrategy}}}</li>
 * </ul>
 * <p>
 * Config keys:
 * <ul>
 * <li>{@code skill} — required skill name (e.g., "language-translation" or a
 * template expression)</li>
 * <li>{@code strategy} — selection strategy: "highest_confidence",
 * "round_robin", "all"</li>
 * <li>{@code minResults} — minimum number of matching agents required (default:
 * 1)</li>
 * </ul>
 *
 * Example behavior.json:
 *
 * <pre>
 * {
 *   "type": "capabilityMatch",
 *   "configs": {
 *     "skill": "language-translation",
 *     "strategy": "highest_confidence",
 *     "minResults": "1"
 *   }
 * }
 * </pre>
 *
 * Example with template variables:
 *
 * <pre>
 * {
 *   "type": "capabilityMatch",
 *   "configs": {
 *     "skill": "{{properties.requiredSkill.valueString}}",
 *     "strategy": "highest_confidence",
 *     "minResults": "1"
 *   }
 * }
 * </pre>
 *
 * @since 6.0.0
 */
public class CapabilityMatchCondition implements IRuleCondition {
    public static final String ID = "capabilityMatch";
    private static final Logger LOGGER = Logger.getLogger(CapabilityMatchCondition.class);
    private static final String KEY_SKILL = "skill";
    private static final String KEY_STRATEGY = "strategy";
    private static final String KEY_MIN_RESULTS = "minResults";
    private static final String MEMORY_KEY = "capabilityMatch.results";

    private String skill;
    private String strategy = "highest_confidence";
    private int minResults = 1;

    private final CapabilityRegistryService registryService;
    private final IMemoryItemConverter memoryItemConverter;
    private final ITemplatingEngine templatingEngine;

    public CapabilityMatchCondition(CapabilityRegistryService registryService,
            IMemoryItemConverter memoryItemConverter,
            ITemplatingEngine templatingEngine) {
        this.registryService = registryService;
        this.memoryItemConverter = memoryItemConverter;
        this.templatingEngine = templatingEngine;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public Map<String, String> getConfigs() {
        Map<String, String> configs = new HashMap<>();
        if (skill != null) {
            configs.put(KEY_SKILL, skill);
        }
        configs.put(KEY_STRATEGY, strategy);
        configs.put(KEY_MIN_RESULTS, String.valueOf(minResults));
        return configs;
    }

    @Override
    public void setConfigs(Map<String, String> configs) {
        if (configs != null) {
            skill = configs.get(KEY_SKILL);
            if (configs.containsKey(KEY_STRATEGY)) {
                strategy = configs.get(KEY_STRATEGY);
            }
            if (configs.containsKey(KEY_MIN_RESULTS)) {
                try {
                    minResults = Integer.parseInt(configs.get(KEY_MIN_RESULTS));
                } catch (NumberFormatException e) {
                    minResults = 1;
                }
            }
        }
    }

    @Override
    public ExecutionState execute(IConversationMemory memory, List<Rule> trace) {
        if (skill == null || skill.isBlank()) {
            return ExecutionState.FAIL;
        }

        // Resolve template variables in config values against current memory
        String resolvedSkill = resolveTemplate(skill, memory);
        String resolvedStrategy = resolveTemplate(strategy, memory);

        if (resolvedSkill == null || resolvedSkill.isBlank()) {
            return ExecutionState.FAIL;
        }

        List<CapabilityMatch> matches = registryService.findBySkill(resolvedSkill, resolvedStrategy);

        if (matches.size() >= minResults) {
            // Store matched agent IDs in memory for downstream tasks
            List<String> matchedAgentIds = matches.stream()
                    .map(CapabilityMatch::agentId)
                    .toList();

            var data = new ai.labs.eddi.engine.memory.model.Data<>(MEMORY_KEY, matchedAgentIds);
            memory.getCurrentStep().storeData(data);

            return ExecutionState.SUCCESS;
        }

        return ExecutionState.FAIL;
    }

    /**
     * Resolve Jinja2 template expressions (e.g., {{properties.foo.valueString}})
     * against the current conversation memory. Returns the raw value unchanged if
     * it contains no template markers or if template resolution fails.
     */
    private String resolveTemplate(String value, IConversationMemory memory) {
        if (value == null || !value.contains("{{")) {
            return value;
        }
        try {
            Map<String, Object> templateData = memoryItemConverter.convert(memory);
            return templatingEngine.processTemplate(value, templateData);
        } catch (ITemplatingEngine.TemplateEngineException e) {
            LOGGER.warnf("Template resolution failed for '%s': %s", value, e.getMessage());
            return value; // Fall back to raw value
        }
    }

    @Override
    public IRuleCondition clone() throws CloneNotSupportedException {
        CapabilityMatchCondition clone = new CapabilityMatchCondition(
                registryService, memoryItemConverter, templatingEngine);
        clone.setConfigs(this.getConfigs());
        return clone;
    }
}
