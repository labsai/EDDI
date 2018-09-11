package ai.labs.server.bootstrap;

import ai.labs.runtime.SwaggerServletContextListener;
import ai.labs.runtime.bootstrap.AbstractBaseModule;
import ai.labs.server.IServerRuntime;
import ai.labs.server.MongoLoginService;
import ai.labs.server.ServerRuntime;
import ai.labs.utilities.StringUtilities;
import com.google.inject.Provider;
import com.google.inject.Provides;
import org.eclipse.jetty.security.ConstraintMapping;
import org.eclipse.jetty.security.ConstraintSecurityHandler;
import org.eclipse.jetty.security.LoginService;
import org.eclipse.jetty.security.SecurityHandler;
import org.eclipse.jetty.util.security.Constraint;
import org.jboss.resteasy.plugins.guice.GuiceResteasyBootstrapServletContextListener;
import org.jboss.resteasy.plugins.server.servlet.HttpServletDispatcher;
import org.keycloak.adapters.jetty.KeycloakJettyAuthenticator;
import org.keycloak.representations.adapters.config.AdapterConfig;

import javax.inject.Named;
import javax.inject.Singleton;
import java.io.File;
import java.io.InputStream;

/**
 * @author ginccc
 */
public class ServerRuntimeModule extends AbstractBaseModule {
    private static final String AUTHENTICATION_BASIC_AUTH = "basic";
    private static final String AUTHENTICATION_KEYCLOAK = "keycloak";

    public ServerRuntimeModule(InputStream... configFile) {
        super(configFile);
    }

    @Override
    protected void configure() {
        registerConfigFiles(configFiles);
        bind(LoginService.class).to(MongoLoginService.class);
    }

    @Provides
    @Singleton
    public IServerRuntime provideServerRuntime(@Named("system.environment") String environment,
                                               @Named("systemRuntime.resourceDir") String resourceDir,
                                               @Named("webServer.applicationConfigurationClass") String applicationConfigurationClass,
                                               @Named("webServer.host") String host,
                                               @Named("webServer.httpPort") Integer httpPort,
                                               @Named("webServer.httpsPort") Integer httpsPort,
                                               @Named("webServer.keyStorePassword") String keyStorePassword,
                                               @Named("webServer.keyStorePath") String keyStorePath,
                                               @Named("webServer.defaultPath") String defaultPath,
                                               @Named("webServer.responseDelayInMillis") Long responseDelayInMillis,
                                               @Named("webServer.virtualHosts") String virtualHosts,
                                               @Named("webServer.useCrossSiteScriptingHeaderParam") Boolean useCrossSiteScriptingHeaderParam,
                                               @Named("webServer.idleTime") Long idleTime,
                                               @Named("webServer.outputBufferSize") Integer outputBufferSize,
                                               @Named("webServer.securityHandlerType") String securityHandlerType,
            /*@Named("webServer.securityPaths") String securityPaths,*/
                                               Provider<SecurityHandler> securityHandlerProvider,
                                               GuiceResteasyBootstrapServletContextListener contextListener,
                                               SwaggerServletContextListener swaggerContextListener,
                                               HttpServletDispatcher httpServletDispatcher,
                                               LoginService mongoLoginService)
            throws ClassNotFoundException {

        ServerRuntime.Options options = new ServerRuntime.Options();
        options.applicationConfiguration = Class.forName(applicationConfigurationClass);
        options.loginService = mongoLoginService;
        options.host = host;
        options.httpPort = httpPort;
        options.httpsPort = httpsPort;
        options.keyStorePassword = keyStorePassword;
        options.keyStorePath = StringUtilities.joinStrings(File.separator,
                System.getProperty("user.dir"), "resources",
                keyStorePath);
        options.defaultPath = defaultPath;
        options.responseDelayInMillis = responseDelayInMillis;
        options.virtualHosts = virtualHosts.split(";");
        options.useCrossSiteScripting = useCrossSiteScriptingHeaderParam;
        options.idleTime = idleTime;
        options.outputBufferSize = outputBufferSize;

        SecurityHandler securityHandler = null;
        if (AUTHENTICATION_BASIC_AUTH.equals(securityHandlerType)) {
            securityHandler = null; // todo add mongo
        } else if (AUTHENTICATION_KEYCLOAK.equals(securityHandlerType)) {
            securityHandler = securityHandlerProvider.get();
        }

        return new ServerRuntime(options, contextListener, swaggerContextListener, httpServletDispatcher,
                securityHandler, environment, resourceDir);
    }

    @Provides
    @Singleton
    private SecurityHandler provideAuthenticationService(@Named("webserver.keycloak.admin") String admin,
                                                         @Named("webserver.keycloak.user") String user,
                                                         @Named("webserver.keycloak.publicAccessPaths") String publicAccessPaths,
                                                         @Named("webserver.keycloak.realm") String realm,
                                                         @Named("webserver.keycloak.realmKey") String realmKey,
                                                         @Named("webserver.keycloak.authServerUrl") String authServerUrl,
                                                         @Named("webserver.keycloak.sslRequired") String sslRequired,
                                                         @Named("webserver.keycloak.resource") String resource,
                                                         @Named("webserver.keycloak.publicClient") Boolean publicClient) {
        // Standard Login
        ConstraintSecurityHandler securityHandler = new ConstraintSecurityHandler();
        securityHandler.addRole(admin);
        securityHandler.addRole(user);

        Constraint constraint = new Constraint();
        constraint.setAuthenticate(true);
        constraint.setRoles(new String[]{admin, user});

        // require auth on all paths
        ConstraintMapping mapping = new ConstraintMapping();
        mapping.setConstraint(constraint);
        mapping.setPathSpec("/*");
        securityHandler.addConstraintMapping(mapping);

        // expect those paths given in webserver.keycloak.publicAccessPaths , separated by ";"
        constraint = new Constraint();
        constraint.setAuthenticate(false);

        for (String path : publicAccessPaths.split(";")) {
            mapping = new ConstraintMapping();
            mapping.setConstraint(constraint);
            mapping.setPathSpec(path);
            securityHandler.addConstraintMapping(mapping);
        }

        // Keycloak
        KeycloakJettyAuthenticator keycloakAuthenticator = new KeycloakJettyAuthenticator();
        AdapterConfig keycloakAdapterConfig = new AdapterConfig();
        keycloakAdapterConfig.setRealm(realm);
        keycloakAdapterConfig.setRealmKey(realmKey);
        keycloakAdapterConfig.setAuthServerUrl(authServerUrl);
        keycloakAdapterConfig.setSslRequired(sslRequired);
        keycloakAdapterConfig.setResource(resource);
        keycloakAdapterConfig.setPublicClient(publicClient);
        keycloakAdapterConfig.setCors(true);
        /*keycloakAdapterConfig.setTokenStore("cookie");*/
        keycloakAuthenticator.setAdapterConfig(keycloakAdapterConfig);
        securityHandler.setAuthenticator(keycloakAuthenticator);
        return securityHandler;
    }
}