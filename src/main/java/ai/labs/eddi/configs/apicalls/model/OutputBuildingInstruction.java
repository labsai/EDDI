package ai.labs.eddi.configs.apicalls.model;

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

    public String getOutputType() {
        return outputType;
    }

    public void setOutputType(String outputType) {
        this.outputType = outputType;
    }

    public String getOutputValue() {
        return outputValue;
    }

    public void setOutputValue(String outputValue) {
        this.outputValue = outputValue;
    }

    public HttpCodeValidator getHttpCodeValidator() {
        return httpCodeValidator;
    }

    public void setHttpCodeValidator(HttpCodeValidator httpCodeValidator) {
        this.httpCodeValidator = httpCodeValidator;
    }
}
