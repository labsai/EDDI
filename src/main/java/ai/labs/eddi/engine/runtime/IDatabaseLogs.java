package ai.labs.eddi.engine.runtime;

import ai.labs.eddi.engine.model.DatabaseLog;
import ai.labs.eddi.model.Deployment;
import ai.labs.eddi.engine.model.LogEntry;

import java.util.List;

public interface IDatabaseLogs {
    List<DatabaseLog> getLogs(Integer skip, Integer limit);

    List<DatabaseLog> getLogs(Deployment.Environment environment, String botId, Integer botVersion,
                              String conversationId, String userId, String instanceId,
                              Integer skip, Integer limit);

    void addLogs(String environment, String botId, Integer botVersion,
                 String conversationId, String userId, String instanceId, String message);

    /**
     * Batch insert log entries. Used by the async DB writer in {@link BoundedLogStore}.
     */
    void addLogsBatch(List<LogEntry> entries);
}
