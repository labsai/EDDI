package ai.labs.eddi.engine.memory.model;

import ai.labs.eddi.datastore.serialization.Id;
import ai.labs.eddi.models.ConversationState;
import ai.labs.eddi.models.Deployment;
import ai.labs.eddi.models.Property;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.*;

import java.util.*;

/**
 * @author ginccc
 */
@Getter
@Setter
public class ConversationMemorySnapshot {
    private String conversationId;
    private String botId;
    private Integer botVersion;
    private String userId;
    private Deployment.Environment environment;
    private ConversationState conversationState;
    private List<ConversationOutput> conversationOutputs = new LinkedList<>();
    private Map<String, Property> conversationProperties = new LinkedHashMap<>();
    private List<ConversationStepSnapshot> conversationSteps = new LinkedList<>();
    private Stack<ConversationStepSnapshot> redoCache = new Stack<>();

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        ConversationMemorySnapshot that = (ConversationMemorySnapshot) o;

        return Objects.equals(conversationSteps, that.conversationSteps);
    }

    @Override
    public int hashCode() {
        return conversationSteps != null ? conversationSteps.hashCode() : 0;
    }

    public Map<String, Property> getConversationProperties() {
        return conversationProperties;
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

    @Getter
    @Setter
    public static class ConversationStepSnapshot {
        private List<PackageRunSnapshot> packages = new LinkedList<>();

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            ConversationStepSnapshot that = (ConversationStepSnapshot) o;

            return Objects.equals(packages, that.packages);
        }

        @Override
        public int hashCode() {
            return packages != null ? packages.hashCode() : 0;
        }
    }

    @Getter
    @Setter
    public static class PackageRunSnapshot {
        private List<ResultSnapshot> lifecycleTasks = new LinkedList<>();

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            PackageRunSnapshot that = (PackageRunSnapshot) o;

            return Objects.equals(lifecycleTasks, that.lifecycleTasks);
        }

        @Override
        public int hashCode() {
            return 31 * (lifecycleTasks != null ? lifecycleTasks.hashCode() : 0);
        }
    }

    @NoArgsConstructor
    @AllArgsConstructor
    @Getter
    @Setter
    @ToString
    public static class ResultSnapshot {
        private String key;
        private Object result;
        private List possibleResults;
        private Date timestamp;
        private String originPackageId;
        private boolean isPublic;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

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
    }
}
