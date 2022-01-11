package ai.labs.eddi.engine.runtime;

import ai.labs.eddi.models.DatabaseLog;
import ai.labs.eddi.models.Deployment.Environment;
import ai.labs.eddi.utils.RuntimeUtilities;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

@Singleton
public class DatabaseLogs implements IDatabaseLogs {
    private static final String COLLECTION_NAME = "logs";
    private static final String CONTEXT_MAP_BOT_ID = "botId";
    private static final String CONTEXT_MAP_BOT_VERSION = "botVersion";
    private static final String CONTEXT_MAP_ENVIRONMENT = "environment";
    private static final String CONTEXT_MAP_CONVERSATION_ID = "conversationId";
    private static final String CONTEXT_MAP_USER_ID = "userId";
    private static final String KEY_MESSAGE = "message";
    public static final String TIMESTAMP = "timestamp";
    private final MongoCollection<Document> logsCollection;

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
    public List<DatabaseLog> getLogs(Environment environment, String botId, Integer botVersion, Integer skip, Integer limit) {
        return getLogs(createFilter(environment, botId, botVersion, null, null), skip, limit);

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
        logsCollection.insertOne(document);
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
        var documents = logsCollection.find(filter).sort(new Document(TIMESTAMP, 1));
        if (skip > 0) {
            documents = documents.skip(skip);
        }
        if (limit > 0) {
            documents = documents.limit(limit);
        }
        for (Document document : documents) {
            var databaseLog = new DatabaseLog();
            document.keySet().forEach(key -> databaseLog.put(key, document.get(key)));
            databaseLog.remove("_id");
            ret.add(databaseLog);
        }

        return ret;
    }
}
