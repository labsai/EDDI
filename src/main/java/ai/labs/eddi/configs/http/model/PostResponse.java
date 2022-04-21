package ai.labs.eddi.configs.http.model;

import ai.labs.eddi.models.PropertyInstruction;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class PostResponse {
    private RetryHttpCallInstruction retryHttpCallInstruction;
    private List<PropertyInstruction> propertyInstructions;
    private List<OutputBuildingInstruction> outputBuildInstructions;
    private List<QuickRepliesBuildingInstruction> qrBuildInstructions;
}
