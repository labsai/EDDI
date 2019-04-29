package ai.labs.runtime;

import ai.labs.models.DatabaseLog;
import ai.labs.models.Deployment.Environment;

import java.util.List;

public interface IDatabaseLogs {
    List<DatabaseLog> getLogs(Integer skip, Integer limit);

    List<DatabaseLog> getLogs(Environment environment, String botId, Integer skip, Integer limit);

    List<DatabaseLog> getLogs(Environment environment, String botId, String conversationId, Integer skip, Integer limit);

    List<DatabaseLog> getLogs(Environment environment, String botId, String conversationId, String userId, Integer skip, Integer limit);
}
