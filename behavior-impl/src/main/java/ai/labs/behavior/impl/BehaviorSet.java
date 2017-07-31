package ai.labs.behavior.impl;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */
@NoArgsConstructor
@Getter
@Setter
public class BehaviorSet {
    private List<BehaviorGroup> behaviorGroups = new LinkedList<>();

    public List<BehaviorRule> getBehaviorRule(String behaviorRule) {
        List<BehaviorRule> ret = new LinkedList<>();
        for (BehaviorGroup behaviorGroup : behaviorGroups) {
            for (BehaviorRule status : behaviorGroup.getBehaviorRules()) {
                if (status.getName().equals(behaviorRule)) {
                    ret.add(status);
                }
            }
        }

        return ret;
    }
}
