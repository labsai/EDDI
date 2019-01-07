package ai.labs.rest.restinterfaces;

import ai.labs.runtime.ThreadContext;
import org.eclipse.jetty.client.HttpClient;
import org.jboss.resteasy.client.jaxrs.ResteasyClient;
import org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder;
import org.jboss.resteasy.client.jaxrs.ResteasyWebTarget;
import org.jboss.resteasy.client.jaxrs.engines.jetty.JettyClientEngine;

import javax.inject.Inject;
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

    @Inject
    public RestInterfaceFactory(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public <T> T get(Class<T> clazz, String targetServerUri) {
        Object context = ThreadContext.get("security.token");
        String securityToken = context != null ? context.toString(): null;
        return get(clazz, targetServerUri, securityToken);
    }

    @Override
    public <T> T get(Class<T> clazz, String targetServerUri, String securityToken) {
        ResteasyClient client = getResteasyClient(targetServerUri);
        ResteasyWebTarget target = client.target(targetServerUri);

        if (securityToken != null) {
            target.register((ClientRequestFilter) requestContext ->
                    requestContext.getHeaders().add("Authorization", "Bearer " + securityToken));
        }

        return target.proxy(clazz);
    }

    private ResteasyClient getResteasyClient(String targetServerUri) {
        ResteasyClient client = clients.get(targetServerUri);
        if(client == null) {

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
