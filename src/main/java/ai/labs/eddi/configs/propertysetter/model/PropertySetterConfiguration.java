package ai.labs.eddi.configs.propertysetter.model;

import ai.labs.eddi.modules.properties.model.SetOnActions;

import java.util.LinkedList;
import java.util.List;

public class PropertySetterConfiguration {
    private List<SetOnActions> setOnActions = new LinkedList<>();

    public PropertySetterConfiguration() {
    }

    public List<SetOnActions> getSetOnActions() {
        return setOnActions;
    }

    public void setSetOnActions(List<SetOnActions> setOnActions) {
        this.setOnActions = setOnActions;
    }
}
