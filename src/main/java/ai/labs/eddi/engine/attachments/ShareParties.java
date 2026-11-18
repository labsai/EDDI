/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments;

/**
 * Who a share is between, for its audit entry. The caller holds the source
 * conversation's memory and the target it created, so it supplies these; the
 * service never loads a conversation to find them. Every field may be
 * {@code null} when the caller does not know it.
 *
 * @param userId
 *            the source conversation's user — the key the audit ledger
 *            pseudonymises the entry by on erasure
 * @param sourceAgentId
 *            the agent of the source conversation
 * @param sourceAgentVersion
 *            its version
 * @param targetAgentId
 *            the agent of the target conversation
 * @param environment
 *            the deployment environment of the source conversation
 * @since 6.7.0
 */
public record ShareParties(String userId, String sourceAgentId, Integer sourceAgentVersion, String targetAgentId, String environment) {

    /** No party known. */
    public static final ShareParties UNKNOWN = new ShareParties(null, null, null, null, null);
}
