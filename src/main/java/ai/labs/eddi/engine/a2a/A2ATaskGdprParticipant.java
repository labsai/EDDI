/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.a2a;

import ai.labs.eddi.engine.a2a.A2AModels.A2ATaskRecord;
import ai.labs.eddi.engine.gdpr.IGdprParticipant;
import ai.labs.eddi.engine.gdpr.UserErasureParticipant;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/**
 * Joins GDPR erasure and export for the A2A task store.
 * <p>
 * A peer's A2A tasks are keyed by its principal and each one keeps the message
 * the peer sent and the reply it got — a copy of conversation content that the
 * conversation-store sweeps never reach. Before this participant existed an
 * erasure deleted the conversation and left that copy in the task cache until
 * it expired (M10 of the 2026-10-15 security review).
 * <p>
 * Registering it as a bean is what enrols it: {@code GdprComplianceService}
 * discovers every {@link IGdprParticipant} and runs it after the named stores.
 * It is a {@link UserErasureParticipant} too: in the cascade's stop phase,
 * before anything is deleted, it closes the store to the principal's writes, so
 * a peer request admitted while the cascade runs cannot leave a task behind.
 */
@ApplicationScoped
public class A2ATaskGdprParticipant implements IGdprParticipant, UserErasureParticipant {

    private final IA2ATaskStore taskStore;

    @Inject
    public A2ATaskGdprParticipant(IA2ATaskStore taskStore) {
        this.taskStore = taskStore;
    }

    @Override
    public String name() {
        return "a2aTasks";
    }

    @Override
    public String erasureStepName() {
        return name();
    }

    @Override
    public int stopInFlightWork(String userId) {
        taskStore.beginErasure(userId);
        return 0;
    }

    @Override
    public long erase(String userId) {
        return taskStore.eraseForPrincipal(userId);
    }

    @Override
    public Object export(String userId) {
        List<A2ATaskRecord> held = taskStore.tasksOf(userId);
        return held.isEmpty() ? null : held;
    }
}
