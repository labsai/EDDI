package ai.labs.eddi.configs.apicalls.model;


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

    public String getQuickReplyValue() {
        return quickReplyValue;
    }

    public void setQuickReplyValue(String quickReplyValue) {
        this.quickReplyValue = quickReplyValue;
    }

    public String getQuickReplyExpressions() {
        return quickReplyExpressions;
    }

    public void setQuickReplyExpressions(String quickReplyExpressions) {
        this.quickReplyExpressions = quickReplyExpressions;
    }

    public HttpCodeValidator getHttpCodeValidator() {
        return httpCodeValidator;
    }

    public void setHttpCodeValidator(HttpCodeValidator httpCodeValidator) {
        this.httpCodeValidator = httpCodeValidator;
    }
}
