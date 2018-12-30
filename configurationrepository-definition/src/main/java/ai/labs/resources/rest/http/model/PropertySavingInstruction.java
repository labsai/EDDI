package ai.labs.resources.rest.http.model;

import ai.labs.models.Property;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@NoArgsConstructor
@Getter
@Setter
public class PropertySavingInstruction extends BuildingInstruction {
    private String propertyName = "";
    private String path = "";
    private String scope = Property.Scope.conversation.toString();
}
