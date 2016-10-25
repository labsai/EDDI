package io.sls.memory.model;


import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Stack;

/**
 * @author ginccc
 */
public class ConversationMemorySnapshot {
    private String id;
    private String botId;
    private Integer botVersion;

    private Deployment.Environment environment;
    private ConversationState conversationState;

    private List<ConversationStepSnapshot> conversationSteps;
    private Stack<ConversationStepSnapshot> redoCache;

    public ConversationMemorySnapshot() {
        this.conversationSteps = new LinkedList<>();
        this.redoCache = new Stack<>();
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
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

    public List<ConversationStepSnapshot> getConversationSteps() {
        return conversationSteps;
    }

    public void setConversationSteps(List<ConversationStepSnapshot> conversationSteps) {
        this.conversationSteps = conversationSteps;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        ConversationMemorySnapshot that = (ConversationMemorySnapshot) o;

        return conversationSteps != null ? conversationSteps.equals(that.conversationSteps) : that.conversationSteps == null;

    }

    @Override
    public int hashCode() {
        return conversationSteps != null ? conversationSteps.hashCode() : 0;
    }

    public void setRedoCache(Stack<ConversationStepSnapshot> redoCache) {
        this.redoCache = redoCache;
    }

    public Stack<ConversationStepSnapshot> getRedoCache() {
        return redoCache;
    }

    public static class ConversationStepSnapshot {
        private List<PackageRunSnapshot> packages;

        public ConversationStepSnapshot() {
            this.packages = new LinkedList<PackageRunSnapshot>();
        }

        public List<PackageRunSnapshot> getPackages() {
            return packages;
        }

        public void setPackages(List<PackageRunSnapshot> packages) {
            this.packages = packages;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            ConversationStepSnapshot that = (ConversationStepSnapshot) o;

            return packages != null ? packages.equals(that.packages) : that.packages == null;

        }

        @Override
        public int hashCode() {
            return packages != null ? packages.hashCode() : 0;
        }
    }

    public static class PackageRunSnapshot {
        private String context;
        private List<ResultSnapshot> lifecycleTasks;

        public PackageRunSnapshot() {
            this.lifecycleTasks = new LinkedList<ResultSnapshot>();
        }

        public List<ResultSnapshot> getLifecycleTasks() {
            return lifecycleTasks;
        }

        public void setLifecycleTasks(List<ResultSnapshot> lifecycleTasks) {
            this.lifecycleTasks = lifecycleTasks;
        }

        public String getContext() {
            return context;
        }

        public void setContext(String context) {
            this.context = context;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            PackageRunSnapshot that = (PackageRunSnapshot) o;

            if (context != null ? !context.equals(that.context) : that.context != null) return false;
            return lifecycleTasks != null ? lifecycleTasks.equals(that.lifecycleTasks) : that.lifecycleTasks == null;

        }

        @Override
        public int hashCode() {
            int result = context != null ? context.hashCode() : 0;
            result = 31 * result + (lifecycleTasks != null ? lifecycleTasks.hashCode() : 0);
            return result;
        }
    }

    public static class ResultSnapshot {
        private String key;
        private List possibleResults;
        private Object result;
        private Date timestamp;
        private boolean isPublic;

        public ResultSnapshot() {
        }

        public ResultSnapshot(String key, Object result, List possibleResults, Date timestamp, boolean isPublic) {
            this.key = key;
            this.result = result;
            this.possibleResults = possibleResults;
            this.timestamp = timestamp;
            this.isPublic = isPublic;
        }

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public List getPossibleResults() {
            return possibleResults;
        }

        public void setPossibleResults(List possibleResults) {
            this.possibleResults = possibleResults;
        }

        public Object getResult() {
            return result;
        }

        public void setResult(Object result) {
            this.result = result;
        }

        public Date getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(Date timestamp) {
            this.timestamp = timestamp;
        }

        public boolean isPublic() {
            return isPublic;
        }

        public void setPublic(boolean aPublic) {
            isPublic = aPublic;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            ResultSnapshot that = (ResultSnapshot) o;

            if (key != null ? !key.equals(that.key) : that.key != null) return false;
            return possibleResults != null ? possibleResults.equals(that.possibleResults) : that.possibleResults == null;

        }

        @Override
        public int hashCode() {
            int result = key != null ? key.hashCode() : 0;
            result = 31 * result + (possibleResults != null ? possibleResults.hashCode() : 0);
            return result;
        }

        @Override
        public String toString() {
            final StringBuilder sb = new StringBuilder();
            sb.append("{lifecycleTasks=").append(result).append('}');
            return sb.toString();
        }
    }
}
