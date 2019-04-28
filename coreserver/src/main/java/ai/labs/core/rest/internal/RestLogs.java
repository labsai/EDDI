package ai.labs.core.rest.internal;

import ai.labs.models.DatabaseLog;
import ai.labs.rest.rest.IRestLogs;
import ai.labs.runtime.IDatabaseLogs;

import javax.inject.Inject;
import java.util.List;

import static ai.labs.models.Deployment.Environment;

public class RestLogs implements IRestLogs {
    private final IDatabaseLogs databaseLogs;

    @Inject
    public RestLogs(IDatabaseLogs databaseLogs) {
        this.databaseLogs = databaseLogs;
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
