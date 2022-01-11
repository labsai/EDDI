package ai.labs.eddi.configs.propertysetter.model;

import ai.labs.eddi.models.SetOnActions;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.LinkedList;
import java.util.List;

@NoArgsConstructor
@Getter
@Setter
public class PropertySetterConfiguration {
    private List<SetOnActions> setOnActions = new LinkedList<>();
}
