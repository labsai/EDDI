package ai.labs.server.bootstrap;

import ai.labs.runtime.SwaggerServletContextListener;
import ai.labs.runtime.bootstrap.AbstractBaseModule;
import ai.labs.server.IServerRuntime;
import ai.labs.server.MongoLoginService;
import ai.labs.server.ServerRuntime;
import ai.labs.utilities.StringUtilities;
import com.google.inject.Provides;
import org.eclipse.jetty.security.LoginService;
import org.jboss.resteasy.plugins.guice.GuiceResteasyBootstrapServletContextListener;
import org.jboss.resteasy.plugins.server.servlet.HttpServletDispatcher;

import javax.inject.Named;
import javax.inject.Singleton;
import java.io.File;
import java.io.InputStream;

/**
 * @author ginccc
 */
public class ServerRuntimeModule extends AbstractBaseModule {
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
                                               GuiceResteasyBootstrapServletContextListener contextListener,
                                               SwaggerServletContextListener swaggerContextListener,
                                               HttpServletDispatcher httpServletDispatcher,
                                               LoginService mongoLoginService) throws ClassNotFoundException {

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

        return new ServerRuntime(options, contextListener, swaggerContextListener, httpServletDispatcher,
                null, environment, resourceDir);
    }
}
