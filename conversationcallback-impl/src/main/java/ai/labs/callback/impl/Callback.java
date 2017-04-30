package ai.labs.callback.impl;

import ai.labs.callback.ICallback;
import ai.labs.callback.http.CallbackHttpClient;
import ai.labs.callback.model.ConversationDataRequest;
import ai.labs.callback.model.ConversationDataResponse;
import ai.labs.serialization.IJsonSerialization;
import lombok.extern.slf4j.Slf4j;

import javax.inject.Inject;

/**
 * @author rpi
 */
@Slf4j
public class Callback implements ICallback {
    private final IJsonSerialization jsonSerialization;
    private CallbackHttpClient callbackHttpClient;

    @Inject
    public Callback(IJsonSerialization jsonSerialization) {
        this.jsonSerialization = jsonSerialization;
    }

    @Override
    public void init(long timeoutMS, int maxConnections, int maxRequests, int maxRedirects) {
        try {
            callbackHttpClient = new CallbackHttpClient(jsonSerialization, timeoutMS, maxConnections, maxRequests, maxRedirects);
        } catch (Exception e) {
            log.error("Callback Module disabled!", e);
        }
    }

    @Override
    public ConversationDataResponse doExternalCall(String url, ConversationDataRequest request) {

        return callbackHttpClient.send(url, request);
    }
}
