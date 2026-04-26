/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient.impl;

import io.vertx.core.Vertx;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientSession;

public class VertxHttpClient {
    private final Vertx vertx;
    private final WebClientSession webClient;
    private final WebClient underlyingClient;

    public VertxHttpClient(final Vertx vertx, final WebClientSession webClient, final WebClient underlyingClient) {
        this.vertx = vertx;
        this.webClient = webClient;
        this.underlyingClient = underlyingClient;
    }

    public final Vertx getVertx() {
        return vertx;
    }

    public final WebClientSession getWebClient() {
        return webClient;
    }

    public final WebClient getUnderlyingClient() {
        return underlyingClient;
    }
}
