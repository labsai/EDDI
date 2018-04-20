package ai.labs.callback.impl;

import ai.labs.callback.IConversationCallback;
import ai.labs.callback.model.ConversationDataRequest;
import ai.labs.callback.model.ConversationDataResponse;
import ai.labs.lifecycle.ILifecycleTask;
import ai.labs.lifecycle.LifecycleException;
import ai.labs.lifecycle.PackageConfigurationException;
import ai.labs.memory.ConversationMemoryUtilities;
import ai.labs.memory.IConversationMemory;
import ai.labs.memory.IData;
import ai.labs.memory.model.ConversationMemorySnapshot;
import ai.labs.persistence.IResourceStore;
import ai.labs.resources.rest.extensions.model.ExtensionDescriptor;
import ai.labs.resources.rest.extensions.model.ExtensionDescriptor.ConfigValue;
import ai.labs.resources.rest.extensions.model.ExtensionDescriptor.FieldType;
import ai.labs.utilities.StringUtilities;
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
public class ConversationCallbackTask implements ILifecycleTask {
    private static final String ID = "ai.labs.callback";
    private static final String KEY_ACTION = "action";
    private static final String KEY_CALLBACK_URI = "callbackUri";
    private static final String KEY_TIMEOUT_IN_MILLIS = "timeoutInMillis";
    private static final String KEY_CALL_ON_ACTIONS = "callOnActions";
    private static final long DEFAULT_TIMEOUT_IN_MILLIS = 10000L;

    private final IConversationCallback conversationCallback;
    private List<String> callOnActions;
    private URI callback;
    private long timeoutInMillis;

    @Inject
    public ConversationCallbackTask(IConversationCallback conversationCallback) {
        this.conversationCallback = conversationCallback;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public Object getComponent() {
        return conversationCallback;
    }

    @Override
    public void executeTask(IConversationMemory memory) throws LifecycleException {
        try {
            IData<List<String>> actionData = memory.getCurrentStep().getLatestData(KEY_ACTION);
            if (!executeCallback(actionData)) {
                return;
            }

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

    private boolean executeCallback(IData<List<String>> actionData) {
        if (callOnActions.isEmpty()) {
            return true;
        }

        if (actionData != null) {
            List<String> actions = actionData.getResult();
            return !actions.isEmpty() && actions.stream().anyMatch(action -> callOnActions.contains(action));
        }

        return false;
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
            if (configValue != null) {
                try {
                    callback = URI.create(configValue.toString());
                } catch (IllegalArgumentException e) {
                    throw new PackageConfigurationException(e.getLocalizedMessage(), e);
                }
            } else {
                String errorMessage = String.format("Parameter '%s' must be defined!", KEY_CALLBACK_URI);
                throw new PackageConfigurationException(errorMessage);
            }

        }

        if (configuration.containsKey(KEY_TIMEOUT_IN_MILLIS)) {
            Object configValue = configuration.get(KEY_TIMEOUT_IN_MILLIS);
            if (configValue != null) {
                try {
                    timeoutInMillis = Long.parseLong(configValue.toString());
                } catch (NumberFormatException e) {
                    throw new PackageConfigurationException(e.getLocalizedMessage(), e);
                }
            } else {
                timeoutInMillis = DEFAULT_TIMEOUT_IN_MILLIS;
            }
        }

        if (configuration.containsKey(KEY_CALL_ON_ACTIONS)) {
            Object configValue = configuration.get(KEY_CALL_ON_ACTIONS);
            if (configValue != null) {
                callOnActions = StringUtilities.parseCommaSeparatedString(configValue.toString());
            } else {
                callOnActions = Collections.emptyList();
            }
        }
    }

    @Override
    public ExtensionDescriptor getExtensionDescriptor() {
        ExtensionDescriptor extensionDescriptor = new ExtensionDescriptor(ID);
        extensionDescriptor.setDisplayName("External Callback");

        Map<String, ConfigValue> configs = extensionDescriptor.getConfigs();
        configs.put(KEY_CALLBACK_URI, new ConfigValue("Callback URI", FieldType.URI, false, null));
        configs.put(KEY_TIMEOUT_IN_MILLIS, new ConfigValue("Timeout in Milliseconds", FieldType.URI, true, DEFAULT_TIMEOUT_IN_MILLIS));
        configs.put(KEY_CALL_ON_ACTIONS, new ConfigValue("Call on Actions", FieldType.STRING, true, ""));
        return extensionDescriptor;
    }
}
