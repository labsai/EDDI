package ai.labs.eddi.engine.memory.model;

import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.models.ConversationState;
import ai.labs.eddi.models.Deployment;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.Date;
import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */

@Getter
@Setter
public class SimpleConversationMemorySnapshot {
    private String conversationId;
    private String botId;
    private Integer botVersion;
    private String userId;
    private Deployment.Environment environment;
    private ConversationState conversationState;
    private boolean undoAvailable;
    private boolean redoAvailable;
    private List<ConversationOutput> conversationOutputs = new LinkedList<>();
    private IConversationMemory.IConversationProperties conversationProperties = new ConversationProperties(null);
    private List<SimpleConversationStep> conversationSteps = new LinkedList<>();

    @Getter
    @Setter
    public static class SimpleConversationStep {
        private List<ConversationStepData> conversationStep = new LinkedList<>();
        private Date timestamp;
    }

    @AllArgsConstructor
    @Getter
    @Setter
    public static class ConversationStepData {
        private String key;
        private Object value;
        private Date timestamp;
        private String originPackageId;
    }
}
