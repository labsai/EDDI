package ai.labs.callback;

import ai.labs.callback.model.ConversationDataRequest;
import ai.labs.callback.model.ConversationDataResponse;

import java.net.URI;

/**
 * @author rpi
 */
public interface IConversationCallback {
    ConversationDataResponse doExternalCall(URI url, ConversationDataRequest request, long timeoutInMillis);

}
