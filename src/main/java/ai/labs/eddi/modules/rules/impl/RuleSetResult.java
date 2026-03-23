package ai.labs.eddi.modules.rules.impl;

import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */
public class RuleSetResult {
    private List<Rule> successRules = new LinkedList<>();
    private List<Rule> droppedSuccessRules = new LinkedList<>();
    private List<Rule> failRules = new LinkedList<>();

    public RuleSetResult() {
    }

    public List<Rule> getSuccessRules() {
        return successRules;
    }

    public void setSuccessRules(List<Rule> successRules) {
        this.successRules = successRules;
    }

    public List<Rule> getDroppedSuccessRules() {
        return droppedSuccessRules;
    }

    public void setDroppedSuccessRules(List<Rule> droppedSuccessRules) {
        this.droppedSuccessRules = droppedSuccessRules;
    }

    public List<Rule> getFailRules() {
        return failRules;
    }

    public void setFailRules(List<Rule> failRules) {
        this.failRules = failRules;
    }

    @Override
    public String toString() {
        return "RuleSetResult(" + "successRules=" + successRules + ", droppedSuccessRules=" + droppedSuccessRules + ", failRules=" + failRules + ")";
    }
}
