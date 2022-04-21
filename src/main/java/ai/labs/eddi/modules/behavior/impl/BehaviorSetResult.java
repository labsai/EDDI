package ai.labs.eddi.modules.behavior.impl;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */
@NoArgsConstructor
@Getter
@Setter
@ToString
public class BehaviorSetResult {
    private List<BehaviorRule> successRules = new LinkedList<>();
    private List<BehaviorRule> droppedSuccessRules = new LinkedList<>();
    private List<BehaviorRule> failRules = new LinkedList<>();
}
