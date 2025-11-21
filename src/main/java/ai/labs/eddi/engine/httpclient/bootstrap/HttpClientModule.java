package ai.labs.eddi.engine.httpclient.bootstrap;

import ai.labs.eddi.engine.httpclient.impl.VertxHttpClient;
import io.vertx.core.Vertx;
import io.vertx.ext.web.client.WebClient;
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
        options.setMaxPoolSize(maxConnectionPerRoute); // Closest mapping to MaxConnectionsPerDestination/MaxRequestsQueuedPerDestination
        // maxConnectionsQueued (Jetty) -> MaxWaitQueueSize (Vertx)? Vertx doesn't seem to have exact equivalent exposed in options simply.
        // setExecutor is handled by Vertx instance.

        // request/response buffer sizes are not directly configurable in WebClientOptions in the same way,
        // but we can control some limits. For now, we might skip exact buffer size mapping unless critical.
        // Jetty's setRequestBufferSize/setResponseBufferSize -> Vertx ?
        // Vertx handles buffers dynamically mostly.

        options.setMaxRedirects(maxRedirects);
        // Vertx: setIdleTimeout(int idleTimeout) - "The amount of time a connection can be idle before it is closed. 0 means no timeout."
        // usually seconds in Vertx.
        int idleTimeoutSeconds = idleTimeout / 1000;
        if (idleTimeout > 0 && idleTimeoutSeconds == 0) {
            idleTimeoutSeconds = 1;
        }
        options.setIdleTimeout(idleTimeoutSeconds);

        options.setConnectTimeout(connectTimeout); // Vertx: setConnectTimeout(int connectTimeout) in ms.

        // disableWWWAuthenticationValidation -> Vertx handles auth differently.
        // If this means disable automatic handling of 401, Vertx WebClient doesn't do it automatically unless configured.

        // We also need to consider KeepAlive which is usually true by default.

        WebClient webClient = WebClient.create(vertx, options);

        return new VertxHttpClient(vertx, webClient);
    }
}
