package ai.labs.api;

import ai.labs.backupservice.bootstrap.BackupServiceModule;
import ai.labs.behavior.bootstrap.BehaviorModule;
import ai.labs.bootstrap.UserModule;
import ai.labs.caching.bootstrap.CachingModule;
import ai.labs.callback.bootstrap.ConversationCallbackModule;
import ai.labs.channels.config.IChannelDefinitionStore;
import ai.labs.channels.config.IChannelManager;
import ai.labs.channels.config.bootstrap.ChannelModule;
import ai.labs.channels.differ.bootstrap.AMQPModule;
import ai.labs.channels.differ.bootstrap.DifferModule;
import ai.labs.channels.facebookmessenger.bootstrap.FacebookMessengerModule;
import ai.labs.channels.xmpp.bootstrap.XmppModule;
import ai.labs.core.bootstrap.CoreModule;
import ai.labs.expressions.bootstrap.ExpressionModule;
import ai.labs.httpclient.guice.HttpClientModule;
import ai.labs.memory.bootstrap.ConversationMemoryModule;
import ai.labs.migration.IMigrationManager;
import ai.labs.migration.bootstrap.MigrationModule;
import ai.labs.output.bootstrap.OutputGenerationModule;
import ai.labs.parser.bootstrap.SemanticParserModule;
import ai.labs.permission.bootstrap.PermissionModule;
import ai.labs.persistence.bootstrap.PersistenceModule;
import ai.labs.property.bootstrap.PropertySetterModule;
import ai.labs.resources.bootstrap.RepositoryModule;
import ai.labs.rest.bootstrap.RestInterfaceModule;
import ai.labs.restapi.connector.bootstrap.HttpCallsModule;
import ai.labs.runtime.DependencyInjector;
import ai.labs.runtime.DependencyInjector.Environment;
import ai.labs.runtime.IAutoBotDeployment;
import ai.labs.runtime.bootstrap.LoggingModule;
import ai.labs.runtime.bootstrap.RuntimeModule;
import ai.labs.runtime.bootstrap.SwaggerModule;
import ai.labs.serialization.bootstrap.SerializationModule;
import ai.labs.server.IServerRuntime;
import ai.labs.server.bootstrap.ServerRuntimeModule;
import ai.labs.staticresources.bootstrap.StaticResourcesModule;
import ai.labs.templateengine.bootstrap.TemplateEngineModule;
import ai.labs.testing.bootstrap.AutomatedtestingModule;
import ai.labs.utilities.FileUtilities;
import com.google.inject.Module;
import org.jboss.resteasy.plugins.guice.ext.RequestScopeModule;

import java.io.FileInputStream;

/**
 * the central REST API server component
 * run with params: -DEDDI_ENV=(development|production)
 * requires Mongo DB
 *
 * @author ginccc
 */
public class ApiServer {
    private static final String ENVIRONMENT_KEY = "EDDI_ENV";
    private static final String USER_DIR = System.getProperty("user.dir");

    public static void main(String[] args) throws Exception {
        System.out.println("Starting Server...");
        long serverStartupBegin = System.currentTimeMillis();
        String eddiEnv = System.getProperty(ENVIRONMENT_KEY);
        if (eddiEnv == null || eddiEnv.isEmpty()) {
            System.err.println("Environment Variable must not be null nor empty! (e.g. -DEDDI_ENV=[development/production])");
            System.exit(1);
        }

        final String configDir = FileUtilities.buildPath(USER_DIR, "config", eddiEnv);

        //bootstrapping modules
        Module[] modules = {
                new LoggingModule(),
                new RuntimeModule(
                        new FileInputStream(configDir + "threads.properties"),
                        new FileInputStream(configDir + "systemRuntime.properties")),
                new RequestScopeModule(),
                new RestInterfaceModule(),
                new SerializationModule(),
                new PersistenceModule(new FileInputStream(configDir + "mongodb.properties")),
                new ConversationMemoryModule(),
                new PermissionModule(),
                new ExpressionModule(),
                new RepositoryModule(),
                new UserModule(),
                new CachingModule(new FileInputStream(configDir + "infinispan.xml")),
                new SemanticParserModule(),
                new BehaviorModule(),
                new OutputGenerationModule(),
                new TemplateEngineModule(),
                new PropertySetterModule(),
                new AutomatedtestingModule(),
                new StaticResourcesModule(),
                new HttpClientModule(),
                new ConversationCallbackModule(new FileInputStream(configDir + "httpClient.properties")),
                new CoreModule(),
                new SwaggerModule(new FileInputStream(configDir + "swagger.properties")),
                new ServerRuntimeModule(
                        new FileInputStream(configDir + "webServer.properties"),
                        new FileInputStream(configDir + "keycloak.properties")
                ),
                new BackupServiceModule(new FileInputStream(configDir + "git.properties")),
                new HttpCallsModule(),
                new FacebookMessengerModule(),
                new XmppModule(),
                new AMQPModule(),
                new DifferModule(),
                new ChannelModule(),
                new MigrationModule()
        };

        //init modules
        final DependencyInjector injector = DependencyInjector.init(Environment.PRODUCTION, modules);

        //init webserver
        injector.getInstance(IServerRuntime.class).startup(() -> {
            //auto re-deploy bots
            injector.getInstance(IAutoBotDeployment.class).autoDeployBots();

            injector.getInstance(IMigrationManager.class).checkForMigration();

            //load channel definitions
            var channelDefinitions = injector.getInstance(IChannelDefinitionStore.class).readAllChannelDefinitions();
            var channelManager = injector.getInstance(IChannelManager.class);
            channelDefinitions.forEach(channelManager::initChannel);

            logServerStartupTime(serverStartupBegin);
        });
    }

    private static void logServerStartupTime(long serverStartupBegin) {
        long startupDuration = System.currentTimeMillis() - serverStartupBegin;
        String startupTimeMessage = String.format("Server Startup successful. (%dms)", startupDuration);
        System.out.println(startupTimeMessage);
    }
}
