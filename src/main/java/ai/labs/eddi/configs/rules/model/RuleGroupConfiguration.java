/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.rules.model;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonProperty;

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

    /**
     * The group's rules, on the wire as {@code behaviorRules}.
     *
     * <p>
     * The accessor pair is named {@code getRules}/{@code setRules}, so without the
     * explicit {@link JsonProperty} Jackson serialised this list as {@code rules}
     * while every author-facing artefact writes {@code behaviorRules} — the shipped
     * reference configuration, the ZIP import fixtures, the documentation and the
     * Manager's rules editor alike. The alias made writes work either way, so the
     * mismatch only ever showed up on <em>reads</em>: a rule set posted as
     * {@code behaviorRules} came back as {@code rules}, and the Manager, which
     * types the field as {@code behaviorRules}, rendered every group as "No rules
     * in this group" no matter what it contained.
     * </p>
     *
     * <p>
     * {@code rules} stays accepted as an alias, so stored documents written before
     * this — every rule set in every existing deployment — keep loading unchanged,
     * as do the API clients that learned the old spelling.
     * </p>
     */
    @JsonProperty("behaviorRules")
    public List<RuleConfiguration> getRules() {
        return behaviorRules;
    }

    @JsonProperty("behaviorRules")
    @JsonAlias("rules")
    public void setRules(List<RuleConfiguration> behaviorRules) {
        this.behaviorRules = behaviorRules;
    }
}
