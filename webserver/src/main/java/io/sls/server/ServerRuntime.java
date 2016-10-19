package io.sls.server;

import io.sls.runtime.SystemRuntime;
import io.sls.runtime.ThreadContext;
import io.sls.utilities.RuntimeUtilities;
import io.sls.utilities.StringUtilities;
import org.eclipse.jetty.http.HttpVersion;
import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.security.authentication.BasicAuthenticator;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Slf4jLog;
import org.eclipse.jetty.util.security.Constraint;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.ExecutorThreadPool;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.jboss.resteasy.jsapi.JSAPIServlet;
import org.jboss.resteasy.plugins.guice.GuiceResteasyBootstrapServletContextListener;
import org.jboss.resteasy.plugins.providers.RegisterBuiltin;
import org.jboss.resteasy.plugins.server.servlet.HttpServletDispatcher;
import org.jboss.resteasy.spi.ResteasyProviderFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Named;
import javax.inject.Singleton;
import javax.servlet.*;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.*;

/**
 * User: jarisch
 * Date: 21.11.12
 * Time: 23:09
 */
@Singleton
public class ServerRuntime implements IServerRuntime {

    public static class Options {
        public Class<?> applicationConfiguration;
        public LoginService loginService;
        public String host;
        public int httpPort;
        public int httpsPort;
        public boolean sslOnly;
        public String defaultPath;
        public String pathKeystore;
        public String passwordKeystore;
        public String[] basicAuthPaths;
        public String[] virtualHosts;
        public boolean useCrossSiteScripting;
        public long responseDelayInMillis;
    }

    private static Logger logger = LoggerFactory.getLogger(ServerRuntime.class);
    private static final String ANY_PATH = "/*";

    private Options options;
    private final GuiceResteasyBootstrapServletContextListener resteasyContextListener;
    private final HttpServletDispatcher httpServletDispatcher;
    private final SecurityHandler securityHandler;
    private final String environment;
    private final String baseUri;

    public ServerRuntime(Options options,
                         GuiceResteasyBootstrapServletContextListener resteasyContextListener,
                         HttpServletDispatcher httpServletDispatcher,
                         SecurityHandler securityHandler,
                         @Named("system.environment") String environment,
                         @Named("webServer.baseUri") String baseUri) {
        this.options = options;
        this.resteasyContextListener = resteasyContextListener;
        this.httpServletDispatcher = httpServletDispatcher;
        this.securityHandler = securityHandler;
        this.environment = environment;
        this.baseUri = baseUri;
        for (int i = 0; i < options.basicAuthPaths.length; i++) {
            options.basicAuthPaths[i] = StringUtilities.convertSkipPermissionURIs(options.basicAuthPaths[i]);
        }
        RegisterBuiltin.register(ResteasyProviderFactory.getInstance());
    }

    @Override
    public void startup() throws Exception {
        new Thread(ServerRuntime.class.getSimpleName()) {
            public void run() {
                try {
                    Map<String, String> contextParameter = new HashMap<>();
                    contextParameter.put("resteasy.guice.stage", environment.toUpperCase());
                    contextParameter.put("resteasy.logger.type", "SLF4J");
                    contextParameter.put("resteasy.servlet.mapping.prefix", "/");
                    contextParameter.put("javax.ws.rs.Application", options.applicationConfiguration.getName());

                    startupJetty(contextParameter,
                            Arrays.asList(resteasyContextListener),
                            Arrays.asList(new Filter[0]),
                            Arrays.asList(new HttpServletHolder(httpServletDispatcher, "/*"),
                                    new HttpServletHolder(new JSAPIServlet(), "/rest-js")));
                    logger.info("Jetty has successfully started.");
                } catch (Exception e) {
                    logger.error(e.getLocalizedMessage(), e);
                }
            }
        }.start();
    }

    private void startupJetty(Map<String, String> contextParameters,
                              List<EventListener> eventListeners,
                              final List<Filter> filters,
                              final List<HttpServletHolder> servlets) throws Exception {

        Log.setLog(new Slf4jLog());
        Server server = new Server(createThreadPool());

        HttpConfiguration httpConfig = new HttpConfiguration();
        httpConfig.setSendServerVersion(false);
        httpConfig.setSecureScheme("https");
        httpConfig.setSecurePort(options.httpsPort);
        httpConfig.setOutputBufferSize(32768);

        ServerConnector httpConnector = new ServerConnector(server, new HttpConnectionFactory(httpConfig));
        httpConnector.setPort(options.httpPort);
        httpConnector.setIdleTimeout(30000);

        if ("development".equals(environment)) {
            // skip the HTTPS connector for development
            server.setConnectors(new Connector[]{httpConnector});
        } else {
            SslContextFactory sslContextFactory = new SslContextFactory();
            sslContextFactory.setKeyStorePath(options.pathKeystore);

            if (!RuntimeUtilities.isNullOrEmpty(options.passwordKeystore)) {
                sslContextFactory.setKeyStorePassword(options.passwordKeystore);
                sslContextFactory.setKeyManagerPassword(options.passwordKeystore);
                sslContextFactory.setTrustStorePassword(options.passwordKeystore);
            }

            HttpConfiguration httpsConfig = new HttpConfiguration(httpConfig);
            SecureRequestCustomizer requestCustomizer = new SecureRequestCustomizer();
            requestCustomizer.setStsMaxAge(2000);
            requestCustomizer.setStsIncludeSubDomains(true);
            httpsConfig.addCustomizer(requestCustomizer);

            ServerConnector httpsConnector = new ServerConnector(server,
                    new SslConnectionFactory(sslContextFactory, HttpVersion.HTTP_1_1.asString()),
                    new HttpConnectionFactory(httpsConfig));
            httpsConnector.setPort(options.httpsPort);
            httpsConnector.setIdleTimeout(500000);
            server.setConnectors(new Connector[]{httpConnector, httpsConnector});
        }

        // Set a handler
        final HandlerList handlers = new HandlerList();

        ServletContextHandler servletHandler = new ServletContextHandler(ServletContextHandler.SESSIONS);

        servletHandler.setSecurityHandler(securityHandler);

        handlers.addHandler(servletHandler);

        //set context params
        for (String contextKey : contextParameters.keySet()) {
            final String contextValue = contextParameters.get(contextKey);
            servletHandler.setInitParameter(contextKey, contextValue);
        }

        //set event listeners
        eventListeners.forEach(servletHandler::addEventListener);

        for (Filter filter : filters) {
            servletHandler.addFilter(new FilterHolder(filter), ANY_PATH, getAllDispatcherTypes());
        }

        for (HttpServletHolder httpServletHolder : servlets) {
            Servlet servlet = httpServletHolder.getServlet();
            ServletHolder servletHolder = new ServletHolder(servlet);
            servletHolder.setInitParameters(httpServletHolder.getInitParameter());
            servletHandler.addServlet(servletHolder, httpServletHolder.getPath());
        }

        servletHandler.addFilter(new FilterHolder(createRedirectFilter(options.sslOnly, options.httpsPort, true, options.defaultPath)), ANY_PATH, getAllDispatcherTypes());

        /*if (options.basicAuthPaths.length > 0) {
            servletHandler.setSecurityHandler(basicAuth());
            servletHandler.addFilter(new FilterHolder(createBasicAuthenticationFilter(options.basicAuthPaths)), ANY_PATH, getAllDispatcherTypes());
            logger.info("Basic Authentication has been enabled...");
        }*/
        servletHandler.addFilter(new FilterHolder(createInitThreadBoundValuesFilter()), ANY_PATH, getAllDispatcherTypes());

        if (options.useCrossSiteScripting) {
            //TODO check for production
            //add header param in order to enable cross-site-scripting
            servletHandler.addFilter(new FilterHolder(createCrossSiteScriptFilter()), ANY_PATH, getAllDispatcherTypes());
            logger.info("CrossSiteScriptFilter has been enabled...");
        }

        server.setHandler(handlers);

        // Start the server
        server.start();
        server.join();

    }

    private Filter createRedirectFilter(final boolean useSSLOnly, final int httpsPort, final boolean redirectToDefaultPath, final String defaultPath) {
        return new Filter() {
            private static final String HTTPS = "https";

            @Override
            public void init(FilterConfig filterConfig) throws ServletException {
                //not implemented
            }

            @Override
            public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) throws IOException, ServletException {
                HttpServletRequest httpServletRequest = (HttpServletRequest) servletRequest;
                StringBuffer requestURL = httpServletRequest.getRequestURL();

                if (requestURL != null) {
                    boolean changedProtocol = false;
                    if (useSSLOnly) {
                        changedProtocol = makeUriUseSsl(requestURL, httpsPort);
                    }

                    boolean changedPath = false;
                    if (redirectToDefaultPath) {
                        changedPath = changeDefaultPath(requestURL, httpServletRequest, defaultPath);
                    }

                    if (changedProtocol || changedPath) {
                        ((HttpServletResponse) servletResponse).sendRedirect(requestURL.toString());
                        return;
                    }
                }

                filterChain.doFilter(servletRequest, servletResponse);
            }

            private boolean makeUriUseSsl(StringBuffer requestURL, int httpsPort) throws IOException {
                if (requestURL.indexOf(HTTPS) != 0) {
                    URL url = URI.create(requestURL.toString()).toURL();
                    URL newUrl = new URL(HTTPS, url.getHost(), httpsPort, url.getPath());
                    requestURL.replace(0, requestURL.length(), newUrl.toString());
                    return true;
                }

                return false;
            }

            private boolean changeDefaultPath(StringBuffer requestURL, HttpServletRequest request, String defaultPath) throws MalformedURLException {
                final String currentPath = request.getRequestURI();
                if (!currentPath.equals(defaultPath)) {
                    if ("/".equals(currentPath) || "".equals(currentPath)) {
                        StringBuilder redirectPath = new StringBuilder(defaultPath);
                        String queryString = request.getQueryString();
                        if (queryString != null) {
                            redirectPath.append("?").append(queryString);
                        }

                        URL origUrl = URI.create(requestURL.toString()).toURL();
                        URL newUrl = new URL(origUrl.getProtocol(), origUrl.getHost(), origUrl.getPort(), redirectPath.toString());
                        requestURL.replace(0, requestURL.length(), newUrl.toString());

                        return true;
                    }
                }

                return false;
            }

            @Override
            public void destroy() {
                //not implemented
            }
        };
    }

    private Filter createBasicAuthenticationFilter(final String[] basicAuthPaths) {
        return new Filter() {
            @Override
            public void init(FilterConfig filterConfig) throws ServletException {
                //not implemented
            }

            @Override
            public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) throws IOException, ServletException {
                HttpServletRequest httpServletRequest = (HttpServletRequest) servletRequest;
                if (isBasicAuthRequired(httpServletRequest.getRequestURI()) && httpServletRequest.getRemoteUser() == null) {
                    httpServletRequest.authenticate((HttpServletResponse) servletResponse);
                    return;
                }

                filterChain.doFilter(servletRequest, servletResponse);
            }

            private boolean isBasicAuthRequired(String requestPath) {
                for (String basicAuthPath : basicAuthPaths) {
                    if (requestPath.matches(basicAuthPath)) {
                        return true;
                    }
                }
                return false;
            }

            @Override
            public void destroy() {
                //not implemented
            }
        };
    }

    private Filter createInitThreadBoundValuesFilter() {
        return new Filter() {
            @Override
            public void init(FilterConfig filterConfig) throws ServletException {
                //not implemented
            }

            @Override
            public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) throws IOException, ServletException {
                HttpServletRequest httpservletRequest = (HttpServletRequest) servletRequest;
                URL requestURL = URI.create(httpservletRequest.getRequestURL().toString()).toURL();
                String currentResourceURI = ((Request) httpservletRequest).getHttpURI().getPath();
                ThreadContext.put("currentResourceURI", currentResourceURI);
                ThreadContext.put("currentURLProtocol", requestURL.getProtocol());
                ThreadContext.put("currentURLHost", requestURL.getHost());
                ThreadContext.put("currentURLPort", requestURL.getPort());

                filterChain.doFilter(servletRequest, servletResponse);

                ThreadContext.remove();
            }

            @Override
            public void destroy() {
                //not implemented
            }
        };
    }

    private Filter createCrossSiteScriptFilter() {
        return new Filter() {
            @Override
            public void init(FilterConfig filterConfig) throws ServletException {
                // not implemented
            }

            @Override
            public void doFilter(ServletRequest request, ServletResponse response, FilterChain filterChain) throws IOException, ServletException {
                HttpServletResponse httpResponse = (HttpServletResponse) response;
                httpResponse.setHeader("Access-Control-Allow-Origin", "*");
                httpResponse.setHeader("Access-Control-Request-Method", "GET, PUT, POST, DELETE, PATCH, OPTIONS");
                httpResponse.setHeader("Access-Control-Allow-Headers", "authorization");
                filterChain.doFilter(request, response);
            }

            @Override
            public void destroy() {
                // not implemented
            }
        };
    }

    private Filter createDelayResponseFilter(final long responseDelayInMillis) {
        return new Filter() {
            @Override
            public void init(FilterConfig filterConfig) throws ServletException {
                // not implemented
            }

            @Override
            public void doFilter(ServletRequest request, ServletResponse response, FilterChain filterChain) throws IOException, ServletException {
                try {
                    Thread.sleep(responseDelayInMillis);
                } catch (InterruptedException e) {
                    logger.error(e.getLocalizedMessage(), e);
                } finally {
                    filterChain.doFilter(request, response);
                }
            }

            @Override
            public void destroy() {
                // not implemented
            }
        };
    }

    private SecurityHandler basicAuth() {
        ConstraintSecurityHandler csh = new ConstraintSecurityHandler();
        csh.setAuthenticator(new BasicAuthenticator());
        csh.setRealmName("SLS");
        csh.setLoginService(options.loginService);

        Constraint basicAuthConstraint = new Constraint();
        basicAuthConstraint.setName(Constraint.__BASIC_AUTH);
        basicAuthConstraint.setRoles(new String[]{"user"});
        basicAuthConstraint.setAuthenticate(true);
        basicAuthConstraint.setDataConstraint(Constraint.DC_CONFIDENTIAL);

        ConstraintMapping cm = new ConstraintMapping();
        cm.setConstraint(basicAuthConstraint);
        cm.setPathSpec("/jetty-required-placeholder-url-pattern");
        csh.addConstraintMapping(cm);

        return csh;
    }

    private static EnumSet<DispatcherType> getAllDispatcherTypes() {
        return EnumSet.allOf(DispatcherType.class);
    }

    private ThreadPool createThreadPool() {
        return new ExecutorThreadPool(SystemRuntime.getRuntime().getExecutorService());
    }
}
