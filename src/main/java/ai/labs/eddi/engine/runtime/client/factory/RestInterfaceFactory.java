/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.client.factory;

import ai.labs.eddi.engine.security.SelfUrlResolver;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.spi.CDI;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.RestClientBuilder;

import java.net.URI;
import java.util.Optional;

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

    /** Whether {@link #apiServerURI} is known to be this process's own listener. */
    private final boolean selfResolved;

    /**
     * The loopback address is derived from the HTTP listener by
     * {@link SelfUrlResolver}'s listener-aware logic (its
     * {@code eddi.self.base-url} override deliberately not applied: this hop must
     * not leave the process). A hard-coded {@code 127.0.0.1} was wrong when
     * {@code quarkus.http.host} binds one specific other address: EDDI is then not
     * listening there, and whatever process is would have received every caller's
     * bearer token from {@link LoopbackCallerAuthFilter}. When the address cannot
     * be resolved (a random port), clients are still built against loopback but
     * carry no caller credential.
     */
    @Inject
    public RestInterfaceFactory(@ConfigProperty(name = "quarkus.http.port", defaultValue = "7070") int port,
            @ConfigProperty(name = "quarkus.http.host", defaultValue = "0.0.0.0") String host, LoopbackCallerAuthFilter callerAuthFilter) {
        String self = new SelfUrlResolver(Optional.empty(), port, host).baseUrl();
        this.selfResolved = self != null;
        this.apiServerURI = self != null ? self : "http://127.0.0.1:" + port;
        this.callerAuthFilter = callerAuthFilter;
    }

    // CDI proxy constructor
    public RestInterfaceFactory() {
        this.apiServerURI = "http://127.0.0.1:7070";
        this.callerAuthFilter = null;
        this.selfResolved = true;
    }

    @Override
    public <T> T get(Class<T> clazz) {
        RestClientBuilder builder = RestClientBuilder.newBuilder().baseUri(URI.create(apiServerURI));
        LoopbackCallerAuthFilter filter = selfResolved ? resolveAuthFilter() : null;
        if (filter != null) {
            builder = builder.register(filter);
        }
        return builder.build(clazz);
    }

    /** The address {@link #get(Class)} clients call. */
    String baseUri() {
        return apiServerURI;
    }

    /** Whether {@link #get(Class)} clients carry the caller's credential. */
    boolean forwardsCallerCredentials() {
        return selfResolved;
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
