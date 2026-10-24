/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.apicalls.impl;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What one item of an httpcall's {@code forEach} produced. Every value in here
 * has already been redacted and capped — this is what reaches conversation
 * memory.
 *
 * @param index
 *            the element's position in the source array (before
 *            {@code templateFilterExpression} skipped any)
 * @param httpCode
 *            the response code of the last attempt, or null when no response
 *            arrived
 * @param body
 *            the stored body: the parsed JSON or text of a 2xx, the redacted
 *            error text of any other code; null when the call does not save its
 *            response, when none arrived, or when the total budget was spent
 * @param headers
 *            the response headers when the call names a
 *            {@code responseHeaderObjectName}, otherwise null
 * @param error
 *            an EDDI-written reason the item failed, or null when it succeeded
 * @param timedOut
 *            the request timed out — it may still have been processed (and
 *            billed), which is why it is never retried
 * @param sent
 *            whether any request of this item left
 * @param attempts
 *            how many requests the item sent
 * @param bodyDropped
 *            the body was dropped because the items before it had used up
 *            {@code maxTotalResponseSizeInBytes}
 */
public record ForEachItemResult(int index, Integer httpCode, Object body, Map<String, String> headers, String error, boolean timedOut,
        boolean sent, int attempts, boolean bodyDropped) {

    /** Whether the item succeeded: a 2xx and no error. */
    public boolean ok() {
        return error == null && httpCode != null && httpCode >= 200 && httpCode < 300;
    }

    /** An item that never sent anything. */
    static ForEachItemResult notSent(int index, String reason) {
        return new ForEachItemResult(index, null, null, null, reason, false, false, 0, false);
    }

    /** This result without its body, marked as dropped by the total budget. */
    ForEachItemResult withoutBody(String reason) {
        String combined = error != null ? error + "; " + reason : reason;
        // A successful item keeps ok=false out of it: the call worked, only the
        // stored copy of its body is missing — the flag says so, the error explains.
        return new ForEachItemResult(index, httpCode, null, headers, ok() ? null : combined, timedOut, sent, attempts, true);
    }

    /**
     * The stored shape: plain maps, lists, strings and numbers. Optional members
     * are left out rather than written as null, which keeps the array small.
     */
    Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("index", index);
        map.put("ok", ok());
        if (httpCode != null) {
            map.put("httpCode", httpCode);
        }
        if (body != null) {
            map.put("body", body);
        }
        if (headers != null) {
            map.put("headers", headers);
        }
        if (error != null) {
            map.put("error", error);
        }
        if (timedOut) {
            map.put("timedOut", true);
        }
        if (!sent) {
            map.put("sent", false);
        }
        map.put("attempts", attempts);
        if (bodyDropped) {
            map.put("bodyDropped", true);
        }
        return map;
    }
}
