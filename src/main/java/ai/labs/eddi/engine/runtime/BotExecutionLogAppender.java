package ai.labs.eddi.engine.runtime;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Map;

@Singleton
public class BotExecutionLogAppender extends AppenderBase<ILoggingEvent> {
    private final IDatabaseLogs databaseLogs;

    @Inject
    public BotExecutionLogAppender(IDatabaseLogs databaseLogs) {
        this.databaseLogs = databaseLogs;
    }

    @Override
    protected void append(ILoggingEvent loggingEvent) {
        Level level = loggingEvent.getLevel();
        if (level == Level.INFO || level == Level.WARN || level == Level.ERROR) {
            Map<String, String> contextMap = loggingEvent.getMDCPropertyMap();

            if (contextMap.get("botId") != null) {
                String botVersion = contextMap.get("botVersion");
                databaseLogs.addLogs(
                        contextMap.get("environment"),
                        contextMap.get("botId"),
                        botVersion != null ? Integer.parseInt(botVersion) : null,
                        contextMap.get("conversationId"),
                        contextMap.get("userId"),
                        loggingEvent.getMessage() + " (" + loggingEvent.getLoggerName() + ")");
            }
        }
    }
}
