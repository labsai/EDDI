package ai.labs.eddi.engine.memory.model;

import ai.labs.eddi.datastore.serialization.Id;
import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.configs.properties.model.Property;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.*;

/**
 * @author ginccc
 */
public class ConversationMemorySnapshot {
    private String conversationId;
    private String agentId;
    private Integer agentVersion;
    private String userId;
    private Deployment.Environment environment;
    private ConversationState conversationState;
    private List<ConversationOutput> conversationOutputs = new LinkedList<>();
    private Map<String, Property> conversationProperties = new LinkedHashMap<>();
    private List<ConversationStepSnapshot> conversationSteps = new LinkedList<>();
    private Stack<ConversationStepSnapshot> redoCache = new Stack<>();

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;

        ConversationMemorySnapshot that = (ConversationMemorySnapshot) o;

        return Objects.equals(conversationSteps, that.conversationSteps);
    }

    @Override
    public int hashCode() {
        return conversationSteps != null ? conversationSteps.hashCode() : 0;
    }

    @JsonProperty("_id")
    @Id
    public String getId() {
        return conversationId;
    }

    @JsonProperty("_id")
    @Id
    public void setId(String conversationId) {
        this.conversationId = conversationId;
    }

    @JsonIgnore
    public String getConversationId() {
        return conversationId;
    }

    @JsonIgnore
    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public static class ConversationStepSnapshot {
        private List<WorkflowRunSnapshot> packages = new LinkedList<>();

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;

            ConversationStepSnapshot that = (ConversationStepSnapshot) o;

            return Objects.equals(packages, that.packages);
        }

        @Override
        public int hashCode() {
            return packages != null ? packages.hashCode() : 0;
        }

        public List<WorkflowRunSnapshot> getWorkflows() {
            return packages;
        }

        public void setWorkflows(List<WorkflowRunSnapshot> packages) {
            this.packages = packages;
        }

    }

    public static class WorkflowRunSnapshot {
        private List<ResultSnapshot> lifecycleTasks = new LinkedList<>();

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;

            WorkflowRunSnapshot that = (WorkflowRunSnapshot) o;

            return Objects.equals(lifecycleTasks, that.lifecycleTasks);
        }

        @Override
        public int hashCode() {
            return 31 * (lifecycleTasks != null ? lifecycleTasks.hashCode() : 0);
        }

        public List<ResultSnapshot> getLifecycleTasks() {
            return lifecycleTasks;
        }

        public void setLifecycleTasks(List<ResultSnapshot> lifecycleTasks) {
            this.lifecycleTasks = lifecycleTasks;
        }

    }

    public static class ResultSnapshot {
        private String key;
        private Object result;
        private List<?> possibleResults;
        private Date timestamp;
        private String originWorkflowId;
        private boolean isPublic;

        @Override
        public boolean equals(Object o) {
            if (this == o)
                return true;
            if (o == null || getClass() != o.getClass())
                return false;

            ResultSnapshot that = (ResultSnapshot) o;

            if (Objects.equals(key, that.key)) {
                return Objects.equals(possibleResults, that.possibleResults);
            }
            return false;
        }

        @Override
        public int hashCode() {
            int result = key != null ? key.hashCode() : 0;
            result = 31 * result + (possibleResults != null ? possibleResults.hashCode() : 0);
            return result;
        }

        public ResultSnapshot() {
        }

        public ResultSnapshot(String key, Object result, List<?> possibleResults, Date timestamp, String originWorkflowId, boolean isPublic) {
            this.key = key;
            this.result = result;
            this.possibleResults = possibleResults;
            this.timestamp = timestamp;
            this.originWorkflowId = originWorkflowId;
            this.isPublic = isPublic;
        }

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public Object getResult() {
            return result;
        }

        public void setResult(Object result) {
            this.result = result;
        }

        public List<?> getPossibleResults() {
            return possibleResults;
        }

        public void setPossibleResults(List<?> possibleResults) {
            this.possibleResults = possibleResults;
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

        public boolean isPublic() {
            return isPublic;
        }

        public void setPublic(boolean isPublic) {
            this.isPublic = isPublic;
        }

        @Override
        public String toString() {
            return "ResultSnapshot(" + "key=" + key + ", result=" + result + ", possibleResults=" + possibleResults + ", timestamp=" + timestamp
                    + ", originWorkflowId=" + originWorkflowId + ", isPublic=" + isPublic + ")";
        }
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

    public List<ConversationOutput> getConversationOutputs() {
        return conversationOutputs;
    }

    public void setConversationOutputs(List<ConversationOutput> conversationOutputs) {
        this.conversationOutputs = conversationOutputs;
    }

    public Map<String, Property> getConversationProperties() {
        return conversationProperties;
    }

    public void setConversationProperties(Map<String, Property> conversationProperties) {
        this.conversationProperties = conversationProperties;
    }

    public List<ConversationStepSnapshot> getConversationSteps() {
        return conversationSteps;
    }

    public void setConversationSteps(List<ConversationStepSnapshot> conversationSteps) {
        this.conversationSteps = conversationSteps;
    }

    public Stack<ConversationStepSnapshot> getRedoCache() {
        return redoCache;
    }

    public void setRedoCache(Stack<ConversationStepSnapshot> redoCache) {
        this.redoCache = redoCache;
    }
}
