package ai.labs.eddi.configs.rules.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */

public class RuleSetConfiguration {
    private Boolean appendActions;
    private Boolean expressionsAsActions;

    @JsonProperty("behaviorGroups")
    @JsonAlias("ruleGroups")
    private List<RuleGroupConfiguration> behaviorGroups = new LinkedList<>();

    public RuleSetConfiguration() {
    }

    public Boolean getAppendActions() {
        return appendActions;
    }

    public void setAppendActions(Boolean appendActions) {
        this.appendActions = appendActions;
    }

    public Boolean getExpressionsAsActions() {
        return expressionsAsActions;
    }

    public void setExpressionsAsActions(Boolean expressionsAsActions) {
        this.expressionsAsActions = expressionsAsActions;
    }

    public List<RuleGroupConfiguration> getBehaviorGroups() {
        return behaviorGroups;
    }

    public void setBehaviorGroups(List<RuleGroupConfiguration> behaviorGroups) {
        this.behaviorGroups = behaviorGroups;
    }
}
