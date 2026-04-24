/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.model;

import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;

import static ai.labs.eddi.engine.model.Deployment.Status;

public class AgentDeploymentStatus {
    private Environment environment = Environment.production;
    private String agentId;
    private Integer agentVersion;
    private Status status = Status.NOT_FOUND;
    private DocumentDescriptor descriptor;

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
}
