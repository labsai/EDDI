/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime;

import ai.labs.eddi.engine.gdpr.GdprPseudonymizer;
import ai.labs.eddi.engine.gdpr.UserErasureParticipant;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Takes an erased user out of the in-memory log store
 * ({@link BoundedLogStore}): the ring buffer the log REST endpoint, the live
 * tail and the MCP log tools serve, and the queue of entries waiting for the
 * database writer.
 * <p>
 * It joins the erasure as an in-flight-work participant because that step runs
 * first — before the cascade pseudonymises the persisted logs, so a queued
 * entry is scrubbed before it can land behind that sweep — and because it is
 * the step the cluster stop runs on every other replica, each with its own
 * buffer. No work is signalled, so it reports {@code 0}.
 */
@ApplicationScoped
public class LogBufferErasureParticipant implements UserErasureParticipant {

    private final BoundedLogStore logStore;
    private final GdprPseudonymizer pseudonymizer;

    @Inject
    public LogBufferErasureParticipant(BoundedLogStore logStore, GdprPseudonymizer pseudonymizer) {
        this.logStore = logStore;
        this.pseudonymizer = pseudonymizer;
    }

    @Override
    public String erasureStepName() {
        return "logBuffer";
    }

    /**
     * The same keyed pseudonym the persisted log rows receive, so the buffer and
     * the database name the erased user with one token.
     */
    @Override
    public int stopInFlightWork(String userId) {
        if (userId == null) {
            return 0;
        }
        logStore.markUserErased(userId, pseudonymizer.pseudonymFor(userId));
        return 0;
    }

    @Override
    public int stopInFlightWorkByHash(String userIdHash) {
        logStore.markUserErasedHash(userIdHash);
        return 0;
    }
}
