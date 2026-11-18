/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import ai.labs.eddi.engine.memory.ConcurrentConversationModificationException;
import ai.labs.eddi.engine.memory.ConversationFencedException;
import ai.labs.eddi.engine.memory.ConversationTooLargeException;
import ai.labs.eddi.engine.model.TurnError;
import com.mongodb.MongoException;
import org.bson.BsonMaximumSizeExceededException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static ai.labs.eddi.engine.internal.TurnNotStored.Reason.CONFLICT;
import static ai.labs.eddi.engine.internal.TurnNotStored.Reason.CONVERSATION_TOO_LARGE;
import static ai.labs.eddi.engine.internal.TurnNotStored.Reason.DATA_REJECTED;
import static ai.labs.eddi.engine.internal.TurnNotStored.Reason.FENCED;
import static ai.labs.eddi.engine.internal.TurnNotStored.Reason.STORE_FAILURE;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnNotStoredTest {

    @Test
    @DisplayName("MongoDB's document-too-large refusals and the size guard are conversation_too_large")
    void documentTooLarge() {
        assertEquals(CONVERSATION_TOO_LARGE, TurnNotStored.classify(new MongoException(10334, "BSONObjectTooLarge")));
        assertEquals(CONVERSATION_TOO_LARGE, TurnNotStored.classify(new MongoException(17419, "Resulting document after update is larger")));
        assertEquals(CONVERSATION_TOO_LARGE, TurnNotStored.classify(new BsonMaximumSizeExceededException("Payload document size is larger")));
        assertEquals(CONVERSATION_TOO_LARGE, TurnNotStored.classify(new ConversationTooLargeException("c", 20, 10)));
    }

    @Test
    @DisplayName("a PostgreSQL data exception wrapped by the store is data_rejected; a program limit is too large")
    void postgresDataErrorsThroughTheCauseChain() {
        var nul = new ResourceStoreException("Failed to store", new SQLException("unsupported Unicode escape sequence", "22P05"));
        assertEquals(DATA_REJECTED, TurnNotStored.classify(nul));
        var tooBig = new RuntimeException(new ResourceStoreException("x", new SQLException("row too big", "54000")));
        assertEquals(CONVERSATION_TOO_LARGE, TurnNotStored.classify(tooBig));
        assertEquals(STORE_FAILURE, TurnNotStored.classify(new ResourceStoreException("x", new SQLException("down", "08006"))));
    }

    @Test
    @DisplayName("fence and revision conflicts keep their own reasons; anything else is store_failure")
    void otherReasons() {
        assertEquals(FENCED, TurnNotStored.classify(new ConversationFencedException("c", 4L, 9L)));
        assertEquals(CONFLICT, TurnNotStored.classify(new ConcurrentConversationModificationException("c", 3L)));
        assertEquals(STORE_FAILURE, TurnNotStored.classify(new IllegalStateException("boom")));
        assertEquals(STORE_FAILURE, TurnNotStored.classify(new MongoException(11000, "duplicate key")));
        assertEquals(STORE_FAILURE, TurnNotStored.classify(null));
    }

    @Test
    @DisplayName("the caller-facing error has the stable code and reason, and a fixed message")
    void errorShape() {
        TurnError error = DATA_REJECTED.error();
        assertEquals(TurnNotStored.ERROR_CODE, error.code());
        assertEquals("data_rejected", error.errorType());
        assertFalse(error.retryable());
        assertTrue(TurnNotStored.isNotStored(error));
        assertEquals(DATA_REJECTED, TurnNotStored.reasonOf(error));
        assertFalse(TurnNotStored.isNotStored(new TurnError(TurnError.TURN_FAILED, false, null, "x")));
        assertEquals(STORE_FAILURE, TurnNotStored.reasonOf(new TurnError(TurnNotStored.ERROR_CODE, true, null, "x", null, null, "future")));
    }
}
