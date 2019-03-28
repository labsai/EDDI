package ai.labs.api;

import ai.labs.backupservice.bootstrap.BackupServiceModule;
import ai.labs.behavior.bootstrap.BehaviorModule;
import ai.labs.bootstrap.UserModule;
import ai.labs.caching.bootstrap.CachingModule;
import ai.labs.callback.bootstrap.ConversationCallbackModule;
import ai.labs.core.bootstrap.CoreModule;
import ai.labs.expressions.bootstrap.ExpressionModule;
import ai.labs.facebookmessenger.bootstrap.FacebookMessengerModule;
import ai.labs.httpclient.guice.HttpClientModule;
import ai.labs.memory.bootstrap.ConversationMemoryModule;
import ai.labs.normalizer.bootstrap.NormalizerModule;
import ai.labs.output.bootstrap.OutputGenerationModule;
import ai.labs.parser.bootstrap.SemanticParserModule;
import ai.labs.permission.bootstrap.PermissionModule;
import ai.labs.persistence.bootstrap.PersistenceModule;
import ai.labs.property.bootstrap.PropertyDisposerModule;
import ai.labs.resources.bootstrap.RepositoryModule;
import ai.labs.rest.bootstrap.RestInterfaceModule;
import ai.labs.restapi.connector.bootstrap.HttpCallsModule;
import ai.labs.runtime.DependencyInjector;
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
import ai.labs.xmpp.bootstrap.XmppModule;
import ai.labs.xmpp.endpoint.IXmppEndpoint;
import com.bugsnag.Bugsnag;
import com.google.inject.Module;
import org.jboss.resteasy.plugins.guice.ext.RequestScopeModule;

import java.io.FileInputStream;

/**
 * the central REST API server component
 * run with params: -DEDDI_ENV=(development|production) -Xbootclasspath/p:[ABS_PATH_TO]\alpn-boot-8.1.11.v20170118.jar
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
        DependencyInjector.Environment environment = DependencyInjector.Environment.valueOf(eddiEnv.toUpperCase());
        Module[] modules = {
                new RuntimeModule(
                        new FileInputStream(configDir + "threads.properties"),
                        new FileInputStream(configDir + "systemRuntime.properties")),
                new LoggingModule(),
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
                new NormalizerModule(),
                new SemanticParserModule(),
                new BehaviorModule(),
                new OutputGenerationModule(),
                new TemplateEngineModule(),
                new PropertyDisposerModule(),
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
                new FacebookMessengerModule(),
                new BackupServiceModule(),
                new HttpCallsModule(),
                new XmppModule()
        };

        //init modules
        final DependencyInjector injector = DependencyInjector.init(environment, modules);

        injector.getInstance(Bugsnag.class);

        //init webserver
        injector.getInstance(IServerRuntime.class).startup(() -> {
            //auto re-deploy bots
            injector.getInstance(IAutoBotDeployment.class).autoDeployBots();
            injector.getInstance(IXmppEndpoint.class).init();

            logServerStartupTime(serverStartupBegin);
        });
    }

    private static void logServerStartupTime(long serverStartupBegin) {
        long startupDuration = System.currentTimeMillis() - serverStartupBegin;
        String startupTimeMessage = String.format("Server Startup successful. (%dms)", startupDuration);
        System.out.println(startupTimeMessage);
    }
}
