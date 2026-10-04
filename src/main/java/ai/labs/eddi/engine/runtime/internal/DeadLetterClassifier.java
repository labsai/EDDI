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

    static Classification classify(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof ConversationFencedException fenced) {
                Map<String, Object> fence = new LinkedHashMap<>();
                fence.put("token", fenced.getToken());
                fence.put("storedFence", fenced.getStoredFence());
                return new Classification(DeadLetterEntry.REASON_FENCED, fence);
            }
            if (t instanceof TimeoutException || t.getClass().getSimpleName().contains("Timeout")) {
                return new Classification(DeadLetterEntry.REASON_TIMEOUT, null);
            }
        }
        return new Classification(DeadLetterEntry.REASON_FAILED, null);
    }
}
