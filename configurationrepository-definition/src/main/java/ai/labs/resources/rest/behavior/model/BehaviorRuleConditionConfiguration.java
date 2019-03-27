package ai.labs.resources.rest.behavior.model;


import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;
import java.util.Map;

/**
 * @author ginccc
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@NoArgsConstructor
@Getter
@Setter
public class BehaviorRuleConditionConfiguration {
    private String type;
    private Map<String, String> values;
    private List<BehaviorRuleConditionConfiguration> conditions;
}
