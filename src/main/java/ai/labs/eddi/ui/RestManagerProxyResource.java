package ai.labs.eddi.ui;

import ai.labs.eddi.engine.httpclient.IHttpClient;
import ai.labs.eddi.engine.httpclient.IRequest;
import ai.labs.eddi.engine.httpclient.IResponse;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.net.URI;

/**
 * @author ginccc
 */
@ApplicationScoped
public class RestManagerProxyResource implements IRestManagerProxyResource {
    private static final Logger LOGGER = Logger.getLogger(RestManagerProxyResource.class);
    public static final String JS_SUFFIX = "js";
    private final IHttpClient httpClient;
    private final String managerBaseUrl;

    @Inject
    public RestManagerProxyResource(IHttpClient httpClient,
                                    @ConfigProperty(name = "eddi.manager.baseUrl") String managerBaseUrl) {
        this.httpClient = httpClient;
        this.managerBaseUrl = managerBaseUrl;
    }

    @Override
    public Response proxyClientScript(String path) {
        try {

            URI uri = URI.create(managerBaseUrl + "/scripts/" + path);
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

            var uriString = managerBaseUrl + (path.startsWith("/") ? path : "/" + path);
            var response = httpClient.newRequest(URI.create(uriString)).send();

            return Response.ok(response.getContentAsString(),
                    path.endsWith(JS_SUFFIX) ? MediaType.APPLICATION_JSON : MediaType.TEXT_HTML).build();

        } catch (IRequest.HttpRequestException e) {
            LOGGER.error(e.getLocalizedMessage(), e);
            throw new RuntimeException();
        }
    }
}
