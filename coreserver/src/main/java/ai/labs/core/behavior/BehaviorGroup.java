package ai.labs.core.behavior;

import lombok.Getter;
import lombok.Setter;

import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */
@Getter
@Setter
public class BehaviorGroup {
    private String name;
    private List<BehaviorRule> behaviorRules;

    BehaviorGroup() {
        behaviorRules = new LinkedList<>();
    }
}
