package ai.labs.eddi.engine.internal;

import ai.labs.eddi.engine.model.Deployment;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.MDC;

import java.util.HashMap;
import java.util.Map;

@ApplicationScoped
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
        setLoggingContextValues(loggingContext);
    }

    @Override
    public void clearLoggingContext() {
        MDC.clear();
    }

    private static void setLoggingContextValues(Map<String, String> loggingContext) {
        if (loggingContext != null) {
            for (Map.Entry<String, String> entry : loggingContext.entrySet()) {
                MDC.put(entry.getKey(), entry.getValue());
            }
        }
    }
}
