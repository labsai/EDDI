package ai.labs.rest.restinterfaces;

import ai.labs.runtime.ThreadContext;
import org.eclipse.jetty.client.HttpClient;
import org.jboss.resteasy.client.jaxrs.ResteasyClient;
import org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder;
import org.jboss.resteasy.client.jaxrs.ResteasyWebTarget;
import org.jboss.resteasy.client.jaxrs.engines.jetty.JettyClientEngine;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.ws.rs.client.ClientRequestFilter;
import java.util.HashMap;
import java.util.Map;

/**
 * @author ginccc
 */
@Singleton
public class RestInterfaceFactory implements IRestInterfaceFactory {
    private final Map<String, ResteasyClient> clients = new HashMap<>();
    private final HttpClient httpClient;
    private final String apiServerURI;

    @Inject
    public RestInterfaceFactory(HttpClient httpClient,
                                @Named("system.apiServerURI") String apiServerURI) {
        this.httpClient = httpClient;
        this.apiServerURI = apiServerURI;
    }

    @Override
    public <T> T get(Class<T> clazz) {
        Object context = ThreadContext.get("security.token");
        String securityToken = context != null ? context.toString() : null;
        return get(clazz, apiServerURI, securityToken);
    }

    @Override
    public <T> T get(Class<T> clazz, String apiServerURI, String securityToken) {
        ResteasyClient client = getResteasyClient(apiServerURI);
        ResteasyWebTarget target = client.target(apiServerURI);

        if (securityToken != null) {
            target.register((ClientRequestFilter) requestContext ->
                    requestContext.getHeaders().add("Authorization", "Bearer " + securityToken));
        }

        return target.proxy(clazz);
    }

    private ResteasyClient getResteasyClient(String targetServerUri) {
        ResteasyClient client = clients.get(targetServerUri);
        if (client == null) {

            JettyClientEngine engine = new JettyClientEngine(httpClient);
            ResteasyClientBuilder clientBuilder = new ResteasyClientBuilder();
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
