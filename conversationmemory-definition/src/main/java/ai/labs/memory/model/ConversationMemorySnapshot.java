package ai.labs.memory.model;


import ai.labs.models.ConversationState;
import ai.labs.models.Deployment;
import lombok.*;

import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Stack;

/**
 * @author ginccc
 */
@Getter
@Setter
public class ConversationMemorySnapshot {
    private String id;
    private String botId;
    private Integer botVersion;
    private Deployment.Environment environment;
    private ConversationState conversationState;
    private List<ConversationStepSnapshot> conversationSteps = new LinkedList<>();
    private Stack<ConversationStepSnapshot> redoCache = new Stack<>();

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

    @Getter
    @Setter
    public static class ConversationStepSnapshot {
        private List<PackageRunSnapshot> packages = new LinkedList<>();

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

    @Getter
    @Setter
    public static class PackageRunSnapshot {
        private String context;
        private List<ResultSnapshot> lifecycleTasks = new LinkedList<>();

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            PackageRunSnapshot that = (PackageRunSnapshot) o;

            if (context != null ? context.equals(that.context) : that.context == null) {
                return lifecycleTasks != null ? lifecycleTasks.equals(that.lifecycleTasks) : that.lifecycleTasks == null;
            }
            return false;
        }

        @Override
        public int hashCode() {
            int result = context != null ? context.hashCode() : 0;
            result = 31 * result + (lifecycleTasks != null ? lifecycleTasks.hashCode() : 0);
            return result;
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
        private boolean isPublic;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            ResultSnapshot that = (ResultSnapshot) o;

            if (key != null ? key.equals(that.key) : that.key == null) {
                return possibleResults != null ? possibleResults.equals(that.possibleResults) : that.possibleResults == null;
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
