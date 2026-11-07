/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.audit;

import ai.labs.eddi.configs.agents.AgentConfigurationCache;
import ai.labs.eddi.configs.agents.AgentSigningService;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.agents.model.AgentConfiguration.AgentIdentity;
import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * An agent's own Ed25519 signature on its audit entries: written by
 * {@link AuditLedgerService} when an entry is submitted, checked by
 * {@code /auditstore/verify}.
 *
 * <p>
 * <strong>Who signs.</strong> The agent version that produced the entry
 * decides, through the same setting as its group messages:
 * {@code security.signInterAgentMessages} on, and a key declared in
 * {@code identity}. That is the one setting whose save proves the key can sign
 * (see {@code RestAgentStore.validateSecurityFlags}), so an agent never writes
 * a signature its own public key will not verify. Only entries that carry an
 * HMAC are signed: the signature covers the HMAC, which covers the entry, and a
 * signature over an entry id alone would prove nothing about its content.
 * </p>
 *
 * <p>
 * <strong>The stored form</strong> is {@code v<keyVersion>:<Base64 signature>}.
 * The version is part of the value, so the two cannot be stored, redacted or
 * dead-lettered apart, and no column of the append-only ledger had to change.
 * Rewriting the version names a different key, which then does not verify. The
 * signed bytes are {@value #SIGNED_PREFIX} followed by the entry's HMAC, so an
 * audit signature is never a valid signature over anything else the agent's key
 * signs.
 * </p>
 *
 * <p>
 * <strong>Which public key verifies.</strong> The one the entry's own agent
 * version declares for that key version. Agent versions are immutable, so
 * editing the agent afterwards (or replacing its key) cannot make an old entry
 * verify against a substituted key. An entry naming a key version its agent
 * version never declared is {@link Verdict#INVALID}, as a forged row would be;
 * one whose agent version no longer exists (the agent was deleted) is
 * {@link Verdict#KEY_UNAVAILABLE}: not proven, not disproven.
 * </p>
 *
 * <p>
 * <strong>Not detected:</strong> a signature removed from a row. The HMAC is
 * computed before the agent signs it and so cannot cover the signature, and an
 * unsigned entry is also what an agent with signing off writes.
 * </p>
 *
 * @since 6.7.0
 */
@ApplicationScoped
public class AuditAgentSigner {

    private static final Logger LOGGER = Logger.getLogger(AuditAgentSigner.class);

    /** Domain separation: what precedes the HMAC in the signed bytes. */
    static final String SIGNED_PREFIX = "eddi-audit-entry:";

    /** {@code v<keyVersion>:<Base64 signature>}. */
    private static final Pattern STORED_FORM = Pattern.compile("v(\\d{1,9}):([A-Za-z0-9+/]+=*)");

    /** What {@link #verify} found. */
    public enum Verdict {
        /** The entry carries no agent signature. */
        NOT_SIGNED,
        /** The signature verifies against the key its agent version declares. */
        VALID,
        /**
         * The signature does not verify, is malformed, or names a key version its agent
         * version never declared.
         */
        INVALID,
        /**
         * The entry's agent version no longer exists, so the key to check it with is
         * gone.
         */
        KEY_UNAVAILABLE
    }

    private final AgentSigningService signingService;
    private final AgentConfigurationCache agentConfigurations;

    @Inject
    public AuditAgentSigner(AgentSigningService signingService, AgentConfigurationCache agentConfigurations) {
        this.signingService = signingService;
        this.agentConfigurations = agentConfigurations;
    }

    /**
     * The entry with its agent's signature, or unchanged when its agent version
     * does not sign, it has no HMAC, or signing failed. Runs on the turn's thread:
     * the agent version is read through {@link AgentConfigurationCache}, and an
     * agent that does not sign costs no vault read at all.
     */
    public AuditEntry sign(String tenantId, AuditEntry entry) {
        if (entry.hmac() == null || entry.agentId() == null || entry.agentVersion() == null) {
            return entry;
        }
        try {
            AgentConfiguration config = agentConfigurations.read(entry.agentId(), entry.agentVersion());
            if (config == null || config.getSecurity() == null || !config.getSecurity().isSignInterAgentMessages()) {
                return entry;
            }
            AgentIdentity identity = config.getIdentity();
            if (identity == null) {
                return entry;
            }
            int keyVersion = identity.signingKeyVersion();
            if (identity.getKeyForVersion(keyVersion) == null) {
                return entry;
            }
            String signature = signingService.signIfKeyPresent(tenantId, entry.agentId(), keyVersion, signedBytes(entry));
            if (signature == null) {
                LOGGER.warnf("Audit entry '%s' of agent '%s' is not signed: key v%d is not in the secrets vault", sanitize(entry.id()),
                        sanitize(entry.agentId()), keyVersion);
                return entry;
            }
            return entry.withAgentSignature("v" + keyVersion + ":" + signature);
        } catch (ResourceNotFoundException | ResourceStoreException | AgentSigningService.AgentSigningException e) {
            // A removed signature cannot be detected later (see the class Javadoc), so
            // this line is the only record that the entry should have carried one.
            LOGGER.warnf("Audit entry '%s' of agent '%s' is not signed: %s", sanitize(entry.id()), sanitize(entry.agentId()),
                    sanitize(e.getMessage()));
            return entry;
        }
    }

    /**
     * Checks a stored entry's agent signature against the public key its agent
     * version declares. The HMAC is not checked here; a signature only means
     * something on an entry whose HMAC verified.
     *
     * @throws IllegalStateException
     *             if the agent store could not be read, so the sweep fails rather
     *             than reporting a key as missing
     */
    public Verdict verify(AuditEntry entry) {
        String stored = entry.agentSignature();
        if (stored == null) {
            return Verdict.NOT_SIGNED;
        }
        Matcher matcher = STORED_FORM.matcher(stored);
        if (!matcher.matches() || entry.hmac() == null || entry.agentId() == null || entry.agentVersion() == null) {
            return Verdict.INVALID;
        }
        AgentConfiguration config;
        try {
            config = agentConfigurations.read(entry.agentId(), entry.agentVersion());
        } catch (ResourceNotFoundException e) {
            return Verdict.KEY_UNAVAILABLE;
        } catch (ResourceStoreException e) {
            throw new IllegalStateException("Could not read version " + entry.agentVersion() + " of agent " + entry.agentId()
                    + " to verify the agent signature of audit entry " + entry.id(), e);
        }
        if (config == null) {
            return Verdict.KEY_UNAVAILABLE;
        }
        String publicKey = config.getIdentity() != null ? config.getIdentity().getKeyForVersion(Integer.parseInt(matcher.group(1))) : null;
        if (publicKey == null) {
            return Verdict.INVALID;
        }
        return signingService.verify(publicKey, signedBytes(entry), matcher.group(2)) ? Verdict.VALID : Verdict.INVALID;
    }

    private static String signedBytes(AuditEntry entry) {
        return SIGNED_PREFIX + entry.hmac();
    }
}
