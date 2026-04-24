/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rules.model;

import com.fasterxml.jackson.annotation.JsonAlias;

import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */

public class RuleGroupConfiguration {
    private String name;
    private String executionStrategy;
    private List<RuleConfiguration> behaviorRules = new LinkedList<>();

    public RuleGroupConfiguration() {
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getExecutionStrategy() {
        return executionStrategy;
    }

    public void setExecutionStrategy(String executionStrategy) {
        this.executionStrategy = executionStrategy;
    }

    public List<RuleConfiguration> getRules() {
        return behaviorRules;
    }

    @JsonAlias("behaviorRules")
    public void setRules(List<RuleConfiguration> behaviorRules) {
        this.behaviorRules = behaviorRules;
    }
}
