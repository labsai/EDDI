package ai.labs.callback.impl;

import ai.labs.callback.IConversationCallback;
import ai.labs.callback.http.ConversationCallback;
import ai.labs.callback.model.ConversationDataRequest;
import ai.labs.callback.model.ConversationDataResponse;
import ai.labs.lifecycle.AbstractLifecycleTask;
import ai.labs.lifecycle.ILifecycleTask;
import ai.labs.lifecycle.LifecycleException;
import ai.labs.lifecycle.PackageConfigurationException;
import ai.labs.memory.ConversationMemoryUtilities;
import ai.labs.memory.IConversationMemory;
import ai.labs.memory.model.ConversationMemorySnapshot;
import ai.labs.persistence.IResourceStore;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Created by rpi on 08.02.2017.
 */
@Slf4j
public class ConversationCallbackTask extends AbstractLifecycleTask implements ILifecycleTask {
    private static final String KEY_CALLBACK_URI = "callbackUri";
    private static final String KEY_TIMEOUT_IN_MILLIS = "timeoutInMillis";

    private final IConversationCallback conversationCallback;
    private URI callback;
    private long timeoutInMillis;

    @Inject
    public ConversationCallbackTask(IConversationCallback conversationCallback) {
        this.conversationCallback = conversationCallback;
    }

    @Override
    public String getId() {
        return ConversationCallback.class.toString();
    }

    @Override
    public Object getComponent() {
        return conversationCallback;
    }

    @Override
    public List<String> getComponentDependencies() {
        return Collections.emptyList();
    }

    @Override
    public List<String> getOutputDependencies() {
        return Collections.emptyList();
    }

    @Override
    public void executeTask(IConversationMemory memory) throws LifecycleException {
        try {
            ConversationDataRequest request = new ConversationDataRequest();
            request.setConversationMemorySnapshot(ConversationMemoryUtilities.convertConversationMemory(memory));
            ConversationDataResponse response =
                    conversationCallback.doExternalCall(callback, request, timeoutInMillis);

            if (String.valueOf(response.getHttpCode()).startsWith("2")) { //check for success, http code 2xx
                mergeConversationMemory(memory, response.getConversationMemorySnapshot());
            } else {
                String msg = "ConversationCallback was (%s) but should have been 2xx. Return value as been ignored";
                msg = String.format(msg, response.getHttpCode());
                log.warn(msg);
            }
        } catch (IResourceStore.ResourceStoreException | IResourceStore.ResourceNotFoundException e) {
            throw new LifecycleException(e.getLocalizedMessage(), e);
        }
    }

    private void mergeConversationMemory(IConversationMemory currentConversationMemory,
                                         ConversationMemorySnapshot callbackMemorySnapshot)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        if (callbackMemorySnapshot != null && !callbackMemorySnapshot.getConversationSteps().isEmpty()) {
            IConversationMemory.IWritableConversationStep currentStep = currentConversationMemory.getCurrentStep();

            IConversationMemory callbackConversationMemory = ConversationMemoryUtilities.
                    convertConversationMemorySnapshot(callbackMemorySnapshot);

            IConversationMemory.IWritableConversationStep currentCallbackStep = callbackConversationMemory.getCurrentStep();
            Set<String> callbackKeys = currentCallbackStep.getAllKeys();
            for (String callbackKey : callbackKeys) {
                currentStep.storeData(currentCallbackStep.getData(callbackKey));
            }

        }
    }

    @Override
    public void configure(Map<String, Object> configuration) throws PackageConfigurationException {
        if (configuration.containsKey(KEY_CALLBACK_URI)) {
            Object configValue = configuration.get(KEY_CALLBACK_URI);
            try {
                callback = URI.create(configValue.toString());
            } catch (IllegalArgumentException e) {
                throw new PackageConfigurationException(e.getLocalizedMessage(), e);
            }
        }

        if (configuration.containsKey(KEY_TIMEOUT_IN_MILLIS)) {
            Object configValue = configuration.get(KEY_TIMEOUT_IN_MILLIS);
            try {
                timeoutInMillis = Long.parseLong(configValue.toString());
            } catch (NumberFormatException e) {
                throw new PackageConfigurationException(e.getLocalizedMessage(), e);
            }
        }

    }
}
