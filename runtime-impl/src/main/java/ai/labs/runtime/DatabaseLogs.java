package ai.labs.runtime;

import ai.labs.models.DatabaseLog;
import ai.labs.models.Deployment.Environment;
import ai.labs.utilities.RuntimeUtilities;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.List;

public class DatabaseLogs implements IDatabaseLogs {
    private static final String COLLECTION_NAME = "logs";
    private static final String CONTEXT_MAP_BOT_ID = "contextMap.botId";
    private static final String CONTEXT_MAP_ENVIRONMENT = "contextMap.environment";
    private static final String CONTEXT_MAP_CONVERSATION_ID = "contextMap.conversationId";
    private static final String CONTEXT_MAP_USER_ID = "contextMap.userId";
    private final MongoCollection<Document> logsCollection;

    @Inject
    public DatabaseLogs(MongoDatabase database) {
        RuntimeUtilities.checkNotNull(database, "database");

        logsCollection = database.getCollection(COLLECTION_NAME);
    }

    @Override
    public List<DatabaseLog> getLogs(Integer skip, Integer limit) {
        return getLogDocuments(new Document(), skip, limit);
    }

    @Override
    public List<DatabaseLog> getLogs(Environment environment, String botId, Integer skip, Integer limit) {
        return getLogDocuments(createFilter(environment, botId, null, null), skip, limit);

    }

    @Override
    public List<DatabaseLog> getLogs(Environment environment, String botId, String conversationId, Integer skip, Integer limit) {
        return getLogDocuments(createFilter(environment, botId, conversationId, null), skip, limit);
    }

    @Override
    public List<DatabaseLog> getLogs(Environment environment, String botId, String conversationId, String userId, Integer skip, Integer limit) {
        return getLogDocuments(createFilter(environment, botId, conversationId, userId), skip, limit);
    }

    private Document createFilter(Environment environment, String botId, String conversationId, String userId) {
        Document filter = new Document();
        filter.put(CONTEXT_MAP_ENVIRONMENT, environment.toString());
        filter.put(CONTEXT_MAP_BOT_ID, botId);
        if (conversationId != null) {
            filter.put(CONTEXT_MAP_CONVERSATION_ID, conversationId);
        }
        if (userId != null) {
            filter.put(CONTEXT_MAP_USER_ID, userId);
        }

        return filter;
    }

    private List<DatabaseLog> getLogDocuments(Document filter, Integer skip, Integer limit) {
        List<DatabaseLog> ret = new ArrayList<>();
        var documents = logsCollection.find(filter).sort(new Document("date", 1));
        if (skip > 0) {
            documents = documents.skip(skip);
        }
        if (limit > 0) {
            documents = documents.limit(limit);
        }
        for (Document document : documents) {
            var databaseLog = new DatabaseLog();
            document.keySet().forEach(key -> databaseLog.put(key, document.get(key)));
            ret.add(databaseLog);
        }

        return ret;
    }
}
