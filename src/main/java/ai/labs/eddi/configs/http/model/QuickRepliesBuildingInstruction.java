package ai.labs.eddi.configs.http.model;

import ai.labs.eddi.models.HttpCodeValidator;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class QuickRepliesBuildingInstruction extends BuildingInstruction {
    private String quickReplyValue;
    private String quickReplyExpressions;
    private HttpCodeValidator httpCodeValidator;

    public QuickRepliesBuildingInstruction() {
        super();
        quickReplyValue = "";
        quickReplyExpressions = "";
        httpCodeValidator = new HttpCodeValidator();
    }
}
