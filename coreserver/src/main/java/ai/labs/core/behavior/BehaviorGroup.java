package ai.labs.core.behavior;

import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */
public class BehaviorGroup {
    private String name;
    private List<BehaviorRule> behaviorRules;

    public BehaviorGroup() {
        behaviorRules = new LinkedList<BehaviorRule>();
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public List<BehaviorRule> getBehaviorRules() {
        return behaviorRules;
    }

    public void setBehaviorRules(List<BehaviorRule> behaviorRules) {
        this.behaviorRules = behaviorRules;
    }
}
