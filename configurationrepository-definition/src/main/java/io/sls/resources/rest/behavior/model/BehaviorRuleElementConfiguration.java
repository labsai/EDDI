package io.sls.resources.rest.behavior.model;


import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * @author ginccc
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class BehaviorRuleElementConfiguration {
    private String type;
    private Map<String, String> values;
    private List<BehaviorRuleElementConfiguration> children;

    public BehaviorRuleElementConfiguration() {
        values = new HashMap<>();
        children = new LinkedList<>();
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getType() {
        return type;
    }

    public Map<String, String> getValues() {
        return values;
    }

    public List<BehaviorRuleElementConfiguration> getChildren() {
        return children;
    }
}
