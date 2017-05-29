package ai.labs.httpclient.guice;

import ai.labs.httpclient.IHttpClient;
import ai.labs.httpclient.impl.HttpClientWrapper;
import ai.labs.runtime.bootstrap.AbstractBaseModule;
import com.google.inject.Provides;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Named;
import javax.inject.Singleton;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;

public class HttpClientModule extends AbstractBaseModule {
    private Logger logger = LoggerFactory.getLogger(this.getClass());

    public HttpClientModule(InputStream... configFiles) {
        super(configFiles);
    }

    @Override
    protected void configure() {
        registerConfigFiles(configFiles);

        bind(IHttpClient.class).to(HttpClientWrapper.class);
    }

    @Provides
    @Singleton
    public HttpClient provideHttpClient(ExecutorService executorService,
                                        @Named("httpClient.maxConnectionsQueued") Integer maxConnectionsQueued,
                                        @Named("httpClient.maxConnectionPerRoute") Integer maxConnectionPerRoute,
                                        @Named("httpClient.requestBufferSize") Integer requestBufferSize,
                                        @Named("httpClient.responseBufferSize") Integer responseBufferSize,
                                        @Named("httpClient.maxRedirects") Integer maxRedirects,
                                        @Named("httpClient.trustAllCertificates") Boolean trustAllCertificates) {

        try {
            SslContextFactory sslContextFactory = new SslContextFactory();
            sslContextFactory.setTrustAll(trustAllCertificates);
            HttpClient httpClient = new HttpClient(sslContextFactory);
            httpClient.setExecutor(executorService);
            httpClient.setMaxConnectionsPerDestination(maxConnectionsQueued);
            httpClient.setMaxRequestsQueuedPerDestination(maxConnectionPerRoute);
            httpClient.setRequestBufferSize(requestBufferSize);
            httpClient.setResponseBufferSize(responseBufferSize);
            httpClient.setMaxRedirects(maxRedirects);
            httpClient.start();

            registerHttpClientShutdownHook(httpClient);

            return httpClient;
        } catch (Exception e) {
            logger.error(e.getLocalizedMessage(), e);
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
                    logger.error(message, e);
                }
            }
        });
    }
}
