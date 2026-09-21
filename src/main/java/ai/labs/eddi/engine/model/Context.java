/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.model;

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

    public Boolean getSecret() {
        return secret;
    }

    public void setSecret(Boolean secret) {
        this.secret = secret;
    }
}