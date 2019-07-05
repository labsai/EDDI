package ai.labs.server;

import ai.labs.runtime.SwaggerServletContextListener;
import ai.labs.utilities.FileUtilities;
import io.undertow.Handlers;
import io.undertow.Undertow;
import io.undertow.UndertowOptions;
import io.undertow.attribute.ExchangeAttributes;
import io.undertow.server.handlers.LearningPushHandler;
import io.undertow.server.handlers.resource.PathResourceManager;
import io.undertow.server.session.InMemorySessionManager;
import io.undertow.server.session.SessionAttachmentHandler;
import io.undertow.server.session.SessionCookieConfig;
import io.undertow.util.Headers;
import io.undertow.util.StatusCodes;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jboss.resteasy.plugins.providers.RegisterBuiltin;
import org.jboss.resteasy.plugins.server.servlet.HttpServletDispatcher;
import org.jboss.resteasy.plugins.server.undertow.UndertowJaxrsServer;
import org.jboss.resteasy.spi.ResteasyProviderFactory;
import org.keycloak.adapters.KeycloakDeploymentBuilder;
import org.keycloak.representations.adapters.config.AdapterConfig;
import ro.isdc.wro.http.WroFilter;

import javax.inject.Named;
import javax.inject.Singleton;
import javax.net.ssl.*;
import javax.servlet.Filter;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.ThreadPoolExecutor;

import static io.undertow.Handlers.predicate;
import static io.undertow.Handlers.resource;
import static io.undertow.predicate.Predicates.secure;

@Singleton
@Slf4j
public class UndertowServerRuntime implements IServerRuntime {
    private static final String BASIC_AUTH_SECURITY_HANDLER_TYPE = "basic";

    public static class Options {
        public Class<?> applicationConfiguration;
        public io.undertow.security.handlers.AuthenticationCallHandler loginService;
        public String host;
        public int httpPort;
        public int httpsPort;
        public String keyStorePath;
        public String keyStorePassword;
        public String defaultPath;
        public String[] virtualHosts;
        public boolean useCrossSiteScripting;
        public long responseDelayInMillis;
        public long idleTime;
        public int outputBufferSize;
        public String securityHandlerType;
    }

    private static final String ANY_PATH = "/*";

    private Options options;
    private final SwaggerServletContextListener swaggerContextListener;
    private final HttpServletDispatcher httpServletDispatcher;
    private final ThreadPoolExecutor threadPoolExecutor;
    private final MongoLoginService mongoLoginService;
    private final AdapterConfig keycloakAdapterConfig;
    private final String environment;
    private final String resourceDir;

    public UndertowServerRuntime(Options options,
                                 SwaggerServletContextListener swaggerContextListener,
                                 HttpServletDispatcher httpServletDispatcher,
                                 ThreadPoolExecutor threadPoolExecutor,
                                 MongoLoginService mongoLoginService,
                                 AdapterConfig keycloakAdapterConfig,
                                 @Named("system.environment") String environment,
                                 @Named("systemRuntime.resourceDir") String resourceDir) {
        this.options = options;
        this.swaggerContextListener = swaggerContextListener;
        this.httpServletDispatcher = httpServletDispatcher;
        this.threadPoolExecutor = threadPoolExecutor;
        this.mongoLoginService = mongoLoginService;
        this.keycloakAdapterConfig = keycloakAdapterConfig;
        this.environment = environment;
        this.resourceDir = resourceDir;
        RegisterBuiltin.register(ResteasyProviderFactory.getInstance());
    }

    @Override
    public void startup(IStartupCompleteListener completeListener) {
        new Thread(UndertowServerRuntime.class.getSimpleName()) {
            public void run() {
                try {
                    Map<String, String> contextParameter = new HashMap<>();
                   /* contextParameter.put("resteasy.guice.stage", environment.toUpperCase());
                    contextParameter.put("resteasy.logger.type", "SLF4J");
                    contextParameter.put("resteasy.servlet.mapping.prefix", "/");
                    contextParameter.put("javax.ws.rs.Application", options.applicationConfiguration.getName());*/

                    startupJetty(contextParameter,
                            Collections.singletonList(swaggerContextListener),
                            Arrays.asList(new FilterMappingHolder(
                                            new KeycloakOIDCFilter(
                                                    facade -> KeycloakDeploymentBuilder.build(keycloakAdapterConfig)), "/keycloak/*"),
                                    new FilterMappingHolder(new WroFilter(), "/text/*")),
                            Arrays.asList(new HttpServletHolder(httpServletDispatcher, "/*"),
                                    new HttpServletHolder(new JSAPIServlet(), "/rest-js")),
                            FileUtilities.buildPath(System.getProperty("user.dir"), resourceDir));
                    log.info("Undertow has successfully started.");
                    completeListener.onComplete();
                } catch (Exception e) {
                    log.error(e.getLocalizedMessage(), e);
                }
            }
        }.start();
    }

    private void startupJetty(Map<String, String> contextParameters,
                              List<EventListener> eventListeners,
                              final List<FilterMappingHolder> filters,
                              final List<HttpServletHolder> servlets,
                              final String resourcePath) throws Exception {

        Log.setLog(new Slf4jLog());

        SSLContext sslContext = createSSLContext(loadKeyStore("webServer.keyStorePath"), loadKeyStore("webServer.keyStorePath"));
        UndertowJaxrsServer jaxrsServer = new UndertowJaxrsServer();
        jaxrsServer.start();
        Undertow server = Undertow.builder()
                .setServerOption(UndertowOptions.ENABLE_HTTP2, true)
                .addHttpListener(options.httpPort, options.host)
                .addHttpsListener(options.httpsPort, options.host, sslContext)
                .setHandler(new SessionAttachmentHandler(
                        new LearningPushHandler(100, -1, Handlers.header(predicate(secure(),
                                resource(new PathResourceManager(Paths.get(System.getProperty("example.directory", System.getProperty("user.home"))), 100))
                                        .setDirectoryListingEnabled(true), exchange -> {
                                    exchange.getResponseHeaders().add(Headers.LOCATION,
                                            "https://" + exchange.getHostName() + ":" + (exchange.getHostPort() + 363) + exchange.getRelativePath());
                                    exchange.setStatusCode(StatusCodes.TEMPORARY_REDIRECT);
                                }), "x-undertow-transport", ExchangeAttributes.transportProtocol())), new InMemorySessionManager("test"), new SessionCookieConfig())).build();

        server.start();
    }

    private static KeyStore loadKeyStore(String name) throws Exception {
        String storeLoc = System.getProperty(name);
        final InputStream stream;
        if (storeLoc == null) {
            stream = UndertowServerRuntime.class.getResourceAsStream(name);
        } else {
            stream = Files.newInputStream(Paths.get(storeLoc));
        }

        if (stream == null) {
            throw new RuntimeException("Could not load keystore");
        }
        try (InputStream is = stream) {
            KeyStore loadedKeystore = KeyStore.getInstance("JKS");
            loadedKeystore.load(is, password(name));
            return loadedKeystore;
        }
    }

    static char[] password(String name) {
        String pw = System.getProperty(name + ".password");
        return pw != null ? pw.toCharArray() : "".toCharArray();
    }


    private static SSLContext createSSLContext(final KeyStore keyStore, final KeyStore trustStore) throws Exception {
        KeyManager[] keyManagers;
        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, password("key"));
        keyManagers = keyManagerFactory.getKeyManagers();

        TrustManager[] trustManagers;
        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(trustStore);
        trustManagers = trustManagerFactory.getTrustManagers();

        SSLContext sslContext;
        sslContext = SSLContext.getInstance("TLS");
        sslContext.init(keyManagers, trustManagers, null);

        return sslContext;
    }

    @AllArgsConstructor
    private static class FilterMappingHolder {
        private Filter filter;
        private String mappingPath;
    }
}
