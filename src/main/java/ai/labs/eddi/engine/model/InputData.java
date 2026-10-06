/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.HashMap;
import java.util.Map;

/**
 * @author ginccc
 */
public class InputData {
    private String input = "";
    private Map<String, Context> context = new HashMap<>();

    /**
     * The caller's requested turn budget (the {@code X-EDDI-Turn-Deadline-Ms}
     * header), set by the REST layer. Never read from or written to a request body.
     */
    @JsonIgnore
    private Long requestedTurnDeadlineMs;

    /**
     * The caller's {@code Idempotency-Key} (or {@code X-EDDI-Request-Id}), already
     * validated by the REST layer. Header-only: never read from or written to a
     * request body.
     */
    @JsonIgnore
    private String idempotencyKey;

    public InputData() {
    }

    @JsonIgnore
    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    @JsonIgnore
    public void setIdempotencyKey(String idempotencyKey) {
        this.idempotencyKey = idempotencyKey;
    }

    public InputData(String input, Map<String, Context> context) {
        this.input = input;
        this.context = context;
    }

    public String getInput() {
        return input;
    }

    public void setInput(String input) {
        this.input = input;
    }

    public Map<String, Context> getContext() {
        return context;
    }

    public void setContext(Map<String, Context> context) {
        this.context = context;
    }

    @JsonIgnore
    public Long getRequestedTurnDeadlineMs() {
        return requestedTurnDeadlineMs;
    }

    @JsonIgnore
    public void setRequestedTurnDeadlineMs(Long requestedTurnDeadlineMs) {
        this.requestedTurnDeadlineMs = requestedTurnDeadlineMs;
    }
}
