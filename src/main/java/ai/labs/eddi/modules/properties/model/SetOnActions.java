package ai.labs.eddi.modules.properties.model;

import ai.labs.eddi.configs.properties.model.PropertyInstruction;

import java.util.LinkedList;
import java.util.List;

public class SetOnActions {
    private List<String> actions = new LinkedList<>();
    private List<PropertyInstruction> setProperties = new LinkedList<>();

    public List<String> getActions() {
        return actions;
    }

    public void setActions(List<String> actions) {
        this.actions = actions;
    }

    public List<PropertyInstruction> getSetProperties() {
        return setProperties;
    }

    public void setSetProperties(List<PropertyInstruction> setProperties) {
        this.setProperties = setProperties;
    }
}
