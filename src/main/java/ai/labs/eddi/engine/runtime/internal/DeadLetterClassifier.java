/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime.internal;

import ai.labs.eddi.engine.memory.ConversationFencedException;
import ai.labs.eddi.engine.model.DeadLetterEntry;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * Why a turn was dead-lettered, read from the failure: a write the cluster
 * fence refused, a timeout, or anything else. The admin console filters and
 * explains dead letters by this reason, and a fenced entry also records the two
 * fencing tokens involved.
 */
final class DeadLetterClassifier {

    /** The reason and, for a fenced write, its tokens. */
    record Classification(String reason, Map<String, Object> fence) {
    }

    private DeadLetterClassifier() {
    }

    /**
     * Classifies with what the turn itself recorded first: the step runner puts
     * {@code reason} ({@code lease-lost} or {@code fenced}) and the tokens involved
     * ({@code fence}, {@code storedFence}) into the turn descriptor when it stops a
     * turn. The failure's type decides only when the turn says nothing.
     */
    static Classification classify(Throwable failure, Map<String, Object> turn) {
        Object recorded = turn == null ? null : turn.get("reason");
        if (recorded != null && !recorded.toString().isBlank()) {
            Map<String, Object> fence = new LinkedHashMap<>();
            if (turn.get("fence") != null) {
                fence.put("token", turn.get("fence"));
            }
            if (turn.get("storedFence") != null) {
                fence.put("storedFence", turn.get("storedFence"));
            }
            return new Classification(recorded.toString(), fence.isEmpty() ? null : fence);
        }
        return classify(failure);
    }

    static Classification classify(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof ConversationFencedException fenced) {
                Map<String, Object> fence = new LinkedHashMap<>();
                fence.put("token", fenced.getToken());
                fence.put("storedFence", fenced.getStoredFence());
                return new Classification(DeadLetterEntry.REASON_FENCED, fence);
            }
            if ("TurnLeaseLostException".equals(t.getClass().getSimpleName())) {
                // a turn without a descriptor (HITL resume, group member) still says why
                return new Classification(DeadLetterEntry.REASON_LEASE_LOST, null);
            }
            if (t instanceof TimeoutException || t.getClass().getSimpleName().contains("Timeout")) {
                return new Classification(DeadLetterEntry.REASON_TIMEOUT, null);
            }
        }
        return new Classification(DeadLetterEntry.REASON_FAILED, null);
    }
}
