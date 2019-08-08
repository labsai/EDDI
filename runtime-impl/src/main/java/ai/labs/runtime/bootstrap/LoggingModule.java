package ai.labs.runtime.bootstrap;

import ai.labs.runtime.DatabaseLogs;
import ai.labs.runtime.IDatabaseLogs;
import ai.labs.utilities.RuntimeUtilities;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import com.bugsnag.Bugsnag;
import com.bugsnag.BugsnagAppender;
import com.google.inject.Provides;
import com.google.inject.Scopes;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

public class LoggingModule extends AbstractBaseModule {
    private static final String ENVIRONMENT_KEY = "EDDI_ENV";

    @Override
    protected void configure() {
        bind(ILoggerLoader.class).to(LoggerLoader.class).asEagerSingleton();
        bind(IDatabaseLogs.class).to(DatabaseLogs.class).in(Scopes.SINGLETON);
    }


    interface ILoggerLoader {
    }

    public static class LoggerLoader implements ILoggerLoader {
        @Inject
        public LoggerLoader(Bugsnag ignored) {
        }
    }

    @Singleton
    @Provides
    public LoggerLoader initializeBugsnag(@Named("bugsnagApiKey") String bugsnagApiKey,
                                          @Named("systemRuntime.projectName") String name,
                                          @Named("systemRuntime.projectVersion") String version) {
        Bugsnag bugsnag = null;
        if (!RuntimeUtilities.isNullOrEmpty(bugsnagApiKey)) {
            LoggerContext logCtx = (LoggerContext) LoggerFactory.getILoggerFactory();

            bugsnag = new Bugsnag(bugsnagApiKey);
            bugsnag.setReleaseStage(System.getProperty(ENVIRONMENT_KEY));
            bugsnag.setAppVersion(version);
            BugsnagAppender bugsnagAppender = new BugsnagAppender(bugsnag);
            bugsnagAppender.setContext(logCtx);
            bugsnagAppender.setName(name);
            bugsnagAppender.start();

            Logger log = logCtx.getLogger(Logger.ROOT_LOGGER_NAME);
            log.setAdditive(true);
            log.setLevel(Level.INFO);
            log.addAppender(bugsnagAppender);
            log.info("Bugsnag enabled.");
        }

        return new LoggerLoader(bugsnag);
    }
}
