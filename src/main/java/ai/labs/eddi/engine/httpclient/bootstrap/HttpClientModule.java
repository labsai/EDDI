/*
 * Copyright (c) 2016-2026 EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient.bootstrap;

import ai.labs.eddi.engine.httpclient.impl.VertxHttpClient;
import io.vertx.core.Vertx;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientSession;
import io.vertx.ext.web.client.WebClientOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.inject.Inject;
import jakarta.ws.rs.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class HttpClientModule {

    @Inject
    Vertx vertx;

    @Produces
    @ApplicationScoped
    public VertxHttpClient provideHttpClient(@ConfigProperty(name = "httpClient.maxConnectionPerRoute") Integer maxConnectionPerRoute,
                                             @ConfigProperty(name = "httpClient.maxRedirects") Integer maxRedirects,
                                             @ConfigProperty(name = "httpClient.idleTimeoutInMillis") Integer idleTimeout,
                                             @ConfigProperty(name = "httpClient.connectTimeoutInMillis") Integer connectTimeout) {

        WebClientOptions options = new WebClientOptions();

        // Mapping configuration
        options.setMaxPoolSize(maxConnectionPerRoute);

        options.setMaxRedirects(maxRedirects);

        int idleTimeoutSeconds;
        if (idleTimeout == 0) {
            idleTimeoutSeconds = 0;
        } else {
            idleTimeoutSeconds = (int) Math.ceil(idleTimeout / 1000.0);
        }
        options.setIdleTimeout(idleTimeoutSeconds);

        options.setConnectTimeout(connectTimeout);
        options.setFollowRedirects(true);
        options.setDecompressionSupported(true);

        WebClient webClient = WebClient.create(vertx, options);
        WebClientSession webClientSession = WebClientSession.create(webClient);

        return new VertxHttpClient(vertx, webClientSession, webClient);
    }

    public void close(@Disposes VertxHttpClient client) {
        if (client.getWebClient() != null) {
            client.getWebClient().close();
        }
        if (client.getUnderlyingClient() != null) {
            client.getUnderlyingClient().close();
        }
    }
}
