package ai.labs.server.bootstrap;

import ai.labs.runtime.bootstrap.AbstractBaseModule;

import java.io.InputStream;

/**
 * @author ginccc
 */
public class ServerRuntimeModule extends AbstractBaseModule {
    private static final String AUTHENTICATION_BASIC_AUTH = "basic";
    private static final String AUTHENTICATION_KEYCLOAK = "keycloak";
    public static final String HTTP_METHOD_OPTIONS = "OPTIONS";

    public ServerRuntimeModule(InputStream... configFile) {
        super(configFile);
    }

    @Override
    protected void configure() {
        registerConfigFiles(configFiles);
    }

   /* @Provides
    @Singleton
    private IdentityManager provideBasicAuthenticationService(@Named("webServer.publicAccessPaths") String publicAccessPaths,
                                                              @Named("webServer.basicAuth.defaultUsername") String defaultUsername,
                                                              @Named("webServer.basicAuth.defaultPassword") String defaultPassword,
                                                              IUserStore userStore) throws IResourceStore.ResourceStoreException {
        IdentityManager identityManager = new MongoLoginService();

        var securityHandler = new BasicSecurityHandler();
        setupSecurityHandler(securityHandler, publicAccessPaths, "admin", "user");

        BasicAuth
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
        mapping.setPathSpec("/");
        securityHandler.addConstraintMapping(mapping);
    }

    private static class BasicSecurityHandler extends ConstraintSecurityHandler {
        //for reflection purpose
    }

    private static class KeycloakSecurityHandler extends ConstraintSecurityHandler {
        //for reflection purpose
    }*/
}