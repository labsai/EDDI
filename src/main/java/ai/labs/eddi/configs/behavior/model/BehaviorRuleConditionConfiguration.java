package ai.labs.eddi.configs.behavior.model;


import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * @author ginccc
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BehaviorRuleConditionConfiguration {
    private String type;
    private Map<String, String> configs;
    private List<BehaviorRuleConditionConfiguration> conditions;

    public BehaviorRuleConditionConfiguration() {
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public Map<String, String> getConfigs() {
        return configs;
    }

    public void setConfigs(Map<String, String> configs) {
        this.configs = configs;
    }

    public List<BehaviorRuleConditionConfiguration> getConditions() {
        return conditions;
    }

    public void setConditions(List<BehaviorRuleConditionConfiguration> conditions) {
        this.conditions = conditions;
    }
}
