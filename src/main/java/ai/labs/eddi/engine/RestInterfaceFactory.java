package ai.labs.eddi.engine;

import ai.labs.eddi.engine.runtime.ThreadContext;
import jakarta.ws.rs.client.ClientBuilder;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.resteasy.client.jaxrs.ResteasyClient;
import org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder;
import org.jboss.resteasy.client.jaxrs.ResteasyWebTarget;
import org.jboss.resteasy.client.jaxrs.engines.jetty.JettyClientEngine;

import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.ws.rs.client.ClientRequestFilter;
import java.util.HashMap;
import java.util.Map;

/**
 * @author ginccc
 */
@ApplicationScoped
public class RestInterfaceFactory implements IRestInterfaceFactory {
    private final Map<String, ResteasyClient> clients = new HashMap<>();
    private final HttpClient httpClient;
    private final String apiServerURI;
    private final String securityHandlerType;

    @Inject
    public RestInterfaceFactory(HttpClient httpClient,
                                @ConfigProperty(name = "system.apiServerURI") String apiServerURI,
                                @ConfigProperty(name = "webServer.securityHandlerType") String securityHandlerType) {
        this.httpClient = httpClient;
        this.apiServerURI = apiServerURI;
        this.securityHandlerType = securityHandlerType;
    }

    @Override
    public <T> T get(Class<T> clazz) {
        ResteasyClient client = getResteasyClient(apiServerURI);
        ResteasyWebTarget target = client.target(apiServerURI);

        target.register((ClientRequestFilter) requestContext -> {
            String authorizationString = null;
            if ("basic".equals(securityHandlerType)) {
                Object context = ThreadContext.get("currentuser:credentials");
                authorizationString = context != null ? context.toString() : null;
            } else if ("keycloak".equals(securityHandlerType)) {
                Object context = ThreadContext.get("security.token");
                String securityToken = context != null ? context.toString() : null;
                if (securityToken != null) {
                    authorizationString = "Bearer " + securityToken;
                }
            }
            if (authorizationString != null) {
                requestContext.getHeaders().add("Authorization", authorizationString);
            }
        });

        return target.proxy(clazz);
    }

    private ResteasyClient getResteasyClient(String targetServerUri) {
        ResteasyClient client = clients.get(targetServerUri);
        if (client == null) {

            JettyClientEngine engine = new JettyClientEngine(httpClient);
            ResteasyClientBuilder clientBuilder = (ResteasyClientBuilder) ClientBuilder.newBuilder();
            clientBuilder.httpEngine(engine);

            client = clientBuilder.build();
            clients.put(targetServerUri, client);
        }

        return client;
    }

    public static class RestInterfaceFactoryException extends Exception {
        public RestInterfaceFactoryException(String message, Exception e) {
            super(message, e);
        }
    }
}
