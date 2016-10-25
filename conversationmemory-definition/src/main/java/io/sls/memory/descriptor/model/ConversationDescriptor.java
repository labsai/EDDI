package io.sls.memory.descriptor.model;

import io.sls.memory.feedback.model.Feedback;
import io.sls.memory.model.ConversationState;
import io.sls.memory.model.Deployment;
import io.sls.resources.rest.descriptor.model.ResourceDescriptor;

import java.net.URI;
import java.util.LinkedList;
import java.util.List;

/**
 * @author ginccc
 */
public class ConversationDescriptor extends ResourceDescriptor {
    public enum ViewState {
        UNSEEN,
        SEEN
    }

    private String botName;
    private ViewState viewState;
    private int conversationStepSize;
    private String createdByUserName;
    private Deployment.Environment environment;
    private ConversationState conversationState;
    private List<FeedbackPosition> feedbacks;

    public ConversationDescriptor() {
        this.feedbacks = new LinkedList<>();
    }

    public String getBotName() {
        return botName;
    }

    public void setBotName(String botName) {
        this.botName = botName;
    }

    public ViewState getViewState() {
        return viewState;
    }

    public void setViewState(ViewState viewState) {
        this.viewState = viewState;
    }

    public int getConversationStepSize() {
        return conversationStepSize;
    }

    public void setConversationStepSize(int conversationStepSize) {
        this.conversationStepSize = conversationStepSize;
    }

    public String getCreatedByUserName() {
        return createdByUserName;
    }

    public void setCreatedByUserName(String createdByUserName) {
        this.createdByUserName = createdByUserName;
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

    public List<FeedbackPosition> getFeedbacks() {
        return feedbacks;
    }

    public void setFeedbacks(List<FeedbackPosition> feedbacks) {
        this.feedbacks = feedbacks;
    }

    public static class FeedbackPosition {
        private URI feedback;
        private Feedback.Type feedbackType;
        private int conversationStep;

        public FeedbackPosition() {
        }

        public FeedbackPosition(URI feedback, int conversationStep) {
            this.feedback = feedback;
            this.conversationStep = conversationStep;
        }

        public URI getFeedback() {
            return feedback;
        }

        public void setFeedback(URI feedback) {
            this.feedback = feedback;
        }

        public Feedback.Type getFeedbackType() {
            return feedbackType;
        }

        public void setFeedbackType(Feedback.Type feedbackType) {
            this.feedbackType = feedbackType;
        }

        public int getConversationStep() {
            return conversationStep;
        }

        public void setConversationStep(int conversationStep) {
            this.conversationStep = conversationStep;
        }
    }
}

