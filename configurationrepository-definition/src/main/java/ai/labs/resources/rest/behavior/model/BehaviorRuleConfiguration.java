package ai.labs.resources.rest.behavior.model;

import com.fasterxml.jackson.annotation.JsonProperty;
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
  /*  @JsonProperty("children")*/
    private List<BehaviorRuleElementConfiguration> conditions;

    public BehaviorRuleConfiguration() {
        actions = new LinkedList<>();
        conditions = new ArrayList<>();
    }
}
