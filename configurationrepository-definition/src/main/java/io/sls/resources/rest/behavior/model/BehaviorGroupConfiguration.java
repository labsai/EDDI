package io.sls.resources.rest.behavior.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.LinkedList;
import java.util.List;

/**
 * User: jarisch
 * Date: 10.06.12
 * Time: 21:51
 */
@JsonIgnoreProperties({"id", "children", "selected", "opened", "editable"})
public class BehaviorGroupConfiguration {
    private String name;
    private List<BehaviorRuleConfiguration> behaviorRules;

    public BehaviorGroupConfiguration() {
        behaviorRules = new LinkedList<BehaviorRuleConfiguration>();
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<BehaviorRuleConfiguration> getBehaviorRules() {
        return behaviorRules;
    }

    public void setBehaviorRules(List<BehaviorRuleConfiguration> behaviorRules) {
        this.behaviorRules = behaviorRules;
    }
}

