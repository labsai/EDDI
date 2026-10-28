/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts.model;

/**
 * The allow-listed outcomes a {@link AlertSource#CONVERSATION_ERROR} rule can
 * watch. An alert names the kind, never the exception text: that can carry
 * conversation content, endpoint addresses or provider error bodies.
 */
public enum ErrorKind {
    /** The pipeline failed: the conversation is in {@code ERROR}. */
    TURN_ERROR,
    /**
     * The turn was cancelled while running ({@code EXECUTION_INTERRUPTED}): a
     * cluster lease lost to another node, an administrator's cancel, a GDPR stop.
     */
    TURN_INTERRUPTED,
    /** The turn ran past the agent timeout and the watchdog abandoned it. */
    TURN_DEADLINE,
    /** The agent's {@code maxCostPerConversationUsd} stopped a model call. */
    COST_CEILING,
    /**
     * The tenant's monthly cost budget (or its accounting) stopped a model call.
     */
    TENANT_BUDGET
}
