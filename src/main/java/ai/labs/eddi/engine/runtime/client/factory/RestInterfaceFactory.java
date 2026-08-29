/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.client.factory;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.RestClientBuilder;

import java.net.URI;

/**
 * Builds REST clients for EDDI's own API.
 *
 * <h3>Two overloads, two trust levels</h3> {@link #get(Class)} addresses this
 * process's own HTTP port and attaches {@link LoopbackCallerAuthFilter}, so a
 * service re-entering the API keeps the identity of whoever made the outer
 * request. {@link #get(Class, String)} names an arbitrary instance for
 * cross-instance sync and attaches nothing — forwarding a token to a host named
 * in configuration is precisely what must not happen.
 *
 * @author ginccc
 */
@ApplicationScoped
public class RestInterfaceFactory implements IRestInterfaceFactory {
    private final String apiServerURI;
    private final LoopbackCallerAuthFilter callerAuthFilter;

    @Inject
    public RestInterfaceFactory(@ConfigProperty(name = "quarkus.http.port", defaultValue = "7070") int port,
            LoopbackCallerAuthFilter callerAuthFilter) {
        this.apiServerURI = "http://127.0.0.1:" + port;
        this.callerAuthFilter = callerAuthFilter;
    }

    // CDI proxy constructor
    public RestInterfaceFactory() {
        this.apiServerURI = "http://127.0.0.1:7070";
        this.callerAuthFilter = null;
    }

    @Override
    public <T> T get(Class<T> clazz) {
        RestClientBuilder builder = RestClientBuilder.newBuilder().baseUri(URI.create(apiServerURI));
        LoopbackCallerAuthFilter filter = resolveAuthFilter();
        if (filter != null) {
            builder = builder.register(filter);
        }
        return builder.build(clazz);
    }

    @Override
    public <T> T get(Class<T> clazz, String serverUrl) {
        return RestClientBuilder.newBuilder().baseUri(URI.create(serverUrl)).build(clazz);
    }

    /**
     * The filter, resolved through CDI when this instance came from the no-arg
     * constructor.
     * <p>
     * That constructor exists for the CDI proxy, but the class is also instantiated
     * directly in tests and in a couple of bootstrap paths, where the injected
     * field is null. Looking it up lazily means those callers still get an
     * authenticated loopback client rather than silently reverting to the
     * unauthenticated behaviour this filter exists to fix; when no container is
     * running at all, the lookup fails and the client is built without it, exactly
     * as before.
     */
    private LoopbackCallerAuthFilter resolveAuthFilter() {
        if (callerAuthFilter != null) {
            return callerAuthFilter;
        }
        try {
            return CDI.current().select(LoopbackCallerAuthFilter.class).get();
        } catch (RuntimeException e) {
            return null;
        }
    }

    public static class RestInterfaceFactoryException extends Exception {
        public RestInterfaceFactoryException(String message, Exception e) {
            super(message, e);
        }
    }
}
