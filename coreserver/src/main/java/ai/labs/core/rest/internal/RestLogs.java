package ai.labs.core.rest.internal;

import ai.labs.models.DatabaseLog;
import ai.labs.rest.restinterfaces.IRestLogs;
import ai.labs.runtime.IDatabaseLogs;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.util.List;

import static ai.labs.models.Deployment.Environment;
import static ai.labs.utilities.RuntimeUtilities.checkNotNull;

@Slf4j
public class RestLogs implements IRestLogs {
    private final IDatabaseLogs databaseLogs;

    @Inject
    public RestLogs(IDatabaseLogs databaseLogs) {
        this.databaseLogs = databaseLogs;
    }

    @Override
    public void setLogLevel(String packageName, String logLevel) {
        checkNotNull(packageName, "packageName");
        checkNotNull(logLevel, "logLevel");

        LoggerContext loggerContext = (LoggerContext) LoggerFactory.getILoggerFactory();

        Logger logger = loggerContext.getLogger(packageName);
        log.info("Changing LogLevel for packageName: " + packageName + " with current logger level: " + logger.getLevel());
        logger.setLevel(Level.toLevel(logLevel));
        log.info("Changed LogLevel for packageName: " + packageName + " to logger level: " + logger.getLevel());
    }

    @Override
    public List<DatabaseLog> getLogs(Integer skip, Integer limit) {
        return databaseLogs.getLogs(skip, limit);
    }

    @Override
    public List<DatabaseLog> getLogs(Environment environment, String botId, Integer skip, Integer limit) {
        return databaseLogs.getLogs(environment, botId, skip, limit);
    }

    @Override
    public List<DatabaseLog> getLogs(Environment environment, String botId, String conversationId, Integer skip, Integer limit) {
        return databaseLogs.getLogs(environment, botId, conversationId, skip, limit);
    }

    @Override
    public List<DatabaseLog> getLogs(Environment environment, String botId, String conversationId, String userId, Integer skip, Integer limit) {
        return databaseLogs.getLogs(environment, botId, conversationId, userId, skip, limit);
    }
}
