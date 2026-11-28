/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security;

import ai.labs.eddi.engine.memory.ConversationMemory;
import ai.labs.eddi.engine.memory.ConversationMemoryUtilities;
import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.security.ResolutionPrincipal.Provenance;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

@DisplayName("TurnOrigin — the email taint")
class TurnOriginTest {

    private final ResolutionPrincipalContext principals = new ResolutionPrincipalContext();

    @AfterEach
    void tearDown() {
        principals.clear();
    }

    @Test
    @DisplayName("read from the bound principal, from an email user id, or from a bound origin")
    void sources() throws Exception {
        assertNull(TurnOrigin.current());
        principals.bind(new ResolutionPrincipal("alice", Provenance.VERIFIED, TurnOrigin.EMAIL));
        assertEquals(TurnOrigin.EMAIL, TurnOrigin.current());
        principals.bind(new ResolutionPrincipal("email:aabbccddee1122334455aabb", Provenance.SELF_ASSERTED));
        assertEquals(TurnOrigin.EMAIL, TurnOrigin.current());
        principals.bind(new ResolutionPrincipal("alice", Provenance.VERIFIED));
        assertNull(TurnOrigin.current());
        assertEquals(TurnOrigin.EMAIL, TurnOrigin.callWith(TurnOrigin.EMAIL, TurnOrigin::current));
        assertNull(TurnOrigin.current());
    }

    @Test
    @DisplayName("context, metadata and header values: only a known origin counts")
    void parsing() {
        assertEquals(TurnOrigin.EMAIL, TurnOrigin.fromContext(Map.of(TurnOrigin.CONTEXT_KEY, new Context(Context.ContextType.string, "Email"))));
        assertNull(TurnOrigin.fromContext(Map.of(TurnOrigin.CONTEXT_KEY, new Context(Context.ContextType.string, "slack"))));
        assertNull(TurnOrigin.fromContext(null));
        assertEquals(TurnOrigin.EMAIL, TurnOrigin.fromMetadata(Map.of(TurnOrigin.CONTEXT_KEY, "email")));
        assertNull(TurnOrigin.normalise(" "));
        assertEquals(TurnOrigin.EMAIL, TurnOrigin.combine(null, TurnOrigin.EMAIL));
        assertNull(TurnOrigin.combine(null, null));
        assertEquals(TurnOrigin.EMAIL,
                TurnOrigin.fromContext(TurnOrigin.withOrigin(Map.of("a", new Context(Context.ContextType.string, "b")), TurnOrigin.EMAIL)));
    }

    @Test
    @DisplayName("the principal's taint follows work to another thread (CallerIdentityContext.propagate)")
    void propagatesToWorkerThreads() throws Exception {
        principals.bind(new ResolutionPrincipal("alice", Provenance.VERIFIED, TurnOrigin.EMAIL));
        Callable<String> work = new CallerIdentityContext(null, null).propagate((Callable<String>) TurnOrigin::current);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            assertEquals(TurnOrigin.EMAIL, executor.submit(work).get());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("stored on the memory, sticky, and round-tripped through the snapshot")
    void persisted() {
        var memory = new ConversationMemory("agent-1", 1, "alice");
        memory.setOrigin(TurnOrigin.EMAIL);
        memory.setOrigin(null);
        assertEquals(TurnOrigin.EMAIL, memory.getOrigin(), "nothing clears the taint");

        var snapshot = ConversationMemoryUtilities.convertConversationMemory(memory);
        assertEquals(TurnOrigin.EMAIL, snapshot.getOrigin());
        assertEquals(TurnOrigin.EMAIL, ConversationMemoryUtilities.convertConversationMemorySnapshot(snapshot).getOrigin());
    }
}
