package ai.labs.resources.rest.behavior.model;

import lombok.Getter;
import lombok.Setter;

import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */

@Getter
@Setter
public class BehaviorConfiguration {
    private List<BehaviorGroupConfiguration> behaviorGroups;

    public BehaviorConfiguration() {
        behaviorGroups = new LinkedList<>();
    }
}
