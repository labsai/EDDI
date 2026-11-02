/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.quota;

/**
 * One counter as held by the store: how many calls the subject has made in a
 * window. What a GDPR export of the subject carries.
 */
public record ToolQuotaUsage(String agentId, String counter, long windowStart, int used) {
}
