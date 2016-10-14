package io.sls.resources.rest.behavior.model;


import com.fasterxml.jackson.databind.annotation.JsonSerialize;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * User: Michael
 * Date: 01.04.12
 * Time: 15:45
 */
@JsonSerialize(include = JsonSerialize.Inclusion.NON_NULL)
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
