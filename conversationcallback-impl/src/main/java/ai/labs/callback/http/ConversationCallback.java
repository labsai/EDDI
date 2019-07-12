package ai.labs.callback.http;

import ai.labs.callback.IConversationCallback;
import ai.labs.callback.model.ConversationDataRequest;
import ai.labs.callback.model.ConversationDataResponse;
import ai.labs.callback.model.ConversationDataResponseHolder;
import ai.labs.httpclient.IHttpClient;
import ai.labs.httpclient.IRequest;
import ai.labs.httpclient.IResponse;
import ai.labs.serialization.IJsonSerialization;
import lombok.extern.slf4j.Slf4j;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.ws.rs.core.MediaType;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.TimeUnit;

/**
 * @author rpi
 */
@Slf4j
@ApplicationScoped
public class ConversationCallback implements IConversationCallback {
    private static final String AI_LABS_USER_AGENT = "Jetty 9.3/HTTP CLIENT - AI.LABS.EDDI";
    private static final String ENCODING = "UTF-8";

    private final IHttpClient httpClient;
    private final IJsonSerialization jsonSerialization;

    @Inject
    public ConversationCallback(IHttpClient httpClient,
                                IJsonSerialization jsonSerialization) {
        this.httpClient = httpClient;
        this.jsonSerialization = jsonSerialization;
    }

    @Override
    public ConversationDataResponse doExternalCall(URI url, ConversationDataRequest request, long timeoutInMillis) {
        ConversationDataResponse dataResponse = new ConversationDataResponse();

        try {
            String jsonRequestBody = jsonSerialization.serialize(request);
            IResponse httpResponse = httpClient.newRequest(url, IHttpClient.Method.POST)
                    .setUserAgent(AI_LABS_USER_AGENT)
                    .setTimeout(timeoutInMillis, TimeUnit.MILLISECONDS)
                    .setBodyEntity(jsonRequestBody, ENCODING, MediaType.APPLICATION_JSON)
                    .send();

            dataResponse.setHttpCode(httpResponse.getHttpCode());
            dataResponse.setHeader(httpResponse.getHttpHeader());
            ConversationDataResponseHolder responseHolder = jsonSerialization.deserialize(httpResponse.getContentAsString(), ConversationDataResponseHolder.class);
            dataResponse.setConversationMemorySnapshot(responseHolder.getConversationMemorySnapshot());


        } catch (IRequest.HttpRequestException | IOException e) {
            log.error(e.getLocalizedMessage(), e);
        }

        return dataResponse;
    }
}
