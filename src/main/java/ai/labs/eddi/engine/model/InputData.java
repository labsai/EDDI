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

    /**
     * When the HTTP request carrying this input reached the server (epoch ms), set
     * by the REST layer. Time the request spent waiting for a thread counts against
     * the caller's patience, so budgets start here. Header-side only: never read
     * from or written to a request body.
     */
    @JsonIgnore
    private Long requestArrivalEpochMs;

    /**
     * When the caller stops waiting for this turn's answer (epoch ms), set by a
     * REST surface that answers with a timeout at that point. A turn still queued
     * then is not run: its caller already has its {@code 408}. Never read from or
     * written to a request body.
     */
    @JsonIgnore
    private Long callerGivesUpAtEpochMs;

    /**
     * Set only when the engine runs a self-continuation turn
     * ({@code CONTINUE_CONVERSATION}): its number, which the turn's step records so
     * a re-delivered continuation is recognised and {@code maxContinuations} can be
     * counted. Never read from or written to a request body — a client cannot claim
     * a turn is a continuation.
     */
    @JsonIgnore
    private Integer continuationNumber;

    public InputData() {
    }

    @JsonIgnore
    public Integer getContinuationNumber() {
        return continuationNumber;
    }

    @JsonIgnore
    public void setContinuationNumber(Integer continuationNumber) {
        this.continuationNumber = continuationNumber;
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

    @JsonIgnore
    public Long getRequestArrivalEpochMs() {
        return requestArrivalEpochMs;
    }

    @JsonIgnore
    public void setRequestArrivalEpochMs(Long requestArrivalEpochMs) {
        this.requestArrivalEpochMs = requestArrivalEpochMs;
    }

    @JsonIgnore
    public Long getCallerGivesUpAtEpochMs() {
        return callerGivesUpAtEpochMs;
    }

    @JsonIgnore
    public void setCallerGivesUpAtEpochMs(Long callerGivesUpAtEpochMs) {
        this.callerGivesUpAtEpochMs = callerGivesUpAtEpochMs;
    }
}
