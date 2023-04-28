package ai.labs.eddi.engine.runtime.client.factory;

import org.jboss.resteasy.client.jaxrs.ResteasyClient;
import org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder;
import org.jboss.resteasy.client.jaxrs.ResteasyWebTarget;
import org.jboss.resteasy.client.jaxrs.engines.ApacheHttpClient43Engine;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.client.ClientBuilder;
import java.util.HashMap;
import java.util.Map;

/**
 * @author ginccc
 */
@ApplicationScoped
public class RestInterfaceFactory implements IRestInterfaceFactory {
    private final Map<String, ResteasyClient> clients = new HashMap<>();
    private static final String apiServerURI = "http://127.0.0.1:7070";

    @Override
    public <T> T get(Class<T> clazz) {
        return get(clazz, apiServerURI);
    }

    @Override
    public <T> T get(Class<T> clazz, String serverUrl) {
        ResteasyClient client = getResteasyClient(serverUrl);
        ResteasyWebTarget target = client.target(serverUrl);

        return target.proxy(clazz);
    }

    private ResteasyClient getResteasyClient(String targetServerUri) {
        ResteasyClient client = clients.get(targetServerUri);
        if (client == null) {
            ResteasyClientBuilder clientBuilder = (ResteasyClientBuilder) ClientBuilder.newBuilder();
            clientBuilder.httpEngine(new ApacheHttpClient43Engine());

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
