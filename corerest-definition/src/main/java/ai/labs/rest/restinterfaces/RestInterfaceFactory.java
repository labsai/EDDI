package ai.labs.rest.restinterfaces;

import ai.labs.runtime.ThreadContext;
import org.apache.http.client.HttpClient;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.conn.ssl.X509HostnameVerifier;
import org.apache.http.impl.client.DefaultHttpClient;
import org.jboss.resteasy.client.jaxrs.ResteasyClient;
import org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder;
import org.jboss.resteasy.client.jaxrs.ResteasyWebTarget;
import org.jboss.resteasy.client.jaxrs.engines.ApacheHttpClient4Engine;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.net.ssl.*;
import javax.ws.rs.client.ClientRequestFilter;
import java.io.IOException;
import java.net.URI;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;

/**
 * @author ginccc
 */
@Singleton
public class RestInterfaceFactory implements IRestInterfaceFactory {
    private final Map<String, ResteasyClient> clients = new HashMap<>();
    private final HttpClient httpClient;

    @Inject
    public RestInterfaceFactory(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    @Override
    public <T> T get(Class<T> clazz, String targetServerUri) throws RestInterfaceFactoryException {
        Object context = ThreadContext.get("security.token");
        String securityToken = context != null ? context.toString(): null;
        return get(clazz, targetServerUri, securityToken);
    }

    @Override
    public <T> T get(Class<T> clazz, String targetServerUri, String securityToken) throws RestInterfaceFactoryException {
        ResteasyClient client = getResteasyClient(targetServerUri);
        ResteasyWebTarget target = client.target(targetServerUri);

        if (securityToken != null) {
            target.register((ClientRequestFilter) requestContext ->
                    requestContext.getHeaders().add("Authorization", "Bearer " + securityToken));
        }

        return target.proxy(clazz);
    }

    private ResteasyClient getResteasyClient(String targetServerUri) throws RestInterfaceFactoryException {
        ResteasyClient client = clients.get(targetServerUri);
        if(client == null) {
            HttpClient httpClient = targetServerUri.startsWith("https") ?
                    prepareClientForSSL(this.httpClient, URI.create(targetServerUri)) : this.httpClient;

            ApacheHttpClient4Engine engine = new ApacheHttpClient4Engine(httpClient);
            ResteasyClientBuilder clientBuilder = new ResteasyClientBuilder();
            clientBuilder.httpEngine(engine);

            client = clientBuilder.build();
            clients.put(targetServerUri, client);
        }

        return client;
    }

    private HttpClient prepareClientForSSL(HttpClient base, URI baseURI) throws RestInterfaceFactoryException {
        try {
            SSLContext sslContext = SSLContext.getInstance("SSL");
            X509TrustManager tm = new X509TrustManager() {

                public void checkClientTrusted(X509Certificate[] xcs, String string) throws CertificateException {
                }

                public void checkServerTrusted(X509Certificate[] xcs, String string) throws CertificateException {
                }

                public X509Certificate[] getAcceptedIssuers() {
                    return null;
                }
            };
            X509HostnameVerifier verifier = new X509HostnameVerifier() {

                @Override
                public void verify(String string, SSLSocket ssls) throws IOException {
                }

                @Override
                public void verify(String string, X509Certificate xc) throws SSLException {
                }

                @Override
                public void verify(String string, String[] strings, String[] strings1) throws SSLException {
                }

                @Override
                public boolean verify(String string, SSLSession ssls) {
                    return true;
                }
            };
            sslContext.init(null, new TrustManager[]{tm}, null);
            SSLSocketFactory sslSocketFactory = new SSLSocketFactory(sslContext);
            sslSocketFactory.setHostnameVerifier(verifier);
            ClientConnectionManager ccm = base.getConnectionManager();
            SchemeRegistry schemeRegistry = ccm.getSchemeRegistry();
            schemeRegistry.register(new Scheme(baseURI.getScheme(), baseURI.getPort(), sslSocketFactory));
            return new DefaultHttpClient(ccm, base.getParams());
        } catch (Exception ex) {
            throw new RestInterfaceFactoryException("Error while preparing client connection for server.", ex);
        }
    }

    public static class RestInterfaceFactoryException extends Exception {
        public RestInterfaceFactoryException(String message, Exception e) {
            super(message, e);
        }
    }
}
