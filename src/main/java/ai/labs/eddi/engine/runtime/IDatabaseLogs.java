package ai.labs.eddi.engine.runtime;

import ai.labs.eddi.engine.model.DatabaseLog;
import ai.labs.eddi.engine.model.Deployment;

import java.util.List;

public interface IDatabaseLogs {
    List<DatabaseLog> getLogs(Integer skip, Integer limit);

    List<DatabaseLog> getLogs(Deployment.Environment environment, String botId, Integer botVersion, String conversationId, String userId, Integer skip, Integer limit);

    void addLogs(String environment, String botId, Integer botVersion, String conversationId, String userId, String message);
}
