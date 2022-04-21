package ai.labs.eddi.configs.http.model;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class BatchRequestBuildingInstruction extends BuildingInstruction {
    private Boolean executeCallsSequentially;
}
