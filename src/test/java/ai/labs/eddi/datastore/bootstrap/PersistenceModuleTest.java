/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.bootstrap;

import com.mongodb.client.MongoDatabase;
import jakarta.annotation.PreDestroy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The MongoDB client must outlive the graceful-shutdown drain. It used to be
 * closed by a JVM shutdown hook, which runs concurrently with Quarkus' own
 * shutdown — so SIGTERM closed it the moment the drain started, and a rolling
 * update failed every turn still in flight.
 */
@DisplayName("PersistenceModule client lifecycle")
class PersistenceModuleTest {

    @Test
    @DisplayName("the client is closed by the bean's @PreDestroy, which runs after the ShutdownEvent drain")
    void clientClosesWithTheContainer() throws Exception {
        Method close = PersistenceModule.class.getDeclaredMethod("closeMongoClient");
        assertNotNull(close.getAnnotation(PreDestroy.class),
                "closing must be tied to the CDI container's shutdown, not to a JVM shutdown hook");

        PersistenceModule module = new PersistenceModule();
        // Nothing listens on port 1; creating the client does not connect.
        MongoDatabase database = module.provideMongoDB("mongodb://127.0.0.1:1/?serverSelectionTimeoutMS=200", "eddi");

        module.closeMongoClient();

        IllegalStateException closed = assertThrows(IllegalStateException.class, () -> database.listCollectionNames().first(),
                "the produced database must stop working once the bean is destroyed");
        assertTrue(closed.getMessage().contains("open"), closed.getMessage());
        assertDoesNotThrow(module::closeMongoClient, "closing twice must be harmless");
    }

    @Test
    @DisplayName("destroying a module that never produced a client does nothing")
    void closeWithoutClient() {
        assertDoesNotThrow(() -> new PersistenceModule().closeMongoClient());
    }
}
