package ai.labs.core.behavior;

import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */
public class BehaviorSetResult {
    private List<BehaviorRule> successRules;
    private List<BehaviorRule> droppedSuccessRules;
    private List<BehaviorRule> failRules;

    public BehaviorSetResult() {
        successRules = new LinkedList<BehaviorRule>();
        droppedSuccessRules = new LinkedList<BehaviorRule>();
        failRules = new LinkedList<BehaviorRule>();
    }

    public List<BehaviorRule> getSuccessRules() {
        return successRules;
    }

    public void setSuccessRules(List<BehaviorRule> successRules) {
        this.successRules = successRules;
    }

    public List<BehaviorRule> getDroppedSuccessRules() {
        return droppedSuccessRules;
    }

    public void setDroppedSuccessRules(List<BehaviorRule> droppedSuccessRules) {
        this.droppedSuccessRules = droppedSuccessRules;
    }

    public List<BehaviorRule> getFailRules() {
        return failRules;
    }

    public void setFailRules(List<BehaviorRule> failRules) {
        this.failRules = failRules;
    }

    @Override
    public String toString() {
        return "BehaviorSetResult{" +
                "successRules=" + successRules +
                ", droppedSuccessRules=" + droppedSuccessRules +
                ", failRules=" + failRules +
                '}';
    }
}
