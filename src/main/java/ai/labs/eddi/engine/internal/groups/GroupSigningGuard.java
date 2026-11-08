/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.agents.AgentSigningService;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.crypto.AgentPublicKey;
import ai.labs.eddi.configs.agents.crypto.SignedEnvelope;
import ai.labs.eddi.configs.agents.model.AgentConfiguration.AgentIdentity;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.utils.LogSanitizer;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Signs the group-transcript entries of agents with
 * {@code security.signInterAgentMessages}, and decides which entries an agent
 * with {@code security.requirePeerVerification} may see.
 * <p>
 * <b>What a signature covers.</b> The Ed25519 signature is over the canonical
 * form of a {@link SignedEnvelope} whose sender is the speaking agent, whose
 * recipient is the group, and whose payload binds the entry to this
 * conversation and this position in it: the group conversation id, the phase
 * index and name, the content, the speaker's display name, the target agent (if
 * any) and the key version. A signed entry copied into another conversation, or
 * moved to another phase, therefore no longer verifies. A copy inside the same
 * phase verifies, so a nonce that already appeared earlier in the transcript is
 * rejected as a duplicate.
 * <p>
 * <b>Fail-closed.</b> For a receiver that requires peer verification,
 * {@link #visibleTo} replaces every entry that an agent authored and that does
 * not verify (unsigned, tampered, copied, duplicated, signed with an unknown
 * key or with a key outside its validity window) by a {@code [withheld: ...]}
 * placeholder, before the receiver's prompt and context are built. Entries no
 * agent authored (the user's question, a human member's turn, a reviewer's
 * edit, the engine's own notes, a nested group's answer) carry no signature and
 * pass through: signatures authenticate agents, not the engine's own record.
 * <p>
 * Stateless apart from {@link #reported}, which only keeps the security log
 * from repeating the same withheld entry on every turn of a discussion.
 *
 * @author ginccc
 */
public class GroupSigningGuard {

    private static final Logger LOGGER = Logger.getLogger(GroupSigningGuard.class);

    /**
     * {@code errorReason} of a placeholder that {@link #visibleTo} put in place of
     * an entry that did not verify.
     */
    public static final String WITHHELD_MARKER = "peer-verification:withheld";

    /**
     * Clock difference tolerated between the node that generated a key or created
     * the conversation and the node that signed: a key provisioned on one node is
     * usable on another whose clock runs slightly behind.
     */
    static final long CLOCK_SKEW_MS = 30_000L;

    private static final Set<String> ENGINE_SPEAKERS = Set.of("user", "system");

    private final IAgentStore agentStore;
    private final AgentSigningService agentSigningService;
    private final String defaultTenantId;

    /** Group conversation id to the withheld entries already logged at ERROR. */
    private final ConcurrentHashMap<String, Set<String>> reported = new ConcurrentHashMap<>();

    public GroupSigningGuard(IAgentStore agentStore, AgentSigningService agentSigningService, String defaultTenantId) {
        this.agentStore = agentStore;
        this.agentSigningService = agentSigningService;
        this.defaultTenantId = defaultTenantId;
    }

    /**
     * The outcome of checking one transcript entry. Only {@link #VERIFIED} and
     * {@link #NOT_AGENT_AUTHORED} entries reach a receiver that requires peer
     * verification.
     */
    enum Verdict {
        VERIFIED(null), NOT_AGENT_AUTHORED(null), UNSIGNED("it carries no signature"), UNKNOWN_KEY(
                "the speaker declares no readable key of the version it was signed with"), OUT_OF_WINDOW(
                        "it was signed outside its key's validity window or before this conversation began"), BAD_SIGNATURE(
                                "its signature does not match its content, this conversation or its position in it"), DUPLICATE(
                                        "its signature already appears earlier in this conversation");

        private final String reason;

        Verdict(String reason) {
            this.reason = reason;
        }

        boolean trusted() {
            return reason == null;
        }
    }

    /**
     * Signs {@code entry} if its speaker has
     * {@code security.signInterAgentMessages}, and returns it with the signature,
     * nonce, timestamp and key version set. Returns {@code entry} unchanged when
     * the speaker does not sign, and also when it should but cannot (no usable key,
     * the key outside its validity window, a vault failure, a signature that does
     * not verify against the declared public key). That case is logged at ERROR:
     * the entry then goes out unsigned, and peers that require verification
     * withhold it.
     */
    public TranscriptEntry sign(GroupConversation gc, TranscriptEntry entry) {
        if (agentStore == null || agentSigningService == null || entry.content() == null || entry.speakerAgentId() == null) {
            return entry;
        }
        String agentId = entry.speakerAgentId();
        try {
            // Null-safe: getCurrentResourceId returns null for an agent with no
            // current version on the PostgreSQL adapter.
            var resourceId = agentStore.getCurrentResourceId(agentId);
            if (resourceId == null) {
                LOGGER.debugf("No current version for agent '%s' — sending unsigned", LogSanitizer.sanitize(agentId));
                return entry;
            }
            var config = agentStore.read(agentId, resourceId.getVersion());
            if (config.getSecurity() == null || !config.getSecurity().isSignInterAgentMessages()) {
                return entry;
            }

            AgentIdentity identity = config.getIdentity();
            int keyVersion = identity != null ? identity.signingKeyVersion() : 0;
            String publicKey = identity != null ? identity.getKeyForVersion(keyVersion) : null;
            if (publicKey == null) {
                return unsignedBecause(entry, "it declares no public key of version " + keyVersion);
            }
            if (gc.getId() == null) {
                return unsignedBecause(entry, "the group conversation has no id to bind the signature to");
            }
            var envelope = SignedEnvelope.forSigning(agentId, gc.getGroupId(), signedPayload(gc.getId(), entry, keyVersion));
            if (!keyValidAt(identity, keyVersion, envelope.timestampMs())) {
                return unsignedBecause(entry, "its newest key, version " + keyVersion + ", is outside its validity window");
            }
            var signed = agentSigningService.signEnvelope(defaultTenantId, agentId, envelope, keyVersion);
            // Never store a signature peers could not verify: the vault's private key
            // must match the public key the agent declares for this version.
            if (!agentSigningService.verifyEnvelope(signed, publicKey)) {
                return unsignedBecause(entry, "the vault's key for version " + keyVersion + " does not match the declared public key");
            }
            return new TranscriptEntry(entry.speakerAgentId(), entry.speakerDisplayName(), entry.content(), entry.phaseIndex(),
                    entry.phaseName(), entry.type(), entry.timestamp(), entry.errorReason(), entry.targetAgentId(),
                    signed.signature(), signed.nonce(), signed.timestampMs(), signed.keyVersion());
        } catch (ResourceNotFoundException e) {
            LOGGER.debugf("Agent '%s' not found — sending unsigned", LogSanitizer.sanitize(agentId));
            return entry;
        } catch (Exception e) {
            // Also reached when the agent's own config cannot be read, so the message does
            // not claim the agent is configured to sign.
            LOGGER.errorf("Could not sign a group message of agent '%s' (%s) — sending it unsigned; "
                    + "peers that require verification will withhold this entry",
                    LogSanitizer.sanitize(agentId), e.getMessage());
            return entry;
        }
    }

    /**
     * {@code transcript} as the agent {@code receiverAgentId} may see it. Unchanged
     * when the receiver does not require peer verification; otherwise a copy in
     * which every agent-authored entry that does not verify is replaced, in place,
     * by a {@code [withheld: ...]} placeholder (see {@link #isWithheld}). In place,
     * so transcript indices (which window summaries are keyed on) stay aligned.
     * <p>
     * Fails closed: if the receiver's own configuration cannot be read, it is
     * treated as requiring verification.
     */
    public List<TranscriptEntry> visibleTo(String receiverAgentId, GroupConversation gc, List<TranscriptEntry> transcript) {
        if (agentStore == null || agentSigningService == null || !requiresPeerVerification(receiverAgentId)) {
            return transcript;
        }
        List<TranscriptEntry> snapshot;
        synchronized (transcript) {
            snapshot = List.copyOf(transcript);
        }
        List<Verdict> verdicts = verify(gc, snapshot);
        List<TranscriptEntry> visible = new ArrayList<>(snapshot.size());
        for (int i = 0; i < snapshot.size(); i++) {
            TranscriptEntry entry = snapshot.get(i);
            Verdict verdict = verdicts.get(i);
            if (verdict.trusted()) {
                visible.add(entry);
            } else {
                report(receiverAgentId, gc, i, entry, verdict);
                visible.add(withheld(entry, verdict));
            }
        }
        return List.copyOf(visible);
    }

    /** Whether {@code entry} is a placeholder {@link #visibleTo} put in. */
    public static boolean isWithheld(TranscriptEntry entry) {
        return entry != null && WITHHELD_MARKER.equals(entry.errorReason());
    }

    /**
     * One verdict per entry of {@code transcript}, in order. Every entry is checked
     * on every call: a key revoked mid-discussion (its {@code validUntilMs} moved
     * into the past) takes effect on the next turn.
     */
    List<Verdict> verify(GroupConversation gc, List<TranscriptEntry> transcript) {
        Map<String, Speaker> speakers = new HashMap<>();
        Set<String> verifiedNonces = new HashSet<>();
        List<Verdict> verdicts = new ArrayList<>(transcript.size());
        for (TranscriptEntry entry : transcript) {
            verdicts.add(verdict(gc, entry, speakers, verifiedNonces));
        }
        return verdicts;
    }

    private Verdict verdict(GroupConversation gc, TranscriptEntry entry, Map<String, Speaker> speakers, Set<String> verifiedNonces) {
        String speakerId = entry.speakerAgentId();
        if (entry.content() == null || speakerId == null || ENGINE_SPEAKERS.contains(speakerId)) {
            return Verdict.NOT_AGENT_AUTHORED;
        }
        Speaker speaker = speakers.computeIfAbsent(speakerId, this::lookUpSpeaker);
        if (!speaker.isAgent()) {
            return Verdict.NOT_AGENT_AUTHORED;
        }
        if (!entry.hasEnvelopeData() || entry.signatureKeyVersion() == null) {
            return Verdict.UNSIGNED;
        }
        int keyVersion = entry.signatureKeyVersion();
        String publicKey = speaker.identity() != null ? speaker.identity().getKeyForVersion(keyVersion) : null;
        if (publicKey == null || gc.getId() == null) {
            return Verdict.UNKNOWN_KEY;
        }
        long signedAt = entry.signatureTimestampMs();
        if (!keyValidAt(speaker.identity(), keyVersion, signedAt)
                || gc.getCreated() != null && signedAt < gc.getCreated().toEpochMilli() - CLOCK_SKEW_MS) {
            return Verdict.OUT_OF_WINDOW;
        }
        var envelope = new SignedEnvelope(speakerId, gc.getGroupId(), signedPayload(gc.getId(), entry, keyVersion),
                entry.signatureNonce(), signedAt, entry.signature(), keyVersion);
        if (!agentSigningService.verifyEnvelope(envelope, publicKey)) {
            return Verdict.BAD_SIGNATURE;
        }
        // The first verified occurrence of a nonce wins, so a forged entry carrying
        // a genuine entry's nonce cannot get the genuine one withheld.
        return verifiedNonces.add(entry.signatureNonce()) ? Verdict.VERIFIED : Verdict.DUPLICATE;
    }

    /**
     * The speaker as the agent store knows it. An id that names no agent (a human
     * member, a nested group, a reviewer) is not agent-authored; an agent whose
     * configuration cannot be read is, with no key, so its entries are withheld.
     */
    private Speaker lookUpSpeaker(String speakerId) {
        try {
            var resourceId = agentStore.getCurrentResourceId(speakerId);
            if (resourceId == null) {
                return Speaker.NOT_AN_AGENT;
            }
            return new Speaker(true, agentStore.read(speakerId, resourceId.getVersion()).getIdentity());
        } catch (ResourceNotFoundException e) {
            return Speaker.NOT_AN_AGENT;
        } catch (Exception e) {
            LOGGER.warnf("Could not read agent '%s' to verify its group messages: %s", LogSanitizer.sanitize(speakerId), e.getMessage());
            return new Speaker(true, null);
        }
    }

    private record Speaker(boolean isAgent, AgentIdentity identity) {
        static final Speaker NOT_AN_AGENT = new Speaker(false, null);
    }

    private boolean requiresPeerVerification(String receiverAgentId) {
        try {
            var resourceId = agentStore.getCurrentResourceId(receiverAgentId);
            if (resourceId == null) {
                return false;
            }
            var security = agentStore.read(receiverAgentId, resourceId.getVersion()).getSecurity();
            return security != null && security.isRequirePeerVerification();
        } catch (ResourceNotFoundException e) {
            return false;
        } catch (Exception e) {
            LOGGER.warnf("Could not read agent '%s' to learn whether it requires peer verification (%s) — "
                    + "withholding every message that does not verify", LogSanitizer.sanitize(receiverAgentId), e.getMessage());
            return true;
        }
    }

    /**
     * The payload a transcript entry's signature covers. Signing and verification
     * both build it here, from the entry itself, so the two cannot drift apart. The
     * key version is part of it because {@link SignedEnvelope#canonicalForm()}
     * leaves the envelope's own {@code keyVersion} out.
     */
    private static Map<String, Object> signedPayload(String conversationId, TranscriptEntry entry, int keyVersion) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("conversationId", conversationId);
        payload.put("phaseIndex", entry.phaseIndex());
        payload.put("keyVersion", keyVersion);
        payload.put("content", entry.content());
        putIfPresent(payload, "phase", entry.phaseName());
        putIfPresent(payload, "speakerName", entry.speakerDisplayName());
        putIfPresent(payload, "targetAgentId", entry.targetAgentId());
        return payload;
    }

    private static void putIfPresent(Map<String, Object> payload, String key, String value) {
        if (value != null) {
            payload.put(key, value);
        }
    }

    /**
     * Whether key {@code version} may sign at {@code epochMs}: its window, when the
     * agent declares one for it ({@code identity.keys}); the legacy unversioned key
     * has none. The start of the window tolerates {@link #CLOCK_SKEW_MS}; the end
     * does not, so an expiry set to revoke a key holds to the millisecond.
     */
    private static boolean keyValidAt(AgentIdentity identity, int version, long epochMs) {
        if (identity.getKeys() == null) {
            return true;
        }
        return identity.getKeys().stream()
                .filter(key -> key.version() == version)
                .findFirst()
                .map(key -> withinWindow(key, epochMs))
                .orElse(true);
    }

    private static boolean withinWindow(AgentPublicKey key, long epochMs) {
        return epochMs + CLOCK_SKEW_MS >= key.validFromMs() && (key.validUntilMs() == 0 || epochMs <= key.validUntilMs());
    }

    private static TranscriptEntry withheld(TranscriptEntry entry, Verdict verdict) {
        return new TranscriptEntry(entry.speakerAgentId(), entry.speakerDisplayName(),
                "[withheld: this message could not be verified, because " + verdict.reason + "]",
                entry.phaseIndex(), entry.phaseName(), entry.type(), entry.timestamp(), WITHHELD_MARKER, entry.targetAgentId());
    }

    private static TranscriptEntry unsignedBecause(TranscriptEntry entry, String why) {
        LOGGER.errorf("Agent '%s' is configured to sign its group messages but cannot: %s — sending unsigned; "
                + "peers that require verification will withhold this entry", LogSanitizer.sanitize(entry.speakerAgentId()), why);
        return entry;
    }

    /**
     * Logs a withheld entry at ERROR the first time it is withheld in a
     * conversation, and at DEBUG after that: every member that requires
     * verification sees the same entry on every turn.
     */
    private void report(String receiverAgentId, GroupConversation gc, int index, TranscriptEntry entry, Verdict verdict) {
        boolean first = gc.getId() == null
                || reported.computeIfAbsent(gc.getId(), ignored -> ConcurrentHashMap.newKeySet()).add(index + ":" + verdict);
        String message = "Withheld transcript entry %d from agent '%s' in group conversation '%s' from receiver '%s', "
                + "which requires peer verification: %s (keyV=%s, nonce=%s)";
        Object[] args = {index, LogSanitizer.sanitize(entry.speakerAgentId()), LogSanitizer.sanitize(gc.getId()),
                LogSanitizer.sanitize(receiverAgentId), verdict, entry.signatureKeyVersion(), LogSanitizer.sanitize(entry.signatureNonce())};
        if (first) {
            LOGGER.errorf(message, args);
        } else {
            LOGGER.debugf(message, args);
        }
    }

    /**
     * Drops what was logged for a group conversation. Called when a discussion leg
     * ends (outside an HITL pause, which keeps it so a resume does not log the same
     * entries again) so the map does not grow forever.
     */
    public void forgetConversation(String gcId) {
        reported.remove(gcId);
    }
}
