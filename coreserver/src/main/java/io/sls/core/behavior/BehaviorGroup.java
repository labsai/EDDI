package io.sls.core.behavior;

import java.util.LinkedList;
import java.util.List;

/**
 * User: jarisch
 * Date: 16.11.2010
 * Time: 23:10:51
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
