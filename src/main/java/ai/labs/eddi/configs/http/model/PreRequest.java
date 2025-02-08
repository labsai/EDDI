package ai.labs.eddi.configs.http.model;

import ai.labs.eddi.configs.properties.model.PropertyInstruction;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class PreRequest {
    private List<PropertyInstruction> propertyInstructions;
}
