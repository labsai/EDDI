package io.sls.resources.rest.behavior.model;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */

@Getter
@Setter
@EqualsAndHashCode
public class BehaviorRuleConfiguration {
    private String name;
    private List<String> actions;
    private List<BehaviorRuleElementConfiguration> children;

    public BehaviorRuleConfiguration() {
        actions = new LinkedList<>();
        children = new ArrayList<>();
    }
}
