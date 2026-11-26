/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.gdpr.erasure.mongo;

import com.mongodb.ReadConcern;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ErasureTombstoneStore — the read concern of the guard's point reads")
class ErasureTombstoneStorePointReadTest {

    private static ReadConcern concernFor(Document hello) {
        MongoDatabase database = mock(MongoDatabase.class);
        when(database.runCommand(any(Document.class))).thenReturn(hello);
        return ErasureTombstoneStore.pointReadConcern(database);
    }

    @Test
    @DisplayName("a replica set member: linearizable, so a replaced primary cannot answer from before a tombstone")
    void replicaSet() {
        assertEquals(ReadConcern.LINEARIZABLE, concernFor(new Document("isWritablePrimary", true).append("setName", "rs0")));
    }

    @Test
    @DisplayName("a mongos: linearizable")
    void shardedCluster() {
        assertEquals(ReadConcern.LINEARIZABLE, concernFor(new Document("isWritablePrimary", true).append("msg", "isdbgrid")));
    }

    @Test
    @DisplayName("a standalone server, which refuses linearizable reads: majority")
    void standalone() {
        assertEquals(ReadConcern.MAJORITY, concernFor(new Document("isWritablePrimary", true)));
    }
}
