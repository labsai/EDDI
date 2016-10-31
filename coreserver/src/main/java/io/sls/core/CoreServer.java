package io.sls.core;

import io.sls.bootstrap.UserModule;
import io.sls.core.bootstrap.CoreModule;
import io.sls.core.bootstrap.RestInterfaceModule;
import io.sls.expressions.bootstrap.ExpressionModule;
import io.sls.logging.client.bootstrap.ClientLoggingModule;
import io.sls.memory.bootstrap.ConversationMemoryModule;
import io.sls.permission.bootstrap.PermissionModule;
import io.sls.persistence.bootstrap.RepositoryModule;
import io.sls.persistence.impl.bootstrap.PersistenceModule;
import io.sls.runtime.DependencyInjector;
import io.sls.runtime.bootstrap.RuntimeModule;
import io.sls.serialization.bootstrap.SerializationModule;
import io.sls.server.IServerRuntime;
import io.sls.server.bootstrap.ServerRuntimeModule;
import io.sls.staticresources.bootstrap.StaticResourcesModule;
import io.sls.utilities.FileUtilities;
import org.jboss.resteasy.plugins.guice.ext.RequestScopeModule;

import java.io.FileInputStream;

/**
 * @author ginccc
 */
public class CoreServer {
    private static final String ENVIRONMENT_KEY = "EDDI_ENV";
    private static final String USER_DIR = System.getProperty("user.dir");

    public static void main(String[] args) throws Exception {
        String eddiEnv = System.getProperty(ENVIRONMENT_KEY);
        if (eddiEnv == null || eddiEnv.isEmpty()) {
            System.err.println("Environment Variable must not be null nor empty! (e.g. -DEDDI_ENV=[development/production])");
            System.exit(1);
        }

        final String configDir = FileUtilities.buildPath(USER_DIR, "config", eddiEnv);

        //bootstrapping modules
        DependencyInjector.Environment environment = DependencyInjector.Environment.valueOf(eddiEnv.toUpperCase());
        //TODO check why production mode does not work
        final DependencyInjector injector = DependencyInjector.init(DependencyInjector.Environment.DEVELOPMENT,
                new RuntimeModule(
                        new FileInputStream(configDir + "threads.properties"),
                        new FileInputStream(configDir + "systemRuntime.properties")),
                new RequestScopeModule(),
                new RestInterfaceModule(),
                new SerializationModule(),
                new PersistenceModule(new FileInputStream(configDir + "mongodb.properties")),
                new ConversationMemoryModule(),
                new PermissionModule(),
                new StaticResourcesModule(),
                new ExpressionModule(),
                new RepositoryModule(),
                new ConversationMemoryModule(),
                new ClientLoggingModule(),
                new UserModule(),
                new CoreModule(new FileInputStream(configDir + "coreEngine.properties")),
                new ServerRuntimeModule(new FileInputStream(configDir + "webServer.properties"),
                        new FileInputStream(configDir + "keycloak.properties")));

        //init webserver
        injector.getInstance(IServerRuntime.class).startup();
    }
}
