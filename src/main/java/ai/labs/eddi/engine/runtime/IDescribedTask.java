/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.runtime;

import java.util.Map;

/**
 * A coordinator task that can describe the turn it runs, so a dead letter
 * carries enough to replay it as a new turn: conversation, agent, environment,
 * user, the input and (optionally) the context. Keys: {@code conversationId},
 * {@code agentId}, {@code agentVersion}, {@code environment}, {@code userId},
 * {@code input}, {@code context}, {@code rerun}.
 */
public interface IDescribedTask {
    Map<String, Object> describe();
}
