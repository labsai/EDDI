package ai.labs.eddi.engine.model;

import ai.labs.eddi.model.Deployment.Environment;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;

import static ai.labs.eddi.model.Deployment.Status;

public class BotDeploymentStatus {
    private Environment environment = Environment.unrestricted;
    private String botId;
    private Integer botVersion;
    private Status status = Status.NOT_FOUND;
    private DocumentDescriptor descriptor;

    public BotDeploymentStatus() {
    }

    public BotDeploymentStatus(Environment environment, String botId, Integer botVersion, Status status, DocumentDescriptor descriptor) {
        this.environment = environment;
        this.botId = botId;
        this.botVersion = botVersion;
        this.status = status;
        this.descriptor = descriptor;
    }

    public Environment getEnvironment() {
        return environment;
    }

    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    public String getBotId() {
        return botId;
    }

    public void setBotId(String botId) {
        this.botId = botId;
    }

    public Integer getBotVersion() {
        return botVersion;
    }

    public void setBotVersion(Integer botVersion) {
        this.botVersion = botVersion;
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
