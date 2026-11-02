/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.alerts;

import ai.labs.eddi.engine.alerts.model.ErrorKind;

/**
 * A turn that ended badly, as the turn runner saw it — fired as a CDI event
 * only for those turns, never for a clean one. Carries no conversation content.
 */
public record TurnOutcome(String conversationId, String agentId, Integer agentVersion, ErrorKind kind) {
}
