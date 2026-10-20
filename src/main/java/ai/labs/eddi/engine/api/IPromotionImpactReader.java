/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.api;

import ai.labs.eddi.engine.model.PromotionImpact;

/**
 * Answers, for engine code that previews a write, what the next version of an
 * agent would do to the conversations running on it. Counts only — it never
 * loads a conversation.
 */
public interface IPromotionImpactReader {

    /**
     * @param agentId
     *            the existing agent a version would be written over
     * @return the impact under both a compatible and a breaking declaration, or
     *         {@code null} when the caller may not see conversation counts for the
     *         agent (it takes EDIT, like the write itself) or the agent's current
     *         version cannot be established
     */
    PromotionImpact previewNextVersion(String agentId);
}
