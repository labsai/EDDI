/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * @author ginccc
 */
public class Context {
    public enum ContextType {
        string, expressions, object, array
    }

    private ContextType type;
    private Object value;
    /**
     * Set by the client for a value that must never be persisted or returned — a
     * per-request credential such as the caller's token for a downstream API. It is
     * usable in templates and behavior rules for the turn it arrives with, and
     * replaced by {@code MemoryKeys.SECRET_CONTEXT_PLACEHOLDER} before the step is
     * stored, returned or audited. {@code null} (not sent) means not secret; kept
     * nullable so ordinary context entries serialize unchanged.
     */
    private Boolean secret;

    /**
     * {@code true} on a context entry the engine itself wrote during the turn — the
     * output and quick replies a {@code postResponse} built — as opposed to one a
     * client sent with its input. Never serialized and never read from JSON, so a
     * client cannot set it; it lives only as long as the turn's memory.
     */
    @JsonIgnore
    private transient boolean serverGenerated;

    public Context() {
    }

    public Context(ContextType type, Object value) {
        this.type = type;
        this.value = value;
    }

    public ContextType getType() {
        return type;
    }

    public void setType(ContextType type) {
        this.type = type;
    }

    public Object getValue() {
        return value;
    }

    public void setValue(Object value) {
        this.value = value;
    }

    @JsonIgnore
    public boolean isServerGenerated() {
        return serverGenerated;
    }

    @JsonIgnore
    public void setServerGenerated(boolean serverGenerated) {
        this.serverGenerated = serverGenerated;
    }

    public Boolean getSecret() {
        return secret;
    }

    public void setSecret(Boolean secret) {
        this.secret = secret;
    }
}