package io.sls.core.behavior;

import java.util.LinkedList;
import java.util.List;

/**
 * User: jarisch
 * Date: 04.08.2009
 * Time: 14:12:33
 */
public class BehaviorSet {
    private List<BehaviorGroup> behaviorGroups = new LinkedList<BehaviorGroup>();

    public BehaviorSet() {
    }

    public List<BehaviorRule> getBehaviorRule(String behaviorRule) {
        List<BehaviorRule> ret = new LinkedList<BehaviorRule>();
        for (BehaviorGroup behaviorGroup : behaviorGroups) {
            for (BehaviorRule status : behaviorGroup.getBehaviorRules()) {
                if (status.getName().equals(behaviorRule)) {
                    ret.add(status);
                }
            }
        }

        return ret;
    }

    public List<BehaviorGroup> getBehaviorGroups() {
        return behaviorGroups;
    }

    public void setBehaviorGroups(List<BehaviorGroup> behaviorGroups) {
        this.behaviorGroups = behaviorGroups;
    }
}
