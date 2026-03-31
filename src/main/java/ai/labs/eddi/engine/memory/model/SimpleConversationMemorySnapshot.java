package ai.labs.eddi.engine.memory.model;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.model.Deployment;

import java.util.Date;
import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */

public class SimpleConversationMemorySnapshot {
    private String conversationId;
    private String agentId;
    private Integer agentVersion;
    private String userId;
    private Deployment.Environment environment;
    private ConversationState conversationState;
    private boolean undoAvailable;
    private boolean redoAvailable;
    private List<ConversationOutput> conversationOutputs = new LinkedList<>();
    private IConversationMemory.IConversationProperties conversationProperties = new ConversationProperties(null);
    private List<SimpleConversationStep> conversationSteps = new LinkedList<>();

    public static class SimpleConversationStep {
        private List<ConversationStepData> conversationStep = new LinkedList<>();
        private Date timestamp;

        public List<ConversationStepData> getConversationStep() {
            return conversationStep;
        }

        public void setConversationStep(List<ConversationStepData> conversationStep) {
            this.conversationStep = conversationStep;
        }

        public Date getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(Date timestamp) {
            this.timestamp = timestamp;
        }
    }

    public static class ConversationStepData {
        private String key;
        private Object value;
        private Date timestamp;
        private String originWorkflowId;

        public ConversationStepData(String key, Object value, Date timestamp, String originWorkflowId) {
            this.key = key;
            this.value = value;
            this.timestamp = timestamp;
            this.originWorkflowId = originWorkflowId;
        }

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public Object getValue() {
            return value;
        }

        public void setValue(Object value) {
            this.value = value;
        }

        public Date getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(Date timestamp) {
            this.timestamp = timestamp;
        }

        public String getOriginWorkflowId() {
            return originWorkflowId;
        }

        public void setOriginWorkflowId(String originWorkflowId) {
            this.originWorkflowId = originWorkflowId;
        }
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
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

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public Deployment.Environment getEnvironment() {
        return environment;
    }

    public void setEnvironment(Deployment.Environment environment) {
        this.environment = environment;
    }

    public ConversationState getConversationState() {
        return conversationState;
    }

    public void setConversationState(ConversationState conversationState) {
        this.conversationState = conversationState;
    }

    public boolean isUndoAvailable() {
        return undoAvailable;
    }

    public void setUndoAvailable(boolean undoAvailable) {
        this.undoAvailable = undoAvailable;
    }

    public boolean isRedoAvailable() {
        return redoAvailable;
    }

    public void setRedoAvailable(boolean redoAvailable) {
        this.redoAvailable = redoAvailable;
    }

    public List<ConversationOutput> getConversationOutputs() {
        return conversationOutputs;
    }

    public void setConversationOutputs(List<ConversationOutput> conversationOutputs) {
        this.conversationOutputs = conversationOutputs;
    }

    public IConversationMemory.IConversationProperties getConversationProperties() {
        return conversationProperties;
    }

    public void setConversationProperties(IConversationMemory.IConversationProperties conversationProperties) {
        this.conversationProperties = conversationProperties;
    }

    public List<SimpleConversationStep> getConversationSteps() {
        return conversationSteps;
    }

    public void setConversationSteps(List<SimpleConversationStep> conversationSteps) {
        this.conversationSteps = conversationSteps;
    }
}
