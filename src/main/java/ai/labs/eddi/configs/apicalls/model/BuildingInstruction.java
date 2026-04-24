/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.apicalls.model;

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
