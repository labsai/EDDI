package io.sls.resources.rest.behavior.model;

import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */
public class BehaviorConfiguration {
    private List<BehaviorGroupConfiguration> behaviorGroups;

    public BehaviorConfiguration() {
        behaviorGroups = new LinkedList<BehaviorGroupConfiguration>();
    }

    public List<BehaviorGroupConfiguration> getBehaviorGroups() {
        return behaviorGroups;
    }

    public void setBehaviorGroups(List<BehaviorGroupConfiguration> behaviorGroups) {
        this.behaviorGroups = behaviorGroups;
    }
}
