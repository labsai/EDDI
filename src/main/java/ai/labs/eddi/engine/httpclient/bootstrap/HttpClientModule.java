package ai.labs.eddi.engine.httpclient.bootstrap;

import ai.labs.eddi.engine.httpclient.impl.JettyHttpClient;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.context.ManagedExecutor;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.Produces;
import java.util.Arrays;

@ApplicationScoped
public class HttpClientModule {

    @Inject
    ManagedExecutor executorService;

    @Produces
    @ApplicationScoped
    public JettyHttpClient provideHttpClient(@ConfigProperty(name = "httpClient.maxConnectionsQueued") Integer maxConnectionsQueued,
                                             @ConfigProperty(name = "httpClient.maxConnectionPerRoute") Integer maxConnectionPerRoute,
                                             @ConfigProperty(name = "httpClient.requestBufferSize") Integer requestBufferSize,
                                             @ConfigProperty(name = "httpClient.responseBufferSize") Integer responseBufferSize,
                                             @ConfigProperty(name = "httpClient.maxRedirects") Integer maxRedirects,
                                             @ConfigProperty(name = "httpClient.trustAllCertificates") Boolean trustAllCertificates) {

        try {
            HttpClient httpClient = new HttpClient();
            httpClient.setExecutor(executorService);
            httpClient.setMaxConnectionsPerDestination(maxConnectionsQueued);
            httpClient.setMaxRequestsQueuedPerDestination(maxConnectionPerRoute);
            httpClient.setRequestBufferSize(requestBufferSize);
            httpClient.setResponseBufferSize(responseBufferSize);
            httpClient.setMaxRedirects(maxRedirects);
            httpClient.start();

            registerHttpClientShutdownHook(httpClient);

            return new JettyHttpClient(httpClient);
        } catch (Exception e) {
            System.out.println(Arrays.toString(e.getStackTrace()));
            throw new RuntimeException(e.getLocalizedMessage(), e);
        }
    }

    private void registerHttpClientShutdownHook(final HttpClient httpClient) {
        Runtime.getRuntime().addShutdownHook(new Thread("ShutdownHook_HttpClient") {
            @Override
            public void run() {
                try {
                    if (!httpClient.isStopped()) {
                        httpClient.stop();
                    }
                } catch (Throwable e) {
                    String message = "HttpClient did not stop as expected.";
                    System.out.println(message);
                    System.out.println(Arrays.toString(e.getStackTrace()));
                }
            }
        });
    }
}
