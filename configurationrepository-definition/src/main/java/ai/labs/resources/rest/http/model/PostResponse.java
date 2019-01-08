package ai.labs.resources.rest.http.model;

import ai.labs.models.PropertyInstruction;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class PostResponse {
    private PropertyInstruction propertyInstruction;
    private QuickRepliesBuildingInstruction qrBuildInstruction;
}
