package ai.labs.property.impl;

import ai.labs.models.PropertyInstruction;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class SetOnActions {
    private List<String> actions;
    private List<PropertyInstruction> propertyInstruction;
}
