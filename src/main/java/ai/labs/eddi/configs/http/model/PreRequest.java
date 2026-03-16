package ai.labs.eddi.configs.http.model;

import ai.labs.eddi.configs.properties.model.PropertyInstruction;

import java.util.List;

public class PreRequest {
    private List<PropertyInstruction> propertyInstructions;

    public List<PropertyInstruction> getPropertyInstructions() {
        return propertyInstructions;
    }

    public void setPropertyInstructions(List<PropertyInstruction> propertyInstructions) {
        this.propertyInstructions = propertyInstructions;
    }
}
