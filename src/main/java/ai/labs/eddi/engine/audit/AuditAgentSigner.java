/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.audit;

import ai.labs.eddi.configs.agents.AgentConfigurationCache;
import ai.labs.eddi.configs.agents.AgentSigningService;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.crypto.AgentPublicKey;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.agents.model.AgentConfiguration.AgentIdentity;
import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * An agent's own Ed25519 signature on its audit entries: written by
 * {@link AuditLedgerService} when an entry is submitted, checked by
 * {@code /auditstore/verify}.
 *
 * <p>
 * <strong>Who signs, and with what.</strong> The agent version that produced
 * the entry decides, through the same setting as its group messages:
 * {@code security.signInterAgentMessages} on, and a key declared in
 * {@code identity}. It signs with that version's newest key, and only while
 * that key is usable at the entry's own timestamp — not revoked, and inside its
 * {@code validFromMs}/{@code validUntilMs} window. The timestamp is the
 * ledger's, covered by the HMAC, not one a signer chooses. Every new signature
 * is checked against the public key the version declares before it is stored:
 * an agent version saved before keys were engine-owned can declare a key its
 * vault entry does not match, and its signature would otherwise read as a
 * forgery. Only entries that carry an HMAC are signed: the signature covers the
 * HMAC, which covers the entry, and a signature over an entry id alone would
 * prove nothing about its content.
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
 * <strong>What verifies.</strong> Exactly what {@link #sign} writes: the
 * signature of the key the entry's own agent version signs with, usable at the
 * entry's timestamp. Agent versions are immutable, so editing the agent
 * afterwards (or replacing its key) cannot make an old entry verify against a
 * substituted key. A signature under any other key, or one the ledger would not
 * have made, is {@link Verdict#INVALID}. A soft-deleted agent's versions are
 * still read: the delete flags them and leaves them unchanged, so the ledger
 * outlives the agent.
 * </p>
 *
 * <p>
 * <strong>Not proven, not disproven.</strong> The HMAC proves such an entry's
 * content; only the agent's vouching for it is missing:
 * </p>
 * <ul>
 * <li>{@link Verdict#KEY_REVOKED} — the agent's newest version has revoked the
 * key since, or no longer declares it. Revoking writes a new agent version, so
 * the newest one is asked as well as the entry's own, soft-deleted or not: a
 * delete after the revocation does not take it back. A revoked key is trusted
 * for nothing, whenever it signed: whoever holds a leaked key and the audit
 * HMAC key also chooses the timestamp, so no "signed before the revocation"
 * rule can tell their rows from honest ones.</li>
 * <li>{@link Verdict#MISSING} — no signature on an entry its agent version
 * would have signed: removed from the row, or the vault could not sign when the
 * entry was written (logged then). The HMAC is computed before the agent signs
 * and cannot cover the signature, so this is how a signature stripped by
 * someone who can edit rows but does not hold the audit HMAC key shows. It is
 * no alarm against whoever holds that key: whether a signature was expected is
 * decided by the row's {@code agentVersion} and {@code timestamp}, which that
 * party writes and HMACs, so it can name a version that does not sign or a time
 * before the key's {@code validFromMs}, and the row reads
 * {@link Verdict#NOT_SIGNED}.</li>
 * <li>{@link Verdict#KEY_UNAVAILABLE} — the agent version that would check the
 * signature no longer exists (the agent was deleted permanently).</li>
 * </ul>
 *
 * @since 6.7.0
 */
@ApplicationScoped
public class AuditAgentSigner {

    private static final Logger LOGGER = Logger.getLogger(AuditAgentSigner.class);

    /** Domain separation: what precedes the HMAC in the signed bytes. */
    static final String SIGNED_PREFIX = "eddi-audit-entry:";

    /**
     * {@code v<keyVersion>:<Base64 signature>}, the version written the way
     * {@link #sign} writes it: no leading zero, and wide enough for
     * {@link AgentPublicKey#MAX_VERSION}.
     */
    private static final Pattern STORED_FORM = Pattern.compile("v(0|[1-9]\\d{0,6}):([A-Za-z0-9+/]+=*)");

    /** What {@link #verify} found. */
    public enum Verdict {
        /**
         * The entry carries no agent signature, and its agent version would not sign
         * it.
         */
        NOT_SIGNED,
        /** The signature verifies against the key its agent version signs with. */
        VALID,
        /**
         * The signature does not verify, is malformed, or is one {@link #sign} would
         * not have written: another key than the agent version's newest, a key outside
         * its window at the entry's timestamp, or an agent version with signing off.
         */
        INVALID,
        /**
         * The entry's agent version no longer exists, so the key to check it with is
         * gone.
         */
        KEY_UNAVAILABLE,
        /**
         * The agent has revoked the key since, or no longer declares it: whatever the
         * entry carries, that key vouches for nothing.
         */
        KEY_REVOKED,
        /**
         * The entry carries no signature, and its agent version would have signed it.
         */
        MISSING
    }

    /**
     * How long one WARN about a key missing from the vault stands for its repeats.
     */
    private static final Duration KEY_NOT_IN_VAULT_WARN_INTERVAL = Duration.ofMinutes(10);

    /** A key missing from the vault, as one agent version names it. */
    private record VersionKey(String tenantId, String agentId, int agentVersion, int keyVersion) {
    }

    private final AgentSigningService signingService;
    private final AgentConfigurationCache agentConfigurations;
    private final IAgentStore agentStore;

    /**
     * The agent versions whose missing key was reported at WARN within the last
     * {@link #KEY_NOT_IN_VAULT_WARN_INTERVAL}.
     */
    private final Cache<VersionKey, Boolean> warnedKeyNotInVault = Caffeine.newBuilder()
            .maximumSize(1_000)
            .expireAfterWrite(KEY_NOT_IN_VAULT_WARN_INTERVAL)
            .build();

    @Inject
    public AuditAgentSigner(AgentSigningService signingService, AgentConfigurationCache agentConfigurations, IAgentStore agentStore) {
        this.signingService = signingService;
        this.agentConfigurations = agentConfigurations;
        this.agentStore = agentStore;
    }

    /**
     * The entry with its agent's signature, or unchanged when its agent version
     * does not sign it, it has no HMAC, or signing failed — whatever failed. Runs
     * on the turn's thread after the ledger took the entry's chain position, so
     * nothing may escape: a lost entry would leave a gap that verification reports
     * as a deletion. The agent version is read through
     * {@link AgentConfigurationCache}, and an agent that does not sign costs no
     * vault read at all.
     */
    public AuditEntry sign(String tenantId, AuditEntry entry) {
        if (entry.hmac() == null || entry.agentId() == null || entry.agentVersion() == null) {
            return entry;
        }
        try {
            AgentIdentity identity = signingIdentity(agentVersionOf(entry), entry.timestamp());
            if (identity == null) {
                return entry;
            }
            int keyVersion = identity.signingKeyVersion();
            String signature = signingService.signIfKeyPresent(tenantId, entry.agentId(), keyVersion, signedBytes(entry));
            if (signature == null) {
                logKeyNotInVault(tenantId, entry, keyVersion);
                return entry;
            }
            if (!signingService.verify(identity.getKeyForVersion(keyVersion), signedBytes(entry), signature)) {
                LOGGER.errorf("Audit entry '%s' of agent '%s' is not signed: the vault's key v%d does not match the public key"
                        + " agent version %d declares for it", sanitize(entry.id()), sanitize(entry.agentId()), keyVersion,
                        entry.agentVersion());
                return entry;
            }
            return entry.withAgentSignature("v" + keyVersion + ":" + signature);
        } catch (ResourceNotFoundException e) {
            // Deleted permanently, and its private keys with it: there is nothing to sign
            // with. An erasure resealing that agent's rows ends up here once per row.
            LOGGER.debugf("Audit entry '%s' of agent '%s' is not signed: its agent version no longer exists", sanitize(entry.id()),
                    sanitize(entry.agentId()));
            return entry;
        } catch (ResourceStoreException | AgentSigningService.AgentSigningException | RuntimeException e) {
            // Unchecked failures included: a store driver throws them on a timeout.
            // Verification reports the entry as missing its signature; this line says why.
            LOGGER.warnf("Audit entry '%s' of agent '%s' is not signed: %s", sanitize(entry.id()), sanitize(entry.agentId()),
                    sanitize(e.getMessage()));
            return entry;
        }
    }

    /**
     * Revoking the newest key deletes it from the vault, while the deployed agent
     * version still names it, so every entry of that version goes unsigned until a
     * redeploy. The first such entry per agent version and key is a WARN; its
     * repeats within {@link #KEY_NOT_IN_VAULT_WARN_INTERVAL} are DEBUG, or a busy
     * agent floods the log during the incident that caused the revocation.
     */
    private void logKeyNotInVault(String tenantId, AuditEntry entry, int keyVersion) {
        var key = new VersionKey(tenantId, entry.agentId(), entry.agentVersion(), keyVersion);
        if (warnedKeyNotInVault.asMap().putIfAbsent(key, Boolean.TRUE) == null) {
            LOGGER.warnf("Audit entry '%s' of agent '%s' version %d is not signed: key v%d is not in the secrets vault (revoked or"
                    + " deleted). Entries of that version stay unsigned until a version declaring a current key is deployed;"
                    + " further ones in the next %d minutes are logged at DEBUG", sanitize(entry.id()), sanitize(entry.agentId()),
                    entry.agentVersion(), keyVersion, KEY_NOT_IN_VAULT_WARN_INTERVAL.toMinutes());
        } else {
            LOGGER.debugf("Audit entry '%s' of agent '%s' is not signed: key v%d is not in the secrets vault", sanitize(entry.id()),
                    sanitize(entry.agentId()), keyVersion);
        }
    }

    /**
     * Checks a stored entry's agent signature, or its absence, against the agent
     * version that produced it and the agent's newest version. The HMAC is not
     * checked here; a signature only means something on an entry whose HMAC
     * verified.
     *
     * @throws IllegalStateException
     *             if the agent store could not be read, so the sweep fails rather
     *             than reporting a key as missing
     */
    public Verdict verify(AuditEntry entry) {
        String stored = entry.agentSignature();
        if (entry.hmac() == null || entry.agentId() == null || entry.agentVersion() == null) {
            return stored == null ? Verdict.NOT_SIGNED : Verdict.INVALID;
        }
        AgentConfiguration config;
        try {
            config = agentVersionOf(entry);
        } catch (ResourceNotFoundException e) {
            config = null;
        } catch (ResourceStoreException e) {
            throw unreadable(entry, entry.agentVersion(), e);
        }
        if (config == null) {
            // Nothing says what the entry should carry any more, and nothing can check
            // what it does carry.
            return stored == null ? Verdict.NOT_SIGNED : Verdict.KEY_UNAVAILABLE;
        }
        AgentIdentity identity = signingIdentity(config, entry.timestamp());
        if (stored == null) {
            if (identity == null) {
                return Verdict.NOT_SIGNED;
            }
            int keyVersion = identity.signingKeyVersion();
            return withdrawnSince(entry, keyVersion, identity.getKeyForVersion(keyVersion)) ? Verdict.KEY_REVOKED : Verdict.MISSING;
        }
        Matcher matcher = STORED_FORM.matcher(stored);
        if (identity == null || !matcher.matches() || Integer.parseInt(matcher.group(1)) != identity.signingKeyVersion()) {
            return Verdict.INVALID;
        }
        int keyVersion = identity.signingKeyVersion();
        String publicKey = identity.getKeyForVersion(keyVersion);
        if (!signingService.verify(publicKey, signedBytes(entry), matcher.group(2))) {
            return Verdict.INVALID;
        }
        return withdrawnSince(entry, keyVersion, publicKey) ? Verdict.KEY_REVOKED : Verdict.VALID;
    }

    /**
     * The identity {@code config} signs with at {@code at}, or {@code null} when
     * that agent version does not sign then: signing off, no key declared, or its
     * newest key revoked or outside its window at that time. {@link #sign} and
     * {@link #verify} both decide by this, so a signature verifies exactly when the
     * ledger would have written it.
     */
    private static AgentIdentity signingIdentity(AgentConfiguration config, Instant at) {
        if (config == null || config.getSecurity() == null || !config.getSecurity().isSignInterAgentMessages()) {
            return null;
        }
        AgentIdentity identity = config.getIdentity();
        if (identity == null) {
            return null;
        }
        int keyVersion = identity.signingKeyVersion();
        if (identity.getKeyForVersion(keyVersion) == null) {
            return null;
        }
        // The legacy unversioned key has neither a window nor a revoked flag.
        List<AgentPublicKey> declared = identity.getKeys().stream().filter(key -> key.version() == keyVersion).toList();
        boolean usable = declared.isEmpty() || at != null && declared.stream().allMatch(key -> key.isValidAt(at.toEpochMilli()));
        return usable ? identity : null;
    }

    /**
     * Whether the agent's newest version has withdrawn key {@code keyVersion}:
     * revoked it, or no longer declares {@code publicKey} for it. The newest
     * version counts whether or not it is soft-deleted: revoking a leaked key and
     * then deleting the agent is how a compromise is handled, and the delete must
     * not take the revocation back. Only an agent with no version newer than the
     * entry's has nothing more to say.
     */
    private boolean withdrawnSince(AuditEntry entry, int keyVersion, String publicKey) {
        AgentConfiguration newest;
        try {
            Integer newestVersion = agentStore.getNewestVersionIncludingDeleted(entry.agentId());
            if (newestVersion == null || newestVersion.equals(entry.agentVersion())) {
                return false;
            }
            newest = agentConfigurations.readIncludingDeleted(entry.agentId(), newestVersion);
        } catch (ResourceNotFoundException e) {
            // Deleted permanently since the entry's version was read: nothing newer is
            // left to say anything.
            return false;
        } catch (ResourceStoreException e) {
            throw unreadable(entry, null, e);
        }
        if (newest == null) {
            return false;
        }
        AgentIdentity identity = newest.getIdentity();
        if (identity == null || !publicKey.equals(identity.getKeyForVersion(keyVersion))) {
            return true;
        }
        return identity.getKeys().stream().anyMatch(key -> key.version() == keyVersion && key.revoked());
    }

    private static IllegalStateException unreadable(AuditEntry entry, Integer agentVersion, ResourceStoreException cause) {
        String which = agentVersion != null ? "version " + agentVersion : "the newest version";
        return new IllegalStateException(
                "Could not read " + which + " of agent " + entry.agentId() + " to verify the agent signature of audit entry " + entry.id(),
                cause);
    }

    /**
     * The agent version that produced the entry, soft-deleted or not: an old entry
     * is checked against what that version declared, whatever has happened to the
     * agent since.
     */
    private AgentConfiguration agentVersionOf(AuditEntry entry) throws ResourceNotFoundException, ResourceStoreException {
        return agentConfigurations.readIncludingDeleted(entry.agentId(), entry.agentVersion());
    }

    private static String signedBytes(AuditEntry entry) {
        return SIGNED_PREFIX + entry.hmac();
    }
}
