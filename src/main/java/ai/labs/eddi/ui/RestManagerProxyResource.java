package ai.labs.eddi.ui;

import ai.labs.eddi.engine.httpclient.IHttpClient;
import ai.labs.eddi.engine.httpclient.IRequest;
import ai.labs.eddi.engine.httpclient.IResponse;
import org.jboss.logging.Logger;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.net.URI;

/**
 * @author ginccc
 */
@ApplicationScoped
public class RestManagerProxyResource implements IRestManagerProxyResource {
    private static final Logger LOGGER = Logger.getLogger(RestManagerProxyResource.class);
    public static final String HOST_MANAGER = "https://manager.labs.ai";
    private final IHttpClient httpClient;

    @Inject
    public RestManagerProxyResource(IHttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public Response proxyClientScript(String path) {
        try {

            URI uri = URI.create("https://manager.labs.ai/scripts/" + path);
            IRequest request = httpClient.newRequest(uri, IHttpClient.Method.GET);

            IResponse response = request.send();

            return Response.ok(response.getContentAsString()).build();

        } catch (IRequest.HttpRequestException e) {
            LOGGER.error(e.getLocalizedMessage(), e);
            throw new RuntimeException();
        }
    }

    @Override
    public Response proxyClientRequest() {
        return proxyClientRequest("/");
    }

    @Override
    public Response proxyClientRequest(String path) {
        try {

            String uriString = HOST_MANAGER + (path.startsWith("/") ? path : "/" + path);
            IRequest request = httpClient.newRequest(URI.create(uriString), IHttpClient.Method.GET);
            IResponse response = request.send();

            return Response.ok(response.getContentAsString(),
                    path.endsWith("js") ? MediaType.APPLICATION_JSON : MediaType.TEXT_HTML).build();

        } catch (IRequest.HttpRequestException e) {
            LOGGER.error(e.getLocalizedMessage(), e);
            throw new RuntimeException();
        }
    }
}
