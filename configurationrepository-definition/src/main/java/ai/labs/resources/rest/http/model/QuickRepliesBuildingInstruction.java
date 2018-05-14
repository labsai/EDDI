package ai.labs.resources.rest.http.model;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class QuickRepliesBuildingInstruction {
    private String pathToTargetArray;
    private String iterationObjectName;
    private String quickReplyValue;
    private String quickReplyExpressions;

    public QuickRepliesBuildingInstruction() {
        iterationObjectName = "obj";
        quickReplyValue = "";
        quickReplyExpressions = "";
    }
}
