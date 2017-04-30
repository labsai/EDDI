package ai.labs.callback;

import ai.labs.callback.model.ConversationDataRequest;
import ai.labs.callback.model.ConversationDataResponse;

/**
 * @author rpi
 */
public interface ICallback {

    void init(long timeoutMS, int maxConnections, int maxRequests, int maxRedirects);

    public ConversationDataResponse doExternalCall(String url, ConversationDataRequest request);

}
