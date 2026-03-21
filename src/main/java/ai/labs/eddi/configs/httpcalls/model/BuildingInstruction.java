package ai.labs.eddi.configs.httpcalls.model;


public class BuildingInstruction {
    private String pathToTargetArray;
    private String iterationObjectName = "obj";
    private String templateFilterExpression = "";

    public String getPathToTargetArray() {
        return pathToTargetArray;
    }

    public void setPathToTargetArray(String pathToTargetArray) {
        this.pathToTargetArray = pathToTargetArray;
    }

    public String getIterationObjectName() {
        return iterationObjectName;
    }

    public void setIterationObjectName(String iterationObjectName) {
        this.iterationObjectName = iterationObjectName;
    }

    public String getTemplateFilterExpression() {
        return templateFilterExpression;
    }

    public void setTemplateFilterExpression(String templateFilterExpression) {
        this.templateFilterExpression = templateFilterExpression;
    }
}
