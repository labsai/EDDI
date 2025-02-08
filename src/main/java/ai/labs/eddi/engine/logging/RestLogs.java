package ai.labs.eddi.engine.logging;

import ai.labs.eddi.engine.runtime.IDatabaseLogs;
import ai.labs.eddi.engine.model.DatabaseLog;
import ai.labs.eddi.engine.model.Deployment;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
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
                                     Integer skip,
                                     Integer limit) {

        return databaseLogs.getLogs(environment, botId, botVersion, conversationId, userId, skip, limit);
    }
}
