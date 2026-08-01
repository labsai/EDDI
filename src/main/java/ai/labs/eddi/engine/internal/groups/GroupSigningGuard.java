/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.agents.AgentSigningService;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.crypto.AgentPublicKey;
import ai.labs.eddi.configs.agents.crypto.NonceCacheService;
import ai.labs.eddi.configs.agents.crypto.SignedEnvelope;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.utils.LogSanitizer;
import org.jboss.logging.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Signs outgoing group-transcript entries and verifies incoming ones when
 * inter-agent signing (Ed25519 envelopes) is configured on the speaking or
 * receiving agent. Extracted from {@code GroupConversationService} (Wave R, R1
 * step 3) as a pure move — no behavior change.
 * <p>
 * Owns {@code lastVerifiedIndex}, the in-JVM cursor of how far each group
 * conversation's transcript has been incrementally peer-verified. This is a
 * cache over the durable transcript, not state — losing it just means the next
 * verification pass re-checks from the start.
 *
 * @author ginccc
 */
public class GroupSigningGuard {

    private static final Logger LOGGER = Logger.getLogger(GroupSigningGuard.class);

    private final IAgentStore agentStore;
    private final AgentSigningService agentSigningService;
    private final NonceCacheService nonceCacheService;
    private final String defaultTenantId;

    private final ConcurrentHashMap<String, Integer> lastVerifiedIndex = new ConcurrentHashMap<>();

    public GroupSigningGuard(IAgentStore agentStore, AgentSigningService agentSigningService,
            NonceCacheService nonceCacheService, String defaultTenantId) {
        this.agentStore = agentStore;
        this.agentSigningService = agentSigningService;
        this.nonceCacheService = nonceCacheService;
        this.defaultTenantId = defaultTenantId;
    }

    /**
     * Result of an attempted signing operation. {@link #UNSIGNED} — every field
     * {@code null} — is returned whenever the message was not signed, whether
     * because signing is not configured, the crypto infrastructure is absent, or
     * self-verification/nonce validation failed. Callers store the fields verbatim
     * on the resulting {@link TranscriptEntry}; an unsigned entry is a legitimate
     * outcome, not an error.
     */
    public record SigningResult(String signature, String nonce, Long timestampMs, Integer keyVersion) {
        public static final SigningResult UNSIGNED = new SigningResult(null, null, null, null);
    }

    /**
     * Sign an outgoing message from {@code agentId} if that agent's config has
     * {@code security.signInterAgentMessages=true}. Self-verifies immediately after
     * signing and discards the signature (falling back to
     * {@link SigningResult#UNSIGNED}) rather than ever storing a broken one.
     */
    public SigningResult signOutgoingMessage(String agentId, String groupId, String response, String phaseName) {
        // Skip signing if crypto infrastructure is not injected
        if (agentStore == null || agentSigningService == null || nonceCacheService == null) {
            return SigningResult.UNSIGNED;
        }
        try {
            var resourceId = agentStore.getCurrentResourceId(agentId);
            var agentConfig = agentStore.read(agentId, resourceId.getVersion());
            if (agentConfig.getSecurity() == null
                    || !agentConfig.getSecurity().isSignInterAgentMessages()
                    || response == null) {
                return SigningResult.UNSIGNED;
            }

            // Create SignedEnvelope with nonce for replay protection
            var envelope = SignedEnvelope.forSigning(
                    agentId, groupId,
                    Map.of("content", response, "phase", phaseName));
            int keyVersion = 0;
            if (agentConfig.getIdentity() != null
                    && agentConfig.getIdentity().getKeys() != null
                    && !agentConfig.getIdentity().getKeys().isEmpty()) {
                keyVersion = agentConfig.getIdentity().getKeys().stream()
                        .mapToInt(AgentPublicKey::version)
                        .max().orElse(0);
            }
            var signedEnvelope = agentSigningService.signEnvelope(
                    defaultTenantId, agentId, envelope, keyVersion);

            // Immediate self-verification: sanity-check the signature.
            // If this fails, the signature is broken — do NOT store it.
            String publicKey = agentConfig.getIdentity() != null
                    ? agentConfig.getIdentity()
                            .getKeyValidAt(signedEnvelope.timestampMs())
                    : null;
            if (publicKey != null) {
                boolean valid = agentSigningService.verifyEnvelope(signedEnvelope, publicKey);
                if (!valid) {
                    LOGGER.errorf("SELF-VERIFY FAILED for agent '%s' "
                            + "— key mismatch or signing error. "
                            + "Falling back to unsigned entry.",
                            agentId);
                    // Fall back to unsigned: do NOT store broken signature
                    signedEnvelope = null;
                }
            }

            // Nonce validation: register nonce to prevent replay.
            // If validation fails (stale/skewed), discard the signature.
            if (signedEnvelope != null) {
                var nonceResult = nonceCacheService.validate(
                        signedEnvelope.nonce(), signedEnvelope.timestampMs());
                if (nonceResult != NonceCacheService.NonceValidation.VALID) {
                    LOGGER.warnf("Nonce validation failed for agent '%s': %s "
                            + "— falling back to unsigned entry",
                            agentId, nonceResult);
                    signedEnvelope = null;
                }
            }

            if (signedEnvelope == null) {
                return SigningResult.UNSIGNED;
            }

            LOGGER.debugf("Signed inter-agent envelope from '%s' "
                    + "(nonce=%s, keyV=%d, sig=%s...)",
                    agentId, signedEnvelope.nonce(),
                    signedEnvelope.keyVersion(),
                    signedEnvelope.signature().length() > 16
                            ? signedEnvelope.signature().substring(0, 16)
                            : signedEnvelope.signature());
            return new SigningResult(signedEnvelope.signature(), signedEnvelope.nonce(),
                    signedEnvelope.timestampMs(), signedEnvelope.keyVersion());
        } catch (Exception sigEx) {
            LOGGER.warnf("Failed to sign message from agent '%s': %s",
                    agentId, sigEx.getMessage());
            return SigningResult.UNSIGNED;
        }
    }

    /**
     * Verify signed transcript entries from prior speakers if the receiving agent
     * has {@code requirePeerVerification=true}.
     * <p>
     * For each signed entry with full envelope data, this method:
     * <ol>
     * <li>Reconstructs the {@link SignedEnvelope} from stored fields</li>
     * <li>Loads the speaker's public key from the agent config</li>
     * <li>Verifies the signature against the canonical envelope form</li>
     * </ol>
     * Invalid signatures are logged as security warnings. This is defense-in-depth:
     * the signing code already self-verifies at creation time, so failures here
     * indicate either key rotation issues or data corruption.
     *
     * @param receivingAgentId
     *            the agent about to receive the transcript
     * @param gc
     *            the group conversation containing the transcript
     */
    public void verifyPriorEntriesIfRequired(String receivingAgentId, GroupConversation gc) {
        // Skip if crypto infrastructure is not injected
        if (agentStore == null || agentSigningService == null) {
            return;
        }
        try {
            var resourceId = agentStore.getCurrentResourceId(receivingAgentId);
            if (resourceId == null) {
                return;
            }
            var receiverConfig = agentStore.read(receivingAgentId, resourceId.getVersion());
            if (receiverConfig.getSecurity() == null
                    || !receiverConfig.getSecurity().isRequirePeerVerification()) {
                return;
            }

            List<TranscriptEntry> transcript = gc.getTranscript();
            int totalEntries = transcript.size();

            // Incremental verification: only verify entries added since last check
            int startIdx = lastVerifiedIndex.getOrDefault(gc.getId(), 0);
            if (startIdx >= totalEntries) {
                return; // Nothing new to verify
            }

            LOGGER.debugf("Peer verification for agent '%s' — verifying entries %d..%d (of %d total)",
                    receivingAgentId, startIdx, totalEntries - 1, totalEntries);

            int verified = 0;
            int failed = 0;
            int unsigned = 0;

            // Cache public keys per speaker to avoid redundant agentStore reads
            Map<String, String> publicKeyCache = new HashMap<>();

            for (int i = startIdx; i < totalEntries; i++) {
                TranscriptEntry entry = transcript.get(i);
                // Skip non-agent entries (user questions, errors, etc.)
                if ("user".equals(entry.speakerAgentId()) || entry.content() == null) {
                    continue;
                }

                if (!entry.hasEnvelopeData()) {
                    unsigned++;
                    LOGGER.warnf("UNSIGNED entry from agent '%s' in group '%s' — "
                            + "peer verification required but entry has no envelope data",
                            entry.speakerAgentId(), LogSanitizer.sanitize(gc.getGroupId()));
                    continue;
                }

                // Reconstruct envelope for verification
                var envelope = new SignedEnvelope(
                        entry.speakerAgentId(), gc.getGroupId(),
                        Map.of("content", entry.content(), "phase", entry.phaseName()),
                        entry.signatureNonce(), entry.signatureTimestampMs(),
                        entry.signature(), entry.signatureKeyVersion());

                // Get speaker's public key (cached per speaker)
                try {
                    String publicKey = publicKeyCache.computeIfAbsent(entry.speakerAgentId(), agentId -> {
                        try {
                            var speakerResourceId = agentStore.getCurrentResourceId(agentId);
                            if (speakerResourceId == null) {
                                return null;
                            }
                            var speakerConfig = agentStore.read(agentId, speakerResourceId.getVersion());
                            return speakerConfig.getIdentity() != null
                                    ? speakerConfig.getIdentity()
                                            .getKeyValidAt(entry.signatureTimestampMs())
                                    : null;
                        } catch (Exception e) {
                            LOGGER.warnf("Error loading public key for agent '%s': %s",
                                    agentId, e.getMessage());
                            return null;
                        }
                    });

                    if (publicKey == null) {
                        LOGGER.warnf("No public key found for agent '%s' — cannot verify signature",
                                entry.speakerAgentId());
                        failed++;
                        continue;
                    }

                    boolean valid = agentSigningService.verifyEnvelope(envelope, publicKey);
                    if (valid) {
                        verified++;
                    } else {
                        failed++;
                        LOGGER.errorf("SIGNATURE VERIFICATION FAILED for entry from agent '%s' "
                                + "(nonce=%s, keyV=%d) — potential tampering or key rotation issue",
                                entry.speakerAgentId(), entry.signatureNonce(),
                                entry.signatureKeyVersion());
                    }
                } catch (Exception e) {
                    failed++;
                    LOGGER.warnf("Error verifying entry from agent '%s': %s",
                            entry.speakerAgentId(), e.getMessage());
                }
            }

            // Update the cursor for this conversation
            lastVerifiedIndex.put(gc.getId(), totalEntries);

            LOGGER.infof("Peer verification for agent '%s': %d verified, %d failed, %d unsigned (range %d..%d)",
                    receivingAgentId, verified, failed, unsigned, startIdx, totalEntries - 1);
        } catch (Exception e) {
            LOGGER.warnf("Peer verification check failed for agent '%s': %s",
                    receivingAgentId, e.getMessage());
        }
    }

    /**
     * Drop the incremental verification cursor for a group conversation. Called
     * when a discussion leg ends (outside an HITL pause, which keeps the cursor so
     * a resume continues where it left off) so the map does not leak entries
     * forever.
     */
    public void forgetConversation(String gcId) {
        lastVerifiedIndex.remove(gcId);
    }
}
