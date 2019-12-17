package ai.labs.server.bootstrap;

import ai.labs.persistence.IResourceStore;
import ai.labs.runtime.SwaggerServletContextListener;
import ai.labs.runtime.bootstrap.AbstractBaseModule;
import ai.labs.server.IServerRuntime;
import ai.labs.server.MongoLoginService;
import ai.labs.server.ServerRuntime;
import ai.labs.server.providers.CorsBasicAuthenticator;
import ai.labs.user.IUserStore;
import ai.labs.user.model.User;
import ai.labs.utilities.RuntimeUtilities;
import ai.labs.utilities.StringUtilities;
import com.google.inject.Provider;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.commons.lang3.RandomStringUtils;
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
import java.util.concurrent.ThreadPoolExecutor;

import static ai.labs.utilities.SecurityUtilities.generateSalt;

/**
 * @author ginccc
 */
public class ServerRuntimeModule extends AbstractBaseModule {
    private static final String AUTHENTICATION_BASIC_AUTH = "basic";
    private static final String AUTHENTICATION_KEYCLOAK = "keycloak";
    private static final String HTTP_METHOD_OPTIONS = "OPTIONS";

    public ServerRuntimeModule(InputStream... configFile) {
        super(configFile);
    }

    @Override
    protected void configure() {
        registerConfigFiles(configFiles);
        bind(LoginService.class).to(MongoLoginService.class).in(Scopes.SINGLETON);
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
                                               Provider<BasicSecurityHandler> basicSecurityHandlerProvider,
                                               Provider<KeycloakSecurityHandler> keycloakSecurityHandlerProvider,
                                               GuiceResteasyBootstrapServletContextListener contextListener,
                                               SwaggerServletContextListener swaggerContextListener,
                                               ThreadPoolExecutor threadPoolExecutor,
                                               HttpServletDispatcher httpServletDispatcher,
                                               MongoLoginService mongoLoginService,
                                               AdapterConfig keycloakAdapterConfig,
                                               MeterRegistry meterRegistry)
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
        options.securityHandlerType = securityHandlerType;

        SecurityHandler securityHandler = null;
        if (AUTHENTICATION_BASIC_AUTH.equals(securityHandlerType)) {
            securityHandler = basicSecurityHandlerProvider.get();
        } else if (AUTHENTICATION_KEYCLOAK.equals(securityHandlerType)) {
            securityHandler = keycloakSecurityHandlerProvider.get();
        }

        return new ServerRuntime(options, contextListener, swaggerContextListener, httpServletDispatcher,
                securityHandler, threadPoolExecutor, mongoLoginService, keycloakAdapterConfig, meterRegistry, environment, resourceDir);
    }

    @Provides
    @Singleton
    private BasicSecurityHandler provideBasicAuthenticationService(@Named("webServer.publicAccessPaths") String publicAccessPaths,
                                                                   @Named("webServer.basicAuth.defaultUsername") String defaultUsername,
                                                                   @Named("webServer.basicAuth.defaultPassword") String defaultPassword,
                                                                   IUserStore userStore) throws IResourceStore.ResourceStoreException {
        var securityHandler = new BasicSecurityHandler();
        setupSecurityHandler(securityHandler, publicAccessPaths, "admin", "user");
        securityHandler.setAuthenticator(new CorsBasicAuthenticator());

        try {
            userStore.searchUser(defaultUsername);
        } catch (IResourceStore.ResourceNotFoundException e) {
            if (userStore.getUsersCount() == 0) {
                if (RuntimeUtilities.isNullOrEmpty(defaultPassword)) {
                    defaultPassword = RandomStringUtils.randomAlphanumeric(8);
                }

                userStore.createUser(
                        new User(defaultUsername, defaultPassword, generateSalt(), "", "EDDI"));

                String message = "INFO Basic Authentication has been switch on. No User in the database.\n" +
                        "Creating Default User...\n" +
                        "Default User created (username=%s , password=%s).";
                System.out.println(String.format(message, defaultUsername, defaultPassword));
            }
        }

        return securityHandler;
    }

    @Provides
    @Singleton
    private KeycloakSecurityHandler provideKeycloakAuthenticationService(@Named("webServer.publicAccessPaths") String publicAccessPaths,
                                                                         @Named("webserver.keycloak.admin") String admin,
                                                                         @Named("webserver.keycloak.user") String user,
                                                                         @Named("webserver.keycloak.realm") String realm,
                                                                         @Named("webserver.keycloak.realmKey") String realmKey,
                                                                         @Named("webserver.keycloak.authServerUrl") String authServerUrl,
                                                                         @Named("webserver.keycloak.sslRequired") String sslRequired,
                                                                         @Named("webserver.keycloak.resource") String resource,
                                                                         @Named("webserver.keycloak.publicClient") Boolean publicClient,
                                                                         AdapterConfig keycloakAdapterConfig) {
        // Standard Login
        var securityHandler = new KeycloakSecurityHandler();
        setupSecurityHandler(securityHandler, publicAccessPaths, admin, user);

        // Keycloak
        var keycloakAuthenticator = new KeycloakJettyAuthenticator();
        keycloakAdapterConfig.setRealm(realm);
        keycloakAdapterConfig.setRealmKey(realmKey);
        keycloakAdapterConfig.setAuthServerUrl(authServerUrl);
        keycloakAdapterConfig.setSslRequired(sslRequired);
        keycloakAdapterConfig.setResource(resource);
        keycloakAdapterConfig.setPublicClient(publicClient);
        keycloakAdapterConfig.setCors(true);
        keycloakAuthenticator.setAdapterConfig(keycloakAdapterConfig);

        securityHandler.setAuthenticator(keycloakAuthenticator);
        return securityHandler;
    }

    @Provides
    @Singleton
    private AdapterConfig provideAdapterConfig() {
        return new AdapterConfig();
    }

    private void setupSecurityHandler(ConstraintSecurityHandler securityHandler,
                                      String publicAccessPaths, String admin, String user) {

        securityHandler.addRole(admin);
        securityHandler.addRole(user);

        var constraint = new Constraint();
        constraint.setAuthenticate(true);
        constraint.setRoles(new String[]{admin, user});

        // require auth on all paths
        var mapping = new ConstraintMapping();
        mapping.setConstraint(constraint);
        mapping.setPathSpec("/*");
        securityHandler.addConstraintMapping(mapping);

        // expect those paths given in webServer.publicAccessPaths , separated by ";"
        constraint = new Constraint();
        constraint.setAuthenticate(false);
        constraint.setRoles(new String[]{admin, user});

        for (String path : publicAccessPaths.split(";")) {
            mapping = new ConstraintMapping();
            mapping.setConstraint(constraint);
            mapping.setPathSpec(path.trim());
            securityHandler.addConstraintMapping(mapping);
        }

        // exclude OPTIONS method from authentication
        constraint = new Constraint();
        constraint.setAuthenticate(false);
        constraint.setRoles(new String[]{admin, user});
        mapping = new ConstraintMapping();
        mapping.setMethod(HTTP_METHOD_OPTIONS);
        mapping.setConstraint(constraint);
        mapping.setPathSpec("/*");
        securityHandler.addConstraintMapping(mapping);
    }

    private static class BasicSecurityHandler extends ConstraintSecurityHandler {
        //for reflection purpose
    }

    private static class KeycloakSecurityHandler extends ConstraintSecurityHandler {
        //for reflection purpose
    }
}