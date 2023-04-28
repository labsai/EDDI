package ai.labs.eddi.testing.internal;

import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.testing.ITestCaseStore;
import ai.labs.eddi.testing.model.TestCase;
import ai.labs.eddi.testing.model.TestCaseState;
import com.mongodb.BasicDBObject;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import io.reactivex.rxjava3.core.Observable;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.util.Date;
import java.util.NoSuchElementException;

/**
 * @author ginccc
 */
@ApplicationScoped
public class TestCaseStore implements ITestCaseStore {
    private static final String TESTCASE_COLLECTION = "testcases";
    private static final String TESTCASE_STATE_FIELD = "testCaseState";
    private final MongoCollection<Document> testcaseCollection;
    private final IJsonSerialization jsonSerialization;

    private static final Logger log = Logger.getLogger(TestCaseStore.class);

    @Inject
    public TestCaseStore(MongoDatabase database, IJsonSerialization jsonSerialization) {
        this.testcaseCollection = database.getCollection(TESTCASE_COLLECTION);
        this.jsonSerialization = jsonSerialization;
    }

    @Override
    public String storeTestCase(String id, TestCase testCase) throws IResourceStore.ResourceStoreException {
        try {
            testCase.setLastRun(new Date(System.currentTimeMillis()));

            String json = jsonSerialization.serialize(testCase);
            Document document = Document.parse(json);

            if (id != null) {
                document.put("_id", new ObjectId(id));
            }

            Observable.fromPublisher(testcaseCollection.insertOne(document)).blockingFirst();

            return document.get("_id").toString();
        } catch (IOException e) {
            log.debug(e.getLocalizedMessage(), e);
            throw new IResourceStore.ResourceStoreException(e.getLocalizedMessage(), e);
        }
    }

    @Override
    public TestCase loadTestCase(String id) throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {
        try {
            Document document = Observable.fromPublisher(testcaseCollection.find(new Document("_id", new ObjectId(id))).first()).blockingFirst();
            document.remove("_id");

            return jsonSerialization.deserialize(document.toString(), TestCase.class);
        } catch (NoSuchElementException ne) {
            String message = "Could not find TestCase (id=%s)";
            message = String.format(message, id);
            throw new IResourceStore.ResourceNotFoundException(message);
        } catch (IOException ioe) {
            log.debug(ioe.getLocalizedMessage(), ioe);
            throw new IResourceStore.ResourceStoreException(ioe.getLocalizedMessage(), ioe);
        }
    }

    public void setTestCaseState(String id, TestCaseState testCaseState) {
        BasicDBObject updateTestCaseField = new BasicDBObject("$set", new BasicDBObject(TESTCASE_STATE_FIELD, testCaseState.name()));
        testcaseCollection.updateOne(new BasicDBObject("_id", new ObjectId(id)), updateTestCaseField);
    }

    public void deleteTestCase(String id) {
        testcaseCollection.deleteOne(new BasicDBObject("_id", new ObjectId(id)));
    }

    public TestCaseState getTestCaseState(String id) {
        try {
            Document conversationMemoryDocument = Observable.fromPublisher(testcaseCollection.find(new Document("_id", new ObjectId(id))).first()).blockingFirst();
            return TestCaseState.valueOf(conversationMemoryDocument.get(TESTCASE_STATE_FIELD).toString());
        } catch (NoSuchElementException ne) {
            return null;
        }
    }

    @Override
    public TestCase readIncludingDeleted(String id, Integer version) throws ResourceNotFoundException, ResourceStoreException {
        return loadTestCase(id);
    }

    @Override
    public IResourceId create(TestCase content) throws ResourceStoreException {
        final String id = storeTestCase(null, content);

        return new IResourceId() {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public Integer getVersion() {
                return 0;
            }
        };
    }

    @Override
    public TestCase read(String id, Integer version) throws ResourceNotFoundException, ResourceStoreException {
        return loadTestCase(id);
    }

    @Override
    public Integer update(String id, Integer version, TestCase testCase) throws ResourceStoreException {
        storeTestCase(id, testCase);
        return 0;
    }

    @Override
    public void delete(String id, Integer version) {
        //todo implement
    }

    @Override
    public void deleteAllPermanently(String id) {
        //todo implement
    }

    @Override
    public IResourceId getCurrentResourceId(final String id) {
        return new IResourceId() {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public Integer getVersion() {
                return 0;
            }
        };
    }
}
