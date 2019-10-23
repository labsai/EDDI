package ai.labs.httpclient.guice;

import ai.labs.httpclient.IHttpClient;
import ai.labs.httpclient.impl.HttpClientWrapper;
import ai.labs.runtime.bootstrap.AbstractBaseModule;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import org.eclipse.jetty.client.HttpClient;
import org.eclipse.jetty.util.ssl.SslContextFactory;

import javax.inject.Named;
import javax.inject.Singleton;
import java.io.InputStream;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;

public class HttpClientModule extends AbstractBaseModule {

    public HttpClientModule(InputStream... configFiles) {
        super(configFiles);
    }

    @Override
    protected void configure() {
        registerConfigFiles(configFiles);

        bind(IHttpClient.class).to(HttpClientWrapper.class).in(Scopes.SINGLETON);
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
            SslContextFactory.Client sslContextFactory = new SslContextFactory.Client();
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
