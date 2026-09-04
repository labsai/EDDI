/*
 * Copyright EDDI contributors
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
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class HttpClientModule {

    @Inject
    Vertx vertx;

    /**
     * The CDI producer for the one and only {@link VertxHttpClient} — i.e. for
     * every outbound httpcalls request in every agent.
     * <p>
     * {@code @Produces} here MUST be {@code jakarta.enterprise.inject.Produces}.
     * This declaration carried the JAX-RS media-type annotation of the same simple
     * name instead, and the bean existed only because ArC's
     * {@code quarkus.arc.auto-producer-methods} default promotes any method with a
     * scope annotation to a producer. Turning that default off, or moving
     * {@code @ApplicationScoped} off this method, would have made
     * {@code VertxHttpClient} unsatisfied with nothing pointing at the wrong import
     * as the cause. The paired {@code @Disposes} below was always the real CDI
     * annotation, so the two halves disagreed.
     */
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
