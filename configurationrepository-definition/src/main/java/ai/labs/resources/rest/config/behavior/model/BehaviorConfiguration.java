package ai.labs.resources.rest.config.behavior.model;

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
public class BehaviorConfiguration {
    private List<BehaviorGroupConfiguration> behaviorGroups = new LinkedList<>();
}
