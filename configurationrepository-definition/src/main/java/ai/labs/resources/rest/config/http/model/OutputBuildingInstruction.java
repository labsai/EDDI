package ai.labs.resources.rest.config.http.model;

import ai.labs.models.HttpCodeValidator;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class OutputBuildingInstruction extends BuildingInstruction {
    private String outputType;
    private String outputValue;
    private HttpCodeValidator httpCodeValidator;

    public OutputBuildingInstruction() {
        super();
        outputType = "";
        outputValue = "";
        httpCodeValidator = new HttpCodeValidator();
    }
}
