package ai.labs.eddi.engine.memory;

import ai.labs.eddi.engine.memory.model.ConversationOutput;
import ai.labs.eddi.engine.memory.model.ConversationProperties;
import ai.labs.eddi.models.ConversationState;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Stack;
import java.util.stream.Collectors;

/**
 * @author ginccc
 */
public class ConversationMemory implements IConversationMemory {
    private String conversationId;
    private final String botId;
    private final Integer botVersion;
    private String userId;
    private IWritableConversationStep currentStep;
    private final Stack<IConversationStep> previousSteps;
    private final Stack<IConversationStep> redoCache = new Stack<>();
    private final Stack<ConversationOutput> conversationOutputs = new Stack<>();
    private final IConversationProperties conversationProperties = new ConversationProperties(this);
    private ConversationState conversationState;

    public ConversationMemory(String conversationId, String botId, Integer botVersion, String userId) {
        this(botId, botVersion, userId);
        this.conversationId = conversationId;
    }

    public ConversationMemory(String botId, Integer botVersion, String userId) {
        this(botId, botVersion);
        this.userId = userId;
    }

    public ConversationMemory(String botId, Integer botVersion) {
        this.botId = botId;
        this.botVersion = botVersion;
        var conversationOutput = new ConversationOutput();
        this.conversationOutputs.add(conversationOutput);
        this.currentStep = new ConversationStep(conversationOutput);
        this.previousSteps = new Stack<>();
    }

    @Override
    public IWritableConversationStep getCurrentStep() {
        return currentStep;
    }

    @Override
    public IConversationStepStack getPreviousSteps() {
        return new ConversationStepStack(previousSteps);
    }

    @Override
    public IConversationStepStack getAllSteps() {
        ConversationStepStack result = new ConversationStepStack(previousSteps);
        ((ConversationStep) currentStep).conversationStepNumber = previousSteps.size();
        result.add(currentStep);
        return result;
    }

    public IConversationStep startNextStep() {
        return startNextStep(null);
    }

    IConversationStep startNextStep(ConversationOutput conversationOutput) {
        ((ConversationStep) currentStep).conversationStepNumber = previousSteps.size();
        previousSteps.push(currentStep);
        if (conversationOutput == null) {
            conversationOutput = new ConversationOutput();
        }
        conversationOutputs.push(conversationOutput);
        currentStep = new ConversationStep(conversationOutput);
        return currentStep;
    }

    @Override
    public int size() {
        return previousSteps.size() + 1;
    }

    @Override
    public void undoLastStep() {
        if (!isUndoAvailable()) {
            throw new IllegalStateException();
        }

        redoCache.push(currentStep);
        currentStep = (IWritableConversationStep) previousSteps.pop();
        conversationOutputs.pop();
    }

    @Override
    public boolean isUndoAvailable() {
        return previousSteps.size() > 0;
    }

    @Override
    public boolean isRedoAvailable() {
        return redoCache.size() > 0;
    }

    @Override
    public void redoLastStep() {
        if (!isRedoAvailable()) {
            throw new IllegalStateException();
        }

        previousSteps.push(currentStep);
        currentStep = (IWritableConversationStep) redoCache.pop();
        conversationOutputs.push(currentStep.getConversationOutput());
    }

    @Override
    public ConversationState getConversationState() {
        return conversationState;
    }

    public void setConversationState(ConversationState conversationState) {
        this.conversationState = conversationState;
    }

    @Override
    public String getConversationId() {
        return conversationId;
    }

    @Override
    public String getBotId() {
        return botId;
    }

    @Override
    public String getUserId() {
        return this.userId;
    }

    @Override
    public Integer getBotVersion() {
        return botVersion;
    }

    public List<ConversationOutput> getConversationOutputs() {
        return conversationOutputs;
    }

    @Override
    public IConversationProperties getConversationProperties() {
        return conversationProperties;
    }

    @Override
    public Stack<IConversationStep> getRedoCache() {
        return redoCache;
    }

    public final static class ConversationStepStack implements IConversationStepStack {
        private final List<IConversationStep> conversationSteps = new ArrayList<>();

        public ConversationStepStack(List<IConversationStep> steps) {
            conversationSteps.addAll(steps);
        }

        @Override
        public <T> IData<T> getLatestData(String key) {
            for (int i = conversationSteps.size() - 1; i >= 0; --i) {
                IConversationStep step = conversationSteps.get(i);
                if (step.getData(key) != null) {
                    return step.getData(key);
                }
            }
            return null;
        }

        @Override
        public <T> List<List<IData<T>>> getAllData(String prefix) {
            List<List<IData<T>>> allData = new LinkedList<>();

            for (int i = conversationSteps.size() - 1; i >= 0; i--) {
                IConversationStep step = conversationSteps.get(i);
                List<IData<T>> dataList = step.getAllData(prefix);
                if (!dataList.isEmpty()) {
                    allData.add(dataList);
                }
            }

            return allData;
        }

        @Override
        public <T> List<IData<T>> getAllLatestData(String prefix) {
            return conversationSteps.stream().map((IConversationStep conversationStep) ->
                    conversationStep.<T>getLatestData(prefix)).collect(Collectors.toList());
        }

        @Override
        public int size() {
            return conversationSteps.size();
        }

        @Override
        public IConversationStep get(int index) {
            return conversationSteps.get(conversationSteps.size() - index - 1);
        }

        @Override
        public IConversationStep peek() {
            return conversationSteps.get(conversationSteps.size() - 1);
        }

        public void addAll(List<IConversationStep> conversationSteps) {
            this.conversationSteps.addAll(conversationSteps);
        }

        public void add(IConversationStep step) {
            this.conversationSteps.add(step);
        }
    }
}
