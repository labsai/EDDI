package ai.labs.eddi.configs.http.model;

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
