package ai.labs.runtime;

import ai.labs.models.DatabaseLog;
import ai.labs.models.Deployment.Environment;

import java.util.List;

public interface IDatabaseLogs {
    List<DatabaseLog> getLogs(Integer skip, Integer limit);

    List<DatabaseLog> getLogs(Environment environment, String botId, Integer botVersion, Integer skip, Integer limit);

    List<DatabaseLog> getLogs(Environment environment, String botId, Integer botVersion, String conversationId, String userId, Integer skip, Integer limit);

    void addLogs(String environment, String botId, Integer botVersion, String conversationId, String userId, String message);
}
