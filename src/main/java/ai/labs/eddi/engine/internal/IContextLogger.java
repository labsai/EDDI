package ai.labs.eddi.engine.internal;

import ai.labs.eddi.engine.model.Deployment;

import java.util.Map;

public interface IContextLogger {
    Map<String, String> createLoggingContext(Deployment.Environment environment,
                                             String botId,
                                             String conversationId,
                                             String userId);

    void setLoggingContext(Map<String, String> loggingContext);

    void clearLoggingContext();
}
