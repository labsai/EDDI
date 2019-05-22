package ai.labs.callback.impl;

import ai.labs.callback.IConversationCallback;
import ai.labs.callback.model.ConversationDataRequest;
import ai.labs.lifecycle.ILifecycleTask;
import ai.labs.lifecycle.PackageConfigurationException;
import ai.labs.memory.IConversationMemory;
import ai.labs.memory.IData;
import ai.labs.memory.model.ConversationMemorySnapshot;
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

import static ai.labs.memory.ConversationMemoryUtilities.convertConversationMemory;
import static ai.labs.memory.ConversationMemoryUtilities.convertConversationMemorySnapshot;

/**
 * Created by rpi on 08.02.2017.
 */
@Slf4j
public class ConversationCallbackTask implements ILifecycleTask {
    private static final String ID = "ai.labs.callback";
    private static final String KEY_ACTIONS = "actions";
    private static final String KEY_CALLBACK_URI = "callbackUri";
    private static final String KEY_TIMEOUT_IN_MILLIS = "timeoutInMillis";
    private static final String KEY_CALL_ON_ACTIONS = "callOnActions";
    private static final long DEFAULT_TIMEOUT_IN_MILLIS = 10000L;

    private final IConversationCallback conversationCallback;
    private List<String> callOnActions = Collections.emptyList();
    private URI callback;
    private long timeoutInMillis = DEFAULT_TIMEOUT_IN_MILLIS;

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
    public void executeTask(IConversationMemory memory) {
        IData<List<String>> actionData = memory.getCurrentStep().getLatestData(KEY_ACTIONS);
        if (!executeCallback(actionData)) {
            return;
        }

        var request = new ConversationDataRequest();
        request.setConversationMemorySnapshot(convertConversationMemory(memory));
        var response = conversationCallback.doExternalCall(callback, request, timeoutInMillis);

        if (String.valueOf(response.getHttpCode()).startsWith("2")) { //check for success, http code 2xx
            mergeConversationMemory(memory, response.getConversationMemorySnapshot());
        } else {
            String msg = "ConversationCallback was (%s) but should have been 2xx. Return value as been ignored";
            msg = String.format(msg, response.getHttpCode());
            log.warn(msg);
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
                                         ConversationMemorySnapshot callbackMemorySnapshot) {
        if (callbackMemorySnapshot != null && !callbackMemorySnapshot.getConversationSteps().isEmpty()) {
            var currentStep = currentConversationMemory.getCurrentStep();

            var callbackConversationMemory = convertConversationMemorySnapshot(callbackMemorySnapshot);

            var currentCallbackStep = callbackConversationMemory.getCurrentStep();
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
                throw throwParamNotDefinedError();
            }
        } else {
            throw throwParamNotDefinedError();
        }

        if (configuration.containsKey(KEY_TIMEOUT_IN_MILLIS)) {
            Object configValue = configuration.get(KEY_TIMEOUT_IN_MILLIS);
            if (configValue != null) {
                try {
                    timeoutInMillis = Long.parseLong(configValue.toString());
                } catch (NumberFormatException e) {
                    throw new PackageConfigurationException(e.getLocalizedMessage(), e);
                }
            }
        }

        if (configuration.containsKey(KEY_CALL_ON_ACTIONS)) {
            Object configValue = configuration.get(KEY_CALL_ON_ACTIONS);
            if (configValue != null) {
                callOnActions = StringUtilities.parseCommaSeparatedString(configValue.toString());
            }
        }
    }

    private PackageConfigurationException throwParamNotDefinedError() {
        String errorMessage = String.format("Parameter '%s' must be defined!", KEY_CALLBACK_URI);
        return new PackageConfigurationException(errorMessage);
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
