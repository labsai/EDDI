package ai.labs.memory.model;

import java.util.Date;
import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */
public class SimpleConversationMemorySnapshot {
    private String botId;
    private Integer botVersion;
    private List<SimpleConversationStep> conversationSteps;

    private Deployment.Environment environment;
    private ConversationState conversationState;
    private int redoCacheSize;


    public SimpleConversationMemorySnapshot() {
        this.conversationSteps = new LinkedList<>();
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

    public List<SimpleConversationStep> getConversationSteps() {
        return conversationSteps;
    }

    public void setConversationSteps(List<SimpleConversationStep> conversationSteps) {
        this.conversationSteps = conversationSteps;
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

    public int getRedoCacheSize() {
        return redoCacheSize;
    }

    public void setRedoCacheSize(int redoCacheSize) {
        this.redoCacheSize = redoCacheSize;
    }

    public static class SimpleConversationStep {
        private List<SimpleData> data;
        private Date timestamp;

        public SimpleConversationStep() {
            this.data = new LinkedList<>();
        }

        public List<SimpleData> getData() {
            return data;
        }

        public void setData(List<SimpleData> data) {
            this.data = data;
        }

        public Date getTimestamp() {
            return timestamp;
        }

        public void setTimestamp(Date timestamp) {
            this.timestamp = timestamp;
        }
    }

    public static class SimpleData {
        private String key;
        private String value;

        public SimpleData(String key, String value) {
            this.key = key;
            this.value = value;
        }

        public String getKey() {
            return key;
        }

        public void setKey(String key) {
            this.key = key;
        }

        public String getValue() {
            return value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }
}
