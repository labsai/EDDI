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
public class BehaviorConfiguration {
    private Boolean appendActions;
    private Boolean expressionsAsActions;
    private List<BehaviorGroupConfiguration> behaviorGroups = new LinkedList<>();
}
