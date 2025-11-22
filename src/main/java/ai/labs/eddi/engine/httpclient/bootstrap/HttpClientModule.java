package ai.labs.eddi.engine.httpclient.bootstrap;

import ai.labs.eddi.engine.httpclient.impl.VertxHttpClient;
import io.vertx.core.Vertx;
import io.vertx.ext.web.client.WebClient;
import io.vertx.ext.web.client.WebClientSession;
import io.vertx.ext.web.client.WebClientOptions;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

@ApplicationScoped
public class HttpClientModule {

    @Inject
    Vertx vertx;

    @Produces
    @ApplicationScoped
    public VertxHttpClient provideHttpClient(@ConfigProperty(name = "httpClient.maxConnectionsQueued") Integer maxConnectionsQueued,
                                             @ConfigProperty(name = "httpClient.maxConnectionPerRoute") Integer maxConnectionPerRoute,
                                             @ConfigProperty(name = "httpClient.requestBufferSize") Integer requestBufferSize,
                                             @ConfigProperty(name = "httpClient.responseBufferSize") Integer responseBufferSize,
                                             @ConfigProperty(name = "httpClient.maxRedirects") Integer maxRedirects,
                                             @ConfigProperty(name = "httpClient.idleTimeoutInMillis") Integer idleTimeout,
                                             @ConfigProperty(name = "httpClient.connectTimeoutInMillis") Integer connectTimeout,
                                             @ConfigProperty(name = "httpClient.disableWWWAuthenticationValidation")
                                             Boolean disableWWWAuthenticationValidation) {

        WebClientOptions options = new WebClientOptions();

        // Mapping configuration
        options.setMaxPoolSize(maxConnectionPerRoute);

        options.setMaxRedirects(maxRedirects);
        int idleTimeoutSeconds = idleTimeout / 1000;
        if (idleTimeout > 0 && idleTimeoutSeconds == 0) {
            idleTimeoutSeconds = 1;
        }
        options.setIdleTimeout(idleTimeoutSeconds);

        options.setConnectTimeout(connectTimeout);
        options.setFollowRedirects(true);
        options.setDecompressionSupported(true);

        WebClient webClient = WebClient.create(vertx, options);
        WebClientSession webClientSession = WebClientSession.create(webClient);

        return new VertxHttpClient(vertx, webClientSession);
    }
}
