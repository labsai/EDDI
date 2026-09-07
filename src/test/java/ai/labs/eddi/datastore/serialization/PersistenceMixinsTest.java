/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.datastore.serialization;

import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The wire/storage split for {@code callerLevel}.
 *
 * <h3>What breaks if this stops holding</h3> {@code callerLevel} describes the
 * relationship between a resource and <em>whoever asked for it</em>, so a value
 * stamped for Alice is wrong for everybody else. Several paths read a
 * descriptor and write it back. If one of them ever persists a stamped
 * descriptor, the next reader is told they hold whatever the last writer
 * happened to hold — an escalation that no request logs, because the field is
 * only ever read as a hint about which buttons to draw.
 *
 * <p>
 * That is why this is enforced by a mix-in on the persistence mapper rather
 * than by the convention that {@code redactForCaller} must not be called on
 * something about to be written, and why it is tested from both directions.
 */
class PersistenceMixinsTest {

    /**
     * The mapper the application actually stores through — built by the real
     * producer, not reassembled here.
     * <p>
     * An earlier version called {@code PersistenceMixins.register(...)} itself,
     * which tested that the mix-in works and <em>not</em> that anything uses it:
     * deleting the registration from {@link PersistenceMapperProducer} left this
     * suite green. Caught by reverting the fix, which is the only way that kind of
     * gap shows up.
     */
    private static ObjectMapper persistenceMapper() {
        return new PersistenceMapperProducer().persistenceMapper();
    }

    /** The REST-side mapper, built from the same shared recipe without mix-ins. */
    private static ObjectMapper restMapper() {
        return SerializationCustomizer.configureObjectMapper(new ObjectMapper(), false);
    }

    private static DocumentDescriptor stamped() {
        var d = new DocumentDescriptor();
        d.setName("Support Agent");
        d.setOwnerId("alice@example.com");
        d.setSpaceId("user:alice@example.com");
        d.setVisibility("space");
        d.setCallerLevel("OWN");
        return d;
    }

    @Test
    @DisplayName("a stamped descriptor written to storage carries no caller level")
    void notSerialisedToStorage() throws Exception {
        String stored = persistenceMapper().writeValueAsString(stamped());

        assertFalse(stored.contains("callerLevel"), "storage must not record one caller's access level: " + stored);
        // Everything else still goes, or this test would pass by writing nothing.
        assertTrue(stored.contains("ownerId"));
        assertTrue(stored.contains("spaceId"));
        assertTrue(stored.contains("Support Agent"));
    }

    @Test
    @DisplayName("a stored document that somehow has one does not load it")
    void notDeserialisedFromStorage() throws Exception {
        // Belt and braces for a document written before the mix-in existed, or by a
        // future path that bypasses this mapper. Reading it back must not resurrect
        // somebody else's level.
        String legacy = """
                {"name":"Support Agent","ownerId":"alice@example.com","callerLevel":"OWN"}""";

        var loaded = persistenceMapper().readValue(legacy, DocumentDescriptor.class);

        assertNull(loaded.getCallerLevel());
        assertEquals("alice@example.com", loaded.getOwnerId());
    }

    @Test
    @DisplayName("the REST mapper still sends it, or the field would be pointless")
    void stillSerialisedOverRest() throws Exception {
        String wire = restMapper().writeValueAsString(stamped());

        assertTrue(wire.contains("\"callerLevel\":\"OWN\""), wire);
    }

    @Test
    @DisplayName("nothing a client sends can set it")
    void readOnlyOnTheWire() throws Exception {
        // READ_ONLY on the getter. Without it a PATCH body could assert its own
        // access level, and a read-modify-write would then hand it to the store.
        var body = """
                {"name":"Renamed","callerLevel":"OWN"}""";

        var parsed = restMapper().readValue(body, DocumentDescriptor.class);

        assertEquals("Renamed", parsed.getName(), "the rest of the body must still bind");
        assertNull(parsed.getCallerLevel());
    }

    @Test
    @DisplayName("an unstamped descriptor is byte-identical to before the field existed")
    void absentWhenUnstamped() throws Exception {
        // NON_NULL, so a deployment that does not enforce workspaces sends exactly
        // what it sent before this feature — the compatibility property the whole
        // change is built around.
        var d = new DocumentDescriptor();
        d.setName("Support Agent");

        assertFalse(restMapper().writeValueAsString(d).contains("callerLevel"));
    }
}
