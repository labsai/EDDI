package ai.labs.eddi.configs.behavior.model;

import lombok.EqualsAndHashCode;
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
@EqualsAndHashCode
public class BehaviorRuleConfiguration {
    private String name = "";
    private List<String> actions = new LinkedList<>();
    private List<BehaviorRuleConditionConfiguration> conditions = new LinkedList<>();
}
