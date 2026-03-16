package ai.labs.eddi.configs.http.model;


public class BatchRequestBuildingInstruction extends BuildingInstruction {
    private Boolean executeCallsSequentially;

    public Boolean getExecuteCallsSequentially() {
        return executeCallsSequentially;
    }

    public void setExecuteCallsSequentially(Boolean executeCallsSequentially) {
        this.executeCallsSequentially = executeCallsSequentially;
    }
}
