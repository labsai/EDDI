package ai.labs.eddi.engine.runtime;

import ai.labs.eddi.engine.model.DatabaseLog;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.utils.RuntimeUtilities;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import io.reactivex.rxjava3.core.Observable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.bson.Document;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

@ApplicationScoped
public class DatabaseLogs extends Handler implements IDatabaseLogs {
    private static final String COLLECTION_NAME = "logs";
    private static final String CONTEXT_MAP_BOT_ID = "botId";
    private static final String CONTEXT_MAP_BOT_VERSION = "botVersion";
    private static final String CONTEXT_MAP_ENVIRONMENT = "environment";
    private static final String CONTEXT_MAP_CONVERSATION_ID = "conversationId";
    private static final String CONTEXT_MAP_USER_ID = "userId";
    private static final String KEY_MESSAGE = "message";
    public static final String TIMESTAMP = "timestamp";
    private final MongoCollection<Document> logsCollection;

    private static final Logger log = Logger.getLogger(DatabaseLogs.class);

    @Inject
    public DatabaseLogs(MongoDatabase database) {
        RuntimeUtilities.checkNotNull(database, "database");

        logsCollection = database.getCollection(COLLECTION_NAME);
    }

    @Override
    public List<DatabaseLog> getLogs(Integer skip, Integer limit) {
        return getLogs(new Document(), skip, limit);
    }

    @Override
    public List<DatabaseLog> getLogs(Environment environment, String botId, Integer botVersion, String conversationId, String userId, Integer skip, Integer limit) {
        return getLogs(createFilter(environment, botId, botVersion, conversationId, userId), skip, limit);
    }

    @Override
    public void addLogs(String environment, String botId, Integer botVersion, String conversationId, String userId, String message) {
        Document document = new Document();
        document.put(KEY_MESSAGE, message);
        document.put(TIMESTAMP, new Date(System.currentTimeMillis()));
        document.put(CONTEXT_MAP_ENVIRONMENT, environment);
        document.put(CONTEXT_MAP_BOT_ID, botId);
        document.put(CONTEXT_MAP_BOT_VERSION, botVersion);
        if (conversationId != null) {
            document.put(CONTEXT_MAP_CONVERSATION_ID, conversationId);
        }
        if (userId != null) {
            document.put(CONTEXT_MAP_USER_ID, userId);
        }
        Observable.fromPublisher(logsCollection.insertOne(document)).blockingFirst();
    }

    private Document createFilter(Environment environment, String botId, Integer botVersion, String conversationId, String userId) {
        Document filter = new Document();
        filter.put(CONTEXT_MAP_ENVIRONMENT, environment.toString());
        filter.put(CONTEXT_MAP_BOT_ID, botId);

        if (botVersion != null) {
            filter.put(CONTEXT_MAP_BOT_VERSION, botVersion);
        }
        if (conversationId != null) {
            filter.put(CONTEXT_MAP_CONVERSATION_ID, conversationId);
        }
        if (userId != null) {
            filter.put(CONTEXT_MAP_USER_ID, userId);
        }

        return filter;
    }

    private List<DatabaseLog> getLogs(Document filter, Integer skip, Integer limit) {
        List<DatabaseLog> ret = new ArrayList<>();
        Observable<Document> observable = limit > 0 ? Observable.fromPublisher(logsCollection.find(filter).limit(limit).sort(new Document(TIMESTAMP, 1))) :
                                                        Observable.fromPublisher(logsCollection.find(filter).sort(new Document(TIMESTAMP, 1)))  ;
        if (skip > 0) {
            observable = observable.skip(skip);
        }

        Iterable<Document> documents = observable.blockingIterable();

        for (Document document : documents) {
            var databaseLog = new DatabaseLog();
            document.keySet().forEach(key -> databaseLog.put(key, document.get(key)));
            databaseLog.remove("_id");
            ret.add(databaseLog);
        }

        return ret;
    }

    @Override
    public void publish(LogRecord record) {
        // Retrieve the MDC values safely, checking for nulls and handling potential NumberFormatException
        String environment = (String) MDC.get("environment");
        String botId = (String) MDC.get("botId");
        String conversationId = (String) MDC.get("conversationId");
        String userId = (String) MDC.get("userId");
        Integer botVersion = null;

        try {
            String botVersionString = (String) MDC.get("botVersion");
            if (botVersionString != null) {
                botVersion = Integer.parseInt(botVersionString);
            }
        } catch (NumberFormatException e) {
            log.debugv("Failed to parse botVersion from MDC for botId: {0}, error: {1}", botId, e.getMessage());
        }

        if (environment != null && botId != null && botVersion != null) {
            addLogs(environment, botId, botVersion, conversationId, userId, record.getMessage());
        }
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() throws SecurityException {
    }
}
