package io.sls.resources.rest.behavior.model;


import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.Setter;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * @author ginccc
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Getter
@Setter
public class BehaviorRuleElementConfiguration {
    private String type;
    private Map<String, String> values;
    private List<BehaviorRuleElementConfiguration> children;

    public BehaviorRuleElementConfiguration() {
        values = new HashMap<>();
        children = new LinkedList<>();
    }
}
