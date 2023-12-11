package ai.labs.eddi.engine.runtime.client.factory;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.rest.client.RestClientBuilder;

import java.net.URI;

@ApplicationScoped
public class RestInterfaceFactory implements IRestInterfaceFactory {
    private static final String apiServerURI = "http://127.0.0.1:7070";

    @Override
    public <T> T get(Class<T> clazz) {
        return get(clazz, apiServerURI);
    }

    @Override
    public <T> T get(Class<T> clazz, String serverUrl) {
        return RestClientBuilder.newBuilder().baseUri(URI.create(serverUrl)).build(clazz);
    }

    public static class RestInterfaceFactoryException extends Exception {
        public RestInterfaceFactoryException(String message, Exception e) {
            super(message, e);
        }
    }
}