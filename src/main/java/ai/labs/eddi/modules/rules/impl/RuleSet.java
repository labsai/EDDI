/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.rules.impl;

import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */
public class RuleSet {
    private List<RuleGroup> behaviorGroups = new LinkedList<>();

    public RuleSet() {
    }

    public List<RuleGroup> getRuleGroups() {
        return behaviorGroups;
    }

    public void setRuleGroups(List<RuleGroup> behaviorGroups) {
        this.behaviorGroups = behaviorGroups;
    }
}
