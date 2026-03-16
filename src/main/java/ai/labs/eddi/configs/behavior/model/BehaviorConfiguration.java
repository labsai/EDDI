package ai.labs.eddi.configs.behavior.model;


import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */

public class BehaviorConfiguration {
    private Boolean appendActions;
    private Boolean expressionsAsActions;
    private List<BehaviorGroupConfiguration> behaviorGroups = new LinkedList<>();

    public BehaviorConfiguration() {
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

    public List<BehaviorGroupConfiguration> getBehaviorGroups() {
        return behaviorGroups;
    }

    public void setBehaviorGroups(List<BehaviorGroupConfiguration> behaviorGroups) {
        this.behaviorGroups = behaviorGroups;
    }
}
