/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.secrets;

import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.secrets.ISecretProvider.GrantConflictException;
import ai.labs.eddi.secrets.ISecretProvider.SecretNotFoundException;
import ai.labs.eddi.secrets.ISecretProvider.SecretProviderException;
import ai.labs.eddi.secrets.model.SecretMetadata;
import ai.labs.eddi.secrets.model.SecretReference;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Adds one agent to a secret's grant — the change every NEW agent that uses a
 * restricted secret needs, because a grant lists agent ids and the id did not
 * exist when the grant was written.
 * <p>
 * <b>Why an append, not the replacing {@code PUT …/grant}.</b> A replace is a
 * read-modify-write in the caller: two admins granting two new agents at once
 * each read {@code [a]}, each write {@code [a, theirs]}, and one grant is
 * silently lost. Here the write is the provider's compare-and-set
 * ({@code updateGrant(…, expectedAllowedAgents)}), retried on a conflict, so
 * concurrent appends all land.
 * <p>
 * <b>Never widens to everyone, never touches an open secret.</b> A secret that
 * already grants every agent ({@code ["*"]}, empty or absent) is left exactly
 * as it is: appending an id there would turn "every agent" into "these agents"
 * — a narrowing nobody asked for.
 * <p>
 * <b>Who may call it.</b> Only admin-authorised paths: the {@code eddi-admin}
 * REST endpoint and the setup API's {@code grantReferencedSecrets} flag, which
 * refuses non-admins. No MCP tool reaches it — an agent that can grant itself
 * secrets defeats the gate.
 * <p>
 * Every real change writes an audit-ledger entry, on top of the provider's INFO
 * log line, so "who let this agent use that key, and from where" can be
 * answered afterwards.
 */
@ApplicationScoped
public class VaultGrantService {

    private static final Logger LOGGER = Logger.getLogger(VaultGrantService.class);

    /** Ledger task type and id of a grant append. */
    public static final String AUDIT_TASK_TYPE = "vault";
    public static final String AUDIT_TASK_ID = "vault.grant.append";

    /**
     * Compare-and-set attempts before giving up. Each conflict means another writer
     * landed in between; a handful covers any realistic admin burst.
     */
    static final int MAX_ATTEMPTS = 8;

    /** Same cap the replacing endpoint enforces. */
    public static final int MAX_ALLOWED_AGENTS = 500;

    private final ISecretProvider secretProvider;
    private final AuditLedgerService auditLedgerService;

    @Inject
    public VaultGrantService(ISecretProvider secretProvider, AuditLedgerService auditLedgerService) {
        this.secretProvider = secretProvider;
        this.auditLedgerService = auditLedgerService;
    }

    /**
     * The outcome of an append.
     *
     * @param before
     *            the grant as read
     * @param after
     *            the grant as written — or, for a dry run, as it would be
     * @param changed
     *            false when the agent was already granted, or the secret is open to
     *            every agent
     */
    public record AppendResult(SecretMetadata before, SecretMetadata after, boolean changed) {
    }

    /**
     * Adds {@code agentId} to the grant of {@code secret}.
     *
     * @param actor
     *            who asked, for the audit entry
     * @param origin
     *            which path asked ({@code rest}, {@code setup}), for the audit
     *            entry
     * @param dryRun
     *            when true nothing is written and nothing is audited
     * @throws SecretNotFoundException
     *             the secret does not exist
     * @throws GrantConflictException
     *             concurrent edits kept winning for {@link #MAX_ATTEMPTS} attempts
     * @throws IllegalArgumentException
     *             the grant would exceed {@link #MAX_ALLOWED_AGENTS}
     */
    public AppendResult grantAgent(SecretReference secret, String agentId, String actor, String origin, boolean dryRun)
            throws SecretNotFoundException, SecretProviderException {
        GrantConflictException lastConflict = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            SecretMetadata before = secretProvider.getMetadata(secret);
            List<String> current = before.allowedAgents();
            if (SecretMetadata.grantsAllAgents(current) || current.contains(agentId)) {
                return new AppendResult(before, before, false);
            }
            if (current.size() >= MAX_ALLOWED_AGENTS) {
                throw new IllegalArgumentException("The grant of " + secret.toReferenceString() + " already lists " + current.size()
                        + " agents, the most a grant may hold (" + MAX_ALLOWED_AGENTS + "). Remove agents that no longer need it first.");
            }
            List<String> next = new ArrayList<>(current);
            next.add(agentId);
            if (dryRun) {
                return new AppendResult(before, withGrant(before, next), true);
            }
            try {
                SecretMetadata after = secretProvider.updateGrant(secret, next, null, current);
                audit(secret, agentId, actor, origin, current, after.allowedAgents());
                return new AppendResult(before, after, true);
            } catch (GrantConflictException e) {
                // Somebody else changed the grant between the read and the guarded write.
                // Re-read and try again: their change is kept, ours is added to it.
                lastConflict = e;
            }
        }
        throw lastConflict;
    }

    private static SecretMetadata withGrant(SecretMetadata metadata, List<String> grant) {
        return new SecretMetadata(metadata.tenantId(), metadata.keyName(), metadata.createdAt(), metadata.lastAccessedAt(),
                metadata.lastRotatedAt(), metadata.checksum(), metadata.description(), List.copyOf(grant));
    }

    private void audit(SecretReference secret, String agentId, String actor, String origin, List<String> previous, List<String> now) {
        if (auditLedgerService == null || !auditLedgerService.isEnabled()) {
            return;
        }
        try {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("secret", secret.toReferenceString());
            input.put("origin", origin);
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("grantedAgentId", agentId);
            output.put("previousAllowedAgents", List.copyOf(previous));
            output.put("allowedAgents", List.copyOf(now));
            output.put("actor", actor != null ? actor : "unknown");
            auditLedgerService.submit(new AuditEntry(UUID.randomUUID().toString(), null, agentId, null, actor, null, 0, AUDIT_TASK_ID,
                    AUDIT_TASK_TYPE, 0, 0L, input, output, null, null, List.of(AUDIT_TASK_ID), 0.0, Instant.now(), null, null));
        } catch (Exception e) {
            // The grant is written; a failed audit write must not report it as failed.
            LOGGER.warnf("Failed to write the audit entry for granting %s to agent '%s': %s", sanitize(secret.toReferenceString()),
                    sanitize(agentId), sanitize(e.getMessage()));
        }
    }
}
