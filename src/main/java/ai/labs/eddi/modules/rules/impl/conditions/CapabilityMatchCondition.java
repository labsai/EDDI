package ai.labs.eddi.modules.rules.impl.conditions;

import ai.labs.eddi.configs.agents.CapabilityRegistryService;
import ai.labs.eddi.configs.agents.CapabilityRegistryService.CapabilityMatch;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.modules.rules.impl.Rule;

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
 * Config keys:
 * <ul>
 * <li>{@code skill} — required skill name (e.g., "language-translation")</li>
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
 *   "config": {
 *     "skill": "language-translation",
 *     "strategy": "highest_confidence",
 *     "minResults": "1"
 *   }
 * }
 * </pre>
 *
 * @since 6.0.0
 */
public class CapabilityMatchCondition implements IRuleCondition {
    public static final String ID = "capabilitymatch";
    private static final String KEY_SKILL = "skill";
    private static final String KEY_STRATEGY = "strategy";
    private static final String KEY_MIN_RESULTS = "minResults";
    private static final String MEMORY_KEY = "capabilityMatch.results";

    private String skill;
    private String strategy = "highest_confidence";
    private int minResults = 1;

    private final CapabilityRegistryService registryService;

    public CapabilityMatchCondition(CapabilityRegistryService registryService) {
        this.registryService = registryService;
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

        List<CapabilityMatch> matches = registryService.findBySkill(skill, strategy);

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

    @Override
    public IRuleCondition clone() throws CloneNotSupportedException {
        CapabilityMatchCondition clone = new CapabilityMatchCondition(registryService);
        clone.setConfigs(this.getConfigs());
        return clone;
    }
}
