package ai.labs.core.rest.internal;

import ai.labs.models.Deployment;

import java.util.Map;

public interface IContextLogger {
    Map<String, String> createLoggingContext(Deployment.Environment environment,
                                             String botId,
                                             String conversationId,
                                             String userId);

    void setLoggingContext(Map<String, String> loggingContext);
}
