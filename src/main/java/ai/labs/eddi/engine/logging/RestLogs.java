package ai.labs.eddi.engine.logging;

import ai.labs.eddi.engine.runtime.IDatabaseLogs;
import ai.labs.eddi.engine.model.DatabaseLog;
import ai.labs.eddi.engine.model.Deployment;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

@ApplicationScoped
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
    public List<DatabaseLog> getLogs(Deployment.Environment environment,
                                     String botId,
                                     Integer botVersion,
                                     String conversationId,
                                     String userId,
                                     String instanceId,
                                     Integer skip,
                                     Integer limit) {

        return databaseLogs.getLogs(environment, botId, botVersion, conversationId, userId, instanceId, skip, limit);
    }
}
