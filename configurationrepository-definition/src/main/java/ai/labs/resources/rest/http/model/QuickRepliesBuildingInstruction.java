package ai.labs.resources.rest.http.model;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class QuickRepliesBuildingInstruction extends BuildingInstruction {
    private String quickReplyValue;
    private String quickReplyExpressions;

    public QuickRepliesBuildingInstruction() {
        super();
        quickReplyValue = "";
        quickReplyExpressions = "";
    }
}
