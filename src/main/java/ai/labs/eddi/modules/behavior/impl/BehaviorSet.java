package ai.labs.eddi.modules.behavior.impl;

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
public class BehaviorSet {
    private List<BehaviorGroup> behaviorGroups = new LinkedList<>();
}
