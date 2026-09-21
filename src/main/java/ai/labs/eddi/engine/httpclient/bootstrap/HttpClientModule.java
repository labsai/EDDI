/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient.bootstrap;

import ai.labs.eddi.engine.httpclient.impl.VertxHttpClient;
import ai.labs.eddi.modules.llm.tools.UrlValidationUtils;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.http.HttpClientOptions;
import io.vertx.core.http.HttpClientResponse;
import io.vertx.core.http.RequestOptions;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientSession;
import io.vertx.ext.web.client.WebClientOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.function.Function;

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

        // What WebClient.create(vertx, options) does, with the HttpClient kept in hand.
        WebClient webClient = WebClient.wrap(guardedHttpClient(vertx, options), options);
        WebClientSession webClientSession = WebClientSession.create(webClient);

        return new VertxHttpClient(vertx, webClientSession, webClient);
    }

    /**
     * The HTTP client behind the web client, with {@link #refusingMetadataHops}
     * installed: the web client follows redirects through this client's redirect
     * handler.
     */
    static HttpClient guardedHttpClient(Vertx vertx, HttpClientOptions options) {
        HttpClient httpClient = vertx.createHttpClient(options);
        httpClient.redirectHandler(refusingMetadataHops(vertx, httpClient.redirectHandler()));
        return httpClient;
    }

    /**
     * Wraps the client's redirect handler so that no hop lands on the cloud
     * instance-metadata service.
     * <p>
     * {@code ApiCallExecutor} refuses a metadata target before sending, whatever
     * {@code eddi.security.ssrf-protection.enabled} says. With protection off this
     * client still follows redirects, though, so a public URL answering
     * {@code 302 Location: http://169.254.169.254/…} reached it anyway. The veto
     * sits on the client rather than on the call so that every hop of every request
     * is covered. The wrapped handler still decides whether and where to follow;
     * this only refuses the destination. The check may resolve a hostname, which
     * blocks, so it runs on a worker thread and never on the event loop.
     */
    static Function<HttpClientResponse, Future<RequestOptions>> refusingMetadataHops(Vertx vertx,
                                                                                     Function<HttpClientResponse, Future<RequestOptions>> delegate) {
        return response -> {
            Future<RequestOptions> next = delegate.apply(response);
            if (next == null) {
                return null;
            }
            return next.compose(options -> {
                if (options == null) {
                    return Future.succeededFuture();
                }
                return vertx.executeBlocking(() -> requireNotMetadataHop(options), false);
            });
        };
    }

    /**
     * Returns the redirect target unchanged, or throws when its host is a metadata
     * or link-local address.
     *
     * @throws IllegalArgumentException
     *             when the hop targets the cloud instance-metadata service
     */
    static RequestOptions requireNotMetadataHop(RequestOptions next) {
        String host = next.getHost();
        if (host != null && !host.isBlank()) {
            boolean bareIpv6 = host.indexOf(':') >= 0 && !host.startsWith("[");
            UrlValidationUtils.rejectCloudMetadataTarget("http://" + (bareIpv6 ? "[" + host + "]" : host) + "/");
        }
        return next;
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
