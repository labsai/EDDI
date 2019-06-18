package ai.labs.resources.rest.config.behavior.model;

import com.fasterxml.jackson.annotation.JsonAlias;
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
    @JsonAlias("children")
    private List<BehaviorRuleConditionConfiguration> conditions = new LinkedList<>();
}
