package ai.labs.eddi.modules.behavior.impl;


import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */
public class BehaviorSet {
    private List<BehaviorGroup> behaviorGroups = new LinkedList<>();

    public BehaviorSet() {
    }

    public List<BehaviorGroup> getBehaviorGroups() {
        return behaviorGroups;
    }

    public void setBehaviorGroups(List<BehaviorGroup> behaviorGroups) {
        this.behaviorGroups = behaviorGroups;
    }
}
