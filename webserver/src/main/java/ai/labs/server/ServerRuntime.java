package ai.labs.server;

import ai.labs.runtime.SwaggerServletContextListener;
import ai.labs.runtime.SystemRuntime;
import ai.labs.runtime.ThreadContext;
import ai.labs.utilities.FileUtilities;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.server.*;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.servlet.FilterHolder;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Slf4jLog;
import org.eclipse.jetty.util.thread.ExecutorThreadPool;
import org.eclipse.jetty.util.thread.ThreadPool;
import org.jboss.resteasy.jsapi.JSAPIServlet;
import org.jboss.resteasy.plugins.guice.GuiceResteasyBootstrapServletContextListener;
import org.jboss.resteasy.plugins.providers.RegisterBuiltin;
import org.jboss.resteasy.plugins.server.servlet.HttpServletDispatcher;
import org.jboss.resteasy.spi.ResteasyProviderFactory;
import ro.isdc.wro.http.WroFilter;

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
 * @author ginccc
 */
@Singleton
@Slf4j
public class ServerRuntime implements IServerRuntime {

    public static class Options {
        public Class<?> applicationConfiguration;
        public LoginService loginService;
        public String host;
        public int httpPort;
        public String defaultPath;
        public String[] virtualHosts;
        public boolean useCrossSiteScripting;
        public long responseDelayInMillis;
    }

    private static final String ANY_PATH = "/*";

    private Options options;
    private final GuiceResteasyBootstrapServletContextListener resteasyContextListener;
    private final SwaggerServletContextListener swaggerContextListener;
    private final HttpServletDispatcher httpServletDispatcher;
    private final SecurityHandler securityHandler;
    private final String environment;
    private final String resourceDir;

    public ServerRuntime(Options options,
                         GuiceResteasyBootstrapServletContextListener resteasyContextListener,
                         SwaggerServletContextListener swaggerContextListener,
                         HttpServletDispatcher httpServletDispatcher,
                         SecurityHandler securityHandler,
                         @Named("system.environment") String environment,
                         @Named("systemRuntime.resourceDir") String resourceDir) {
        this.options = options;
        this.resteasyContextListener = resteasyContextListener;
        this.swaggerContextListener = swaggerContextListener;
        this.httpServletDispatcher = httpServletDispatcher;
        this.securityHandler = securityHandler;
        this.environment = environment;
        this.resourceDir = resourceDir;
        RegisterBuiltin.register(ResteasyProviderFactory.getInstance());
    }

    @Override
    public void startup(final IStartupCompleteListener completeListener) {
        new Thread(ServerRuntime.class.getSimpleName()) {
            public void run() {
                try {
                    Map<String, String> contextParameter = new HashMap<>();
                    contextParameter.put("resteasy.guice.stage", environment.toUpperCase());
                    contextParameter.put("resteasy.logger.type", "SLF4J");
                    contextParameter.put("resteasy.servlet.mapping.prefix", "/");
                    contextParameter.put("javax.ws.rs.Application", options.applicationConfiguration.getName());

                    startupJetty(contextParameter,
                            Arrays.asList(resteasyContextListener, swaggerContextListener),
                            Collections.singletonList(new FilterMappingHolder(new WroFilter(), "/text/*")),
                            Arrays.asList(new HttpServletHolder(httpServletDispatcher, "/*"),
                                    new HttpServletHolder(new JSAPIServlet(), "/rest-js")),
                            FileUtilities.buildPath(System.getProperty("user.dir"), resourceDir),
                            completeListener);
                    log.info("Jetty has successfully started.");
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
                              final String resourcePath,
                              final IStartupCompleteListener completeListener) throws Exception {

        Log.setLog(new Slf4jLog());
        Server server = new Server(createThreadPool());

        HttpConfiguration httpConfig = new HttpConfiguration();
        httpConfig.setSendServerVersion(false);
        httpConfig.setOutputBufferSize(32768);

        ServerConnector httpConnector = new ServerConnector(server, new HttpConnectionFactory(httpConfig));
        httpConnector.setPort(options.httpPort);
        httpConnector.setIdleTimeout(30000);


        server.setConnectors(new Connector[]{httpConnector});


        // Set a handler
        final HandlerList handlers = new HandlerList();

        ServletContextHandler servletHandler = new ServletContextHandler(ServletContextHandler.SESSIONS);
        servletHandler.setResourceBase(resourcePath);

        if (securityHandler != null) {
            servletHandler.setSecurityHandler(securityHandler);
        }

        handlers.addHandler(servletHandler);

        //set context params
        for (String contextKey : contextParameters.keySet()) {
            final String contextValue = contextParameters.get(contextKey);
            servletHandler.setInitParameter(contextKey, contextValue);
        }

        //set event listeners
        eventListeners.forEach(servletHandler::addEventListener);


        for (FilterMappingHolder filter : filters) {
            servletHandler.addFilter(new FilterHolder(filter.filter), filter.mappingPath, getAllDispatcherTypes());

        }

        for (HttpServletHolder httpServletHolder : servlets) {
            Servlet servlet = httpServletHolder.getServlet();
            ServletHolder servletHolder = new ServletHolder(servlet);
            servletHolder.setInitParameters(httpServletHolder.getInitParameter());
            servletHandler.addServlet(servletHolder, httpServletHolder.getPath());
        }

        servletHandler.addFilter(new FilterHolder(createRedirectFilter(options.defaultPath)), ANY_PATH, getAllDispatcherTypes());

        servletHandler.addFilter(new FilterHolder(createInitThreadBoundValuesFilter()), ANY_PATH, getAllDispatcherTypes());

        if (options.useCrossSiteScripting) {
            //TODO check for production
            //add header param in order to enable cross-site-scripting
            servletHandler.addFilter(new FilterHolder(createCrossSiteScriptFilter()), ANY_PATH, getAllDispatcherTypes());
            log.info("CrossSiteScriptFilter has been enabled...");
        }

        server.setHandler(handlers);

        // Start the server
        server.start();
        completeListener.onComplete();
        server.join();

    }

    private Filter createRedirectFilter(final String defaultPath) {
        return new Filter() {

            @Override
            public void init(FilterConfig filterConfig) throws ServletException {
                //not implemented
            }

            @Override
            public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) throws IOException, ServletException {
                HttpServletRequest httpServletRequest = (HttpServletRequest) servletRequest;
                StringBuffer requestURL = httpServletRequest.getRequestURL();

                if (requestURL != null) {

                    boolean changedPath;
                    changedPath = changeDefaultPath(requestURL, httpServletRequest, defaultPath);

                    if (changedPath) {
                        ((HttpServletResponse) servletResponse).sendRedirect(requestURL.toString());
                        return;
                    }
                }

                filterChain.doFilter(servletRequest, servletResponse);
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
                String currentResourceURI = ((Request) httpservletRequest).getHttpURI().getPathQuery();
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

    private static EnumSet<DispatcherType> getAllDispatcherTypes() {
        return EnumSet.allOf(DispatcherType.class);
    }

    private ThreadPool createThreadPool() {
        return new ExecutorThreadPool(SystemRuntime.getRuntime().getExecutorService());
    }

    @AllArgsConstructor
    private static class FilterMappingHolder {
        private Filter filter;
        private String mappingPath;
    }
}
