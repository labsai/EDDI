package ai.labs.property.impl;

import ai.labs.models.PropertyInstruction;
import lombok.Getter;
import lombok.Setter;

import java.util.LinkedList;
import java.util.List;

@Getter
@Setter
public class ActionProperties {
    private List<String> actions = new LinkedList<>();
    private List<PropertyInstruction> setProperties = new LinkedList<>();
}
