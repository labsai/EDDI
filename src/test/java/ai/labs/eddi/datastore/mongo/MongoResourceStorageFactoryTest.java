package ai.labs.eddi.datastore.mongo;

import ai.labs.eddi.datastore.IResourceStorageFactory;
import com.mongodb.reactivestreams.client.MongoCollection;
import com.mongodb.reactivestreams.client.MongoDatabase;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests that MongoResourceStorageFactory correctly creates storage instances
 * and exposes the database.
 */
class MongoResourceStorageFactoryTest {

    @Test
    void shouldExposeDatabase() {
        MongoDatabase database = mock(MongoDatabase.class);
        MongoResourceStorageFactory factory = new MongoResourceStorageFactory(database);

        assertSame(database, factory.getDatabase());
    }

    @Test
    void shouldImplementFactoryInterface() {
        MongoDatabase database = mock(MongoDatabase.class);
        MongoResourceStorageFactory factory = new MongoResourceStorageFactory(database);

        assertInstanceOf(IResourceStorageFactory.class, factory);
    }
}
