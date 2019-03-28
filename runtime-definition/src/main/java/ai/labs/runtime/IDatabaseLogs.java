package ai.labs.runtime;

import ai.labs.models.DatabaseLog;
import ai.labs.models.Deployment;

import java.util.List;

public interface IDatabaseLogs {
    List<DatabaseLog> getLogs(Deployment.Environment environment, String botId);

    List<DatabaseLog> getLogs(Deployment.Environment environment, String botId, String conversationId);

    List<DatabaseLog> getLogs(Deployment.Environment environment, String botId, String conversationId, String userId);
}
