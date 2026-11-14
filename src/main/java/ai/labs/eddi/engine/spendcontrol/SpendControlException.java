/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.spendcontrol;

import java.util.Map;

/**
 * A refused spend-control write or decision, with the HTTP status and a stable
 * error code that both the REST resource and the MCP tools report.
 */
public class SpendControlException extends RuntimeException {

    public static final String BAD_REQUEST = "BAD_REQUEST";
    public static final String FORBIDDEN = "FORBIDDEN";
    public static final String NOT_FOUND = "NOT_FOUND";
    /** The catalog revision or the quota fingerprint changed since it was read. */
    public static final String CONFLICT = "CONFLICT";
    /** The change was already decided, or expired. */
    public static final String ALREADY_DECIDED = "ALREADY_DECIDED";
    /** The control changed since the proposal was filed; nothing was applied. */
    public static final String STALE = "STALE";
    public static final String TOO_MANY_PENDING = "TOO_MANY_PENDING";
    /**
     * The write of an approved change failed; whether it took effect is unknown.
     */
    public static final String APPLY_FAILED = "APPLY_FAILED";
    /** The approver is the principal that proposed the change. */
    public static final String SELF_APPROVAL = "SELF_APPROVAL";

    private final int status;
    private final String code;
    private final Map<String, Object> details;

    public SpendControlException(int status, String code, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details == null ? Map.of() : Map.copyOf(details);
    }

    public SpendControlException(int status, String code, String message) {
        this(status, code, message, null);
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }

    public Map<String, Object> details() {
        return details;
    }
}
