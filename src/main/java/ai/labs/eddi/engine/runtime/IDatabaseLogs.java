package ai.labs.eddi.engine.runtime;

import ai.labs.eddi.engine.model.Deployment;
import ai.labs.eddi.engine.model.LogEntry;

import java.util.List;

public interface IDatabaseLogs {

    List<LogEntry> getLogs(Deployment.Environment environment, String agentId, Integer agentVersion, String conversationId, String userId,
                           String instanceId, Integer skip, Integer limit);

    /**
     * Batch insert log entries. Used by the async DB writer in
     * {@link BoundedLogStore}.
     */
    void addLogsBatch(List<LogEntry> entries);
}
