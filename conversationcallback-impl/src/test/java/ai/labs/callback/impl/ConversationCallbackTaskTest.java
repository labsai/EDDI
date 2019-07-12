package ai.labs.callback.impl;

import ai.labs.callback.IConversationCallback;
import ai.labs.callback.model.ConversationDataRequest;
import ai.labs.callback.model.ConversationDataResponse;
import ai.labs.lifecycle.PackageConfigurationException;
import ai.labs.memory.ConversationMemory;
import ai.labs.memory.IConversationMemory;
import ai.labs.memory.model.ConversationProperties;
import ai.labs.memory.model.Data;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Stack;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * @author ginccc
 */
class ConversationCallbackTaskTest {
    private IConversationCallback conversationCallback;
    private IConversationMemory memory;

    @BeforeEach
    void setup() {
        conversationCallback = mock(IConversationCallback.class);
        when(conversationCallback.doExternalCall(any(URI.class), any(ConversationDataRequest.class), anyLong())).
                thenAnswer(invocation -> {
                    ConversationDataResponse conversationDataResponse = new ConversationDataResponse();
                    conversationDataResponse.setHttpCode(200);
                    return conversationDataResponse;
                });
        memory = mock(IConversationMemory.class);
        IConversationMemory.IWritableConversationStep currentStep = mock(IConversationMemory.IWritableConversationStep.class);
        when(memory.getCurrentStep()).thenAnswer(invocation -> currentStep);
        when(memory.getRedoCache()).thenAnswer(invocation -> new Stack<>());
        when(memory.getAllSteps()).thenAnswer(invocation ->
                new ConversationMemory.ConversationStepStack(new LinkedList<>()));
        when(currentStep.getLatestData(eq("actions"))).thenAnswer(invocation ->
                new Data<>("actions", Collections.singletonList("action_1")));
        when(memory.getConversationProperties()).thenAnswer(invocation -> new ConversationProperties(memory));
    }

    @Test
    void executeTask_executingCallback() throws PackageConfigurationException {
        //setup
        ConversationCallbackTask conversationCallbackTask = createConfiguration("action_1");

        //test
        conversationCallbackTask.executeTask(memory);

        //assert
        verify(conversationCallback).doExternalCall(any(URI.class), any(ConversationDataRequest.class), eq(5000L));
    }

    @Test
    void executeTask_executingCallback_NoActionDefined() throws PackageConfigurationException {
        //setup
        ConversationCallbackTask conversationCallbackTask = createConfiguration("");

        //test
        conversationCallbackTask.executeTask(memory);

        //assert
        verify(conversationCallback).doExternalCall(any(URI.class), any(ConversationDataRequest.class), eq(5000L));
    }

    @Test
    void executeTask_NotExecutingCallback() throws PackageConfigurationException {
        //setup
        ConversationCallbackTask conversationCallbackTask = createConfiguration("action_2");

        //test
        conversationCallbackTask.executeTask(memory);

        //assert
        verify(conversationCallback, never()).doExternalCall(any(URI.class), any(ConversationDataRequest.class), eq(5000L));
    }

    private ConversationCallbackTask createConfiguration(String action) throws PackageConfigurationException {
        ConversationCallbackTask conversationCallbackTask = new ConversationCallbackTask(conversationCallback);
        HashMap<String, Object> configuration = new HashMap<>();
        configuration.put("callbackUri", "http://someUri");
        configuration.put("timeoutInMillis", 5000L);
        configuration.put("callOnActions", action);
        conversationCallbackTask.configure(configuration);

        return conversationCallbackTask;
    }
}