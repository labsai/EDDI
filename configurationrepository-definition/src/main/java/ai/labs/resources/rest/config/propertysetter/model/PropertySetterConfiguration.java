package ai.labs.resources.rest.config.propertysetter.model;

import ai.labs.models.SetOnActions;
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
