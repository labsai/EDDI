package ai.labs.eddi.configs.behavior.model;

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
public class BehaviorGroupConfiguration {
    private String name;
    private String executionStrategy;
    private List<BehaviorRuleConfiguration> behaviorRules = new LinkedList<>();
}

