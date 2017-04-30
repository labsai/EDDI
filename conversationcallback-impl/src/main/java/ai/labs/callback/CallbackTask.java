package ai.labs.callback;

import ai.labs.callback.http.CallbackHttpClient;
import ai.labs.callback.impl.Callback;
import ai.labs.callback.model.ConversationDataRequest;
import ai.labs.callback.model.ConversationDataResponse;
import ai.labs.lifecycle.AbstractLifecycleTask;
import ai.labs.lifecycle.ILifecycleTask;
import ai.labs.lifecycle.LifecycleException;
import ai.labs.memory.IConversationMemory;
import ai.labs.memory.IData;
import com.google.inject.name.Names;

import java.util.Collections;
import java.util.List;

/**
 * Created by rpi on 08.02.2017.
 */
public class CallbackTask extends AbstractLifecycleTask implements ILifecycleTask {

    private ICallback callback;


    @Override
    public String getId() {
        return Callback.class.toString();
    }

    @Override
    public Object getComponent() {
        return callback;
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
        ConversationDataRequest request = new ConversationDataRequest();
        request.setConversationMemory(memory);
        ConversationDataResponse response = callback.doExternalCall(Names.named("callback.url").value(), request);
        if (response.getErrorcode() == CallbackHttpClient.ERRORCODE_OK) {
            mergeConverationMemory(memory, response.getConversationMemory());
        }
    }

    private void mergeConverationMemory(IConversationMemory memory, List<IData> callbackData) {
        if (callbackData != null && !callbackData.isEmpty()) {
            for (IData callbackDataItem : callbackData) {
                memory.getCurrentStep().storeData(callbackDataItem);
            }
        }
    }


}
