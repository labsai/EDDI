/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.pricing;

/**
 * Whom a model call made on behalf of a document owner is charged to — the
 * owner-keyed counterpart of the group conversation that the group variant of
 * {@link SystemSpendMeter} charges.
 * <p>
 * A document job or tool acts as the owner snapshot taken when the work was
 * requested, never as "the system", so the owner's daily ceilings and the
 * tenant budget apply to it.
 *
 * @param ownerKey
 *            the owner key of the document's lineage (a conversation id, a
 *            personal space tenant or a team space tenant); attribution only
 * @param userId
 *            the user the spend counts against — exactly the id the
 *            conversation carries, which is the id a GDPR erasure names — or
 *            null when no user is accountable
 * @param teamSpaceId
 *            the team space ({@code team:<path>}) whose daily ceiling also
 *            applies, or null outside a team library
 * @param tenantId
 *            the tenant charged; null means the deployment's default tenant
 */
public record SpendOwner(String ownerKey, String userId, String teamSpaceId, String tenantId) {
}
