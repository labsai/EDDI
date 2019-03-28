package ai.labs.core.rest.internal;

import ai.labs.models.Deployment;
import org.slf4j.MDC;

import java.util.HashMap;
import java.util.Map;

public class ContextLogger implements IContextLogger {

    @Override
    public Map<String, String> createLoggingContext(Deployment.Environment environment,
                                                    String botId,
                                                    String conversationId,
                                                    String userId) {


        Map<String, String> context = new HashMap<>();

        context.put("environment", environment.toString());
        context.put("botId", botId);
        if (conversationId != null) {
            context.put("conversationId", conversationId);
        }
        if (userId != null) {
            context.put("userId", userId);
        }

        return context;
    }

    @Override
    public void setLoggingContext(Map<String, String> loggingContext) {
        MDC.setContextMap(loggingContext);
    }
}
