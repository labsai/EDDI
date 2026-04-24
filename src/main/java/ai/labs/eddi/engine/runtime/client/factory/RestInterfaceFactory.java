/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.client.factory;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.RestClientBuilder;

import java.net.URI;

@ApplicationScoped
public class RestInterfaceFactory implements IRestInterfaceFactory {
    private final String apiServerURI;

    @Inject
    public RestInterfaceFactory(@ConfigProperty(name = "quarkus.http.port", defaultValue = "7070") int port) {
        this.apiServerURI = "http://127.0.0.1:" + port;
    }

    // CDI proxy constructor
    public RestInterfaceFactory() {
        this.apiServerURI = "http://127.0.0.1:7070";
    }

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