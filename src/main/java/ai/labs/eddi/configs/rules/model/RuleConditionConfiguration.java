package ai.labs.eddi.configs.rules.model;


import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * @author ginccc
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RuleConditionConfiguration {
    private String type;
    private Map<String, String> configs;
    private List<RuleConditionConfiguration> conditions;

    public RuleConditionConfiguration() {
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

    public List<RuleConditionConfiguration> getConditions() {
        return conditions;
    }

    public void setConditions(List<RuleConditionConfiguration> conditions) {
        this.conditions = conditions;
    }
}
