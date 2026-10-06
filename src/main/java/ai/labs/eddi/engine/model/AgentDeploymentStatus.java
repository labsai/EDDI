/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.model;

import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;

import java.util.List;

import static ai.labs.eddi.engine.model.Deployment.Status;

public class AgentDeploymentStatus {
    private Environment environment = Environment.production;
    private String agentId;
    private Integer agentVersion;
    private Status status = Status.NOT_FOUND;
    private DocumentDescriptor descriptor;
    private List<String> warnings = List.of();

    public AgentDeploymentStatus() {
    }

    public AgentDeploymentStatus(Environment environment, String agentId, Integer agentVersion, Status status, DocumentDescriptor descriptor) {
        this.environment = environment;
        this.agentId = agentId;
        this.agentVersion = agentVersion;
        this.status = status;
        this.descriptor = descriptor;
    }

    public Environment getEnvironment() {
        return environment;
    }

    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    public String getAgentId() {
        return agentId;
    }

    public void setAgentId(String agentId) {
        this.agentId = agentId;
    }

    public Integer getAgentVersion() {
        return agentVersion;
    }

    public void setAgentVersion(Integer agentVersion) {
        this.agentVersion = agentVersion;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public DocumentDescriptor getDescriptor() {
        return descriptor;
    }

    public void setDescriptor(DocumentDescriptor descriptor) {
        this.descriptor = descriptor;
    }

    /**
     * Advisory findings of the deploy-time compatibility lint (configuration that
     * behaves differently than it did in 5.x). Never affects {@link #getStatus()}.
     */
    public List<String> getWarnings() {
        return warnings;
    }

    public void setWarnings(List<String> warnings) {
        this.warnings = warnings == null ? List.of() : warnings;
    }
}
