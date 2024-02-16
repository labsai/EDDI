package ai.labs.eddi.modules.properties.model;

import ai.labs.eddi.configs.properties.model.PropertyInstruction;
import lombok.Getter;
import lombok.Setter;

import java.util.LinkedList;
import java.util.List;

@Getter
@Setter
public class SetOnActions {
    private List<String> actions = new LinkedList<>();
    private List<PropertyInstruction> setProperties = new LinkedList<>();
}
