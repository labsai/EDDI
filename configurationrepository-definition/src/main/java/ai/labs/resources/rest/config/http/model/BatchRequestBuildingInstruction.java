package ai.labs.resources.rest.config.http.model;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class BatchRequestBuildingInstruction extends BuildingInstruction {
    private Boolean executeCallsSequentially;
}
