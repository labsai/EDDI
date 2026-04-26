/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.apicalls.model;

import ai.labs.eddi.configs.properties.model.PropertyInstruction;

import java.util.List;

public class PostResponse {
    private List<PropertyInstruction> propertyInstructions;
    private List<OutputBuildingInstruction> outputBuildInstructions;
    private List<QuickRepliesBuildingInstruction> qrBuildInstructions;

    public List<PropertyInstruction> getPropertyInstructions() {
        return propertyInstructions;
    }

    public void setPropertyInstructions(List<PropertyInstruction> propertyInstructions) {
        this.propertyInstructions = propertyInstructions;
    }

    public List<OutputBuildingInstruction> getOutputBuildInstructions() {
        return outputBuildInstructions;
    }

    public void setOutputBuildInstructions(List<OutputBuildingInstruction> outputBuildInstructions) {
        this.outputBuildInstructions = outputBuildInstructions;
    }

    public List<QuickRepliesBuildingInstruction> getQrBuildInstructions() {
        return qrBuildInstructions;
    }

    public void setQrBuildInstructions(List<QuickRepliesBuildingInstruction> qrBuildInstructions) {
        this.qrBuildInstructions = qrBuildInstructions;
    }
}
