package io.sls.resources.rest.behavior.model;

import lombok.Getter;
import lombok.Setter;

import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */

@Getter
@Setter
public class BehaviorGroupConfiguration {
    private String name;
    private List<BehaviorRuleConfiguration> behaviorRules;

    public BehaviorGroupConfiguration() {
        behaviorRules = new LinkedList<>();
    }
}

