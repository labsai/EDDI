/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.agents.AgentConfigurationCache;
import ai.labs.eddi.configs.agents.AgentSigningService;
import ai.labs.eddi.configs.agents.IAgentStore;
import ai.labs.eddi.configs.agents.crypto.AgentPublicKey;
import ai.labs.eddi.configs.agents.crypto.SignedEnvelope;
import ai.labs.eddi.configs.agents.model.AgentConfiguration;
import ai.labs.eddi.configs.agents.model.AgentConfiguration.AgentIdentity;
import ai.labs.eddi.configs.groups.IAgentGroupStore;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.MemberType;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.configs.groups.model.SharedTaskList.TaskItem;
import ai.labs.eddi.configs.groups.mongo.AgentGroupStore;
import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.engine.internal.GroupConversationService;
import ai.labs.eddi.utils.LogSanitizer;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Signs the group-transcript entries of agents with
 * {@code security.signInterAgentMessages}, and decides which entries an agent
 * with {@code security.requirePeerVerification} may see.
 * <p>
 * <b>What a signature covers.</b> The Ed25519 signature is over the canonical
 * form of a {@link SignedEnvelope} whose sender is the speaking agent, whose
 * recipient is the group, and whose payload binds the entry to this
 * conversation and to its place in it: the group conversation id, the round,
 * the phase index and name, the entry type, the content, the speaker's display
 * name, the target agent (if any), the error reason (if any) and the key
 * version. A signed entry copied into another conversation, moved to another
 * phase, or moved into another round no longer verifies. Moving it between
 * repeats of one phase within a round is not detected, and a copy inside the
 * same phase verifies, so a nonce that already appeared earlier in the
 * transcript is rejected as a duplicate.
 * <p>
 * <b>Fail-closed.</b> For a receiver that requires peer verification,
 * {@link #visibleTo} replaces every entry that another agent authored and that
 * does not verify (unsigned, tampered, copied, moved to another round,
 * duplicated, signed with an unknown or revoked key or with a key outside its
 * validity window) by a {@code [withheld: ...]} placeholder, before the
 * receiver's prompt and context are built. Every prompt the engine composes for
 * an agent reads the transcript through a {@link View} from here: a phase turn,
 * a role turn (judge, dissenter, facilitator, task executor, verifier, bidder),
 * a follow-up and a nested group's question; so do the task records it quotes
 * ({@link TaskRecordView}) and the records it keeps for every reader (the
 * negotiation table, the vote tally, a retrospective's lessons; see
 * {@link #recordView}). Entries no agent authored (the user's question, a human
 * member's turn, a reviewer's edit, the engine's own notes, a nested group's
 * answer) carry no signature and pass through, but under a name that cannot
 * pass for a member agent's: signatures authenticate agents, not the engine's
 * own record. A synthesis under the engine's or the user's name is withheld:
 * the engine never records one there. The receiver's own entries are checked
 * like a peer's when it signs; when it does not, it has nothing to check them
 * against and they pass as they are.
 * <p>
 * Holds only caches: agent configurations by version (immutable), the outcome
 * of each Ed25519 check (a pure function of key, signature and signed bytes),
 * each receiver's last-read verification setting, and which withheld entries
 * were already logged. None of them changes a verdict's rules.
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
    /**
     * U+FFFD, as a number: the formatter would turn an escape into the raw
     * character.
     */
    private static final char REPLACEMENT_CHARACTER = (char) 0xFFFD;
    private static final int MAX_CACHED_ENTRIES = 10_000;
    private static final Duration CACHE_EXPIRY = Duration.ofHours(24);

    private final IAgentStore agentStore;
    private final AgentConfigurationCache agentConfigs;
    private final AgentSigningService agentSigningService;
    private final String defaultTenantId;
    private final IAgentGroupStore groupStore;

    /**
     * Outcome of an Ed25519 check by the SHA-256 of what was checked (public key,
     * signature, signed bytes), so each entry is verified once, not on every turn
     * of every verifying member.
     */
    private final Cache<String, Boolean> signatureChecks = boundedCache();

    /**
     * Each receiver's verification setting as last read, used only when its
     * configuration cannot be read.
     */
    private final Cache<String, Boolean> lastKnownRequirement = boundedCache();

    /** Group conversation id to the withheld entries already logged at ERROR. */
    private final Cache<String, Set<String>> reported = boundedCache();

    /**
     * A guard that reads no nested group: a group id as a reader requires nothing.
     */
    public GroupSigningGuard(IAgentStore agentStore, AgentSigningService agentSigningService, String defaultTenantId) {
        this(agentStore, agentSigningService, defaultTenantId, null);
    }

    /**
     * @param groupStore
     *            where a nested group (a {@code GROUP} member, which reads the
     *            parent transcript as its question) is read, to learn how strictly
     *            it reads: as its strictest agent
     */
    public GroupSigningGuard(IAgentStore agentStore, AgentSigningService agentSigningService, String defaultTenantId,
            IAgentGroupStore groupStore) {
        this.agentStore = agentStore;
        this.agentConfigs = new AgentConfigurationCache(agentStore);
        this.agentSigningService = agentSigningService;
        this.defaultTenantId = defaultTenantId;
        this.groupStore = groupStore;
    }

    /**
     * The outcome of checking one transcript entry. Only trusted verdicts reach a
     * receiver that requires peer verification unchanged.
     */
    enum Verdict {
        VERIFIED(null), NOT_AGENT_AUTHORED(null), RECEIVERS_OWN(null), UNSIGNED("it carries no signature"), UNKNOWN_KEY(
                "the speaker declares no readable key of the version it was signed with"), REVOKED(
                        "the key it was signed with has been revoked"), OUT_OF_WINDOW(
                                "it was signed outside its key's validity window or before this conversation began"), BAD_SIGNATURE(
                                        "its signature does not match its content, this conversation or its position in it"), WRONG_ROUND(
                                                "it was signed in another round of this discussion"), DUPLICATE(
                                                        "its signature already appears earlier in this conversation"), NOT_IN_TRANSCRIPT(
                                                                "it is not part of this conversation's transcript"), ENGINE_SYNTHESIS(
                                                                        "it is a synthesis under the engine's or the user's name, "
                                                                                + "under which none is ever recorded");

        private final String reason;

        Verdict(String reason) {
            this.reason = reason;
        }

        boolean trusted() {
            return reason == null;
        }
    }

    /**
     * Whether a receiver requires peer verification. {@link #UNKNOWN}: its
     * configuration cannot be read and this node has never read it.
     */
    enum Requirement {
        NOT_REQUIRED, REQUIRED, UNKNOWN
    }

    /**
     * What a reader may see of a transcript, and whether it asked to see only
     * verified entries. Every prompt the engine composes for an agent, and every
     * record it keeps from the transcript, quotes the transcript through one of
     * these: {@link #transcript()} as a whole, or a stored entry through
     * {@link #of} and {@link #trusts}.
     */
    static final class View {
        private final Requirement requirement;
        private final List<TranscriptEntry> transcript;
        private final List<TranscriptEntry> stored;
        /**
         * Each stored entry's position, by identity: a copy of a signed entry is equal
         * to it but is a duplicate. {@code null} when nothing was filtered.
         */
        private final Map<TranscriptEntry, Integer> positions;
        private final List<Verdict> verdicts;
        /**
         * Whether an agent signs verifiably; {@code null} when nothing was filtered.
         */
        private final Predicate<String> signers;

        /** A view that shows {@code transcript} as it is. */
        View(Requirement requirement, List<TranscriptEntry> transcript) {
            this.requirement = requirement;
            this.transcript = transcript;
            this.stored = transcript;
            this.positions = null;
            this.verdicts = null;
            this.signers = null;
        }

        private View(Requirement requirement, List<TranscriptEntry> stored, List<TranscriptEntry> visible, List<Verdict> verdicts,
                Predicate<String> signers) {
            this.requirement = requirement;
            this.transcript = visible;
            this.stored = stored;
            this.verdicts = verdicts;
            this.signers = signers;
            this.positions = new IdentityHashMap<>(stored.size());
            for (int i = 0; i < stored.size(); i++) {
                positions.putIfAbsent(stored.get(i), i);
            }
        }

        Requirement requirement() {
            return requirement;
        }

        /** The whole transcript as the reader may see it. */
        List<TranscriptEntry> transcript() {
            return transcript;
        }

        /** The transcript this view was taken of, as stored. */
        List<TranscriptEntry> stored() {
            return stored;
        }

        /** Whether the reader sees only what verifies (or its setting is unknown). */
        boolean verifying() {
            return requirement != Requirement.NOT_REQUIRED;
        }

        /**
         * {@code stored} as the reader may see it: itself, relabelled, or a
         * {@code [withheld: ...]} placeholder. An entry that is not part of the
         * transcript this view was taken of is withheld.
         */
        TranscriptEntry of(TranscriptEntry stored) {
            if (positions == null || stored == null) {
                return stored;
            }
            Integer position = positions.get(stored);
            return position != null ? transcript.get(position) : withheld(stored, Verdict.NOT_IN_TRANSCRIPT);
        }

        /** Each of {@code stored} as the reader may see it, in order. */
        List<TranscriptEntry> of(List<TranscriptEntry> stored) {
            return stored == null || positions == null ? stored : stored.stream().map(this::of).toList();
        }

        /**
         * Whether the reader may take {@code stored}'s text as what its speaker wrote:
         * it verified, it is the reader's own, or no agent wrote it.
         */
        boolean trusts(TranscriptEntry stored) {
            if (positions == null) {
                return true;
            }
            Integer position = stored != null ? positions.get(stored) : null;
            return position != null && verdicts.get(position).trusted();
        }

        /**
         * Whether the reader may take text the engine recorded for agent
         * {@code agentId} outside the transcript (a task it filed with its task tool)
         * as that agent's: the agent signs, with a key that is declared, not revoked
         * and inside its validity window now, so the reader would verify what it writes
         * to the discussion.
         */
        boolean vouchesFor(String agentId) {
            return signers == null || agentId != null && signers.test(agentId);
        }
    }

    /**
     * Signs {@code entry} if its speaker has
     * {@code security.signInterAgentMessages}, and returns it with the signature,
     * nonce, timestamp, key version and round set. Unpaired UTF-16 surrogates in
     * its text fields become U+FFFD first, so the stored entry is exactly what was
     * signed on every store. Returns {@code entry} unchanged when the speaker does
     * not sign, and also when it should but cannot (no usable key, the key revoked
     * or outside its validity window, a vault failure, a signature that does not
     * verify against the declared public key). That case is logged at ERROR: the
     * entry then goes out unsigned, and peers that require verification withhold
     * it.
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
            var config = agentConfigs.read(agentId, resourceId.getVersion());
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
            if (isRevoked(identity, keyVersion)) {
                return unsignedBecause(entry, "its newest key, version " + keyVersion + ", has been revoked");
            }
            var wellFormed = new TranscriptEntry(agentId, wellFormed(entry.speakerDisplayName()), wellFormed(entry.content()),
                    entry.phaseIndex(), wellFormed(entry.phaseName()), entry.type(), entry.timestamp(), wellFormed(entry.errorReason()),
                    wellFormed(entry.targetAgentId()));
            int round = gc.getRound();
            var envelope = SignedEnvelope.forSigning(agentId, gc.getGroupId(), signedPayload(gc.getId(), wellFormed, keyVersion, round));
            if (!keyValidAt(identity, keyVersion, envelope.timestampMs())) {
                return unsignedBecause(entry, "its newest key, version " + keyVersion + ", is outside its validity window");
            }
            if (hasUnpairedSurrogate(envelope.canonicalForm())) {
                return unsignedBecause(entry, "an id it would be bound to is not well-formed text");
            }
            var signed = agentSigningService.signEnvelope(defaultTenantId, agentId, envelope, keyVersion);
            // Never store a signature peers could not verify: the vault's private key
            // must match the public key the agent declares for this version.
            if (!agentSigningService.verifyEnvelope(signed, publicKey)) {
                return unsignedBecause(entry, "the vault's key for version " + keyVersion + " does not match the declared public key");
            }
            return new TranscriptEntry(agentId, wellFormed.speakerDisplayName(), wellFormed.content(), wellFormed.phaseIndex(),
                    wellFormed.phaseName(), wellFormed.type(), wellFormed.timestamp(), wellFormed.errorReason(), wellFormed.targetAgentId(),
                    signed.signature(), signed.nonce(), signed.timestampMs(), signed.keyVersion(), round);
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
     * {@code transcript} as the agent {@code receiverAgentId} may see it; see
     * {@link #viewFor}.
     */
    public List<TranscriptEntry> visibleTo(String receiverAgentId, GroupConversation gc, List<TranscriptEntry> transcript) {
        return viewFor(receiverAgentId, gc, transcript).transcript();
    }

    /**
     * {@code transcript} as the agent {@code receiverAgentId} may see it. Unchanged
     * when the receiver does not require peer verification. Otherwise a copy in
     * which every agent-authored entry that must verify and does not (another
     * agent's, or its own when it signs) is replaced, in place, by a
     * {@code [withheld: ...]} placeholder (see {@link #isWithheld}), and every
     * entry no agent authored is renamed so it cannot pass for a member agent's. In
     * place, so transcript indices (which window summaries are keyed on) stay
     * aligned.
     * <p>
     * When the receiver's configuration cannot be read, the setting this node last
     * read for it applies. If there is none, the requirement is
     * {@link Requirement#UNKNOWN} and the transcript is filtered as for a receiver
     * that requires verification; the caller decides whether to run the turn at
     * all.
     */
    View viewFor(String receiverAgentId, GroupConversation gc, List<TranscriptEntry> transcript) {
        if (agentStore == null || agentSigningService == null) {
            return new View(Requirement.NOT_REQUIRED, transcript);
        }
        Requirement requirement = requirementOf(receiverAgentId);
        if (requirement == Requirement.NOT_REQUIRED) {
            return new View(requirement, transcript);
        }
        return filtered(requirement, receiverAgentId, "receiver '" + receiverAgentId + "'", gc, snapshotOf(transcript));
    }

    /**
     * The stored transcript of {@code gc} as {@code readerId} may see it: an agent
     * member, or a nested group, which reads as strictly as its strictest member.
     * {@code guard} is {@code null} where an engine was wired without one, which
     * shows every entry.
     */
    static View viewOf(GroupSigningGuard guard, String readerId, GroupConversation gc) {
        List<TranscriptEntry> snapshot = snapshotOf(gc.getTranscript());
        return guard != null ? guard.viewFor(readerId, gc, snapshot) : new View(Requirement.NOT_REQUIRED, snapshot);
    }

    /**
     * The stored transcript of {@code gc} as the engine's own records may count it:
     * the negotiation table, the vote tally and the lessons a retrospective stores,
     * which every reader then sees. When any of {@code readerIds} requires peer
     * verification (or its setting is unknown), only entries that verify for every
     * reader count, so no reader's own entries are exempt; otherwise every entry
     * does.
     */
    static View recordView(GroupSigningGuard guard, GroupConversation gc, Collection<String> readerIds) {
        List<TranscriptEntry> snapshot = snapshotOf(gc.getTranscript());
        if (guard == null || guard.agentStore == null || guard.agentSigningService == null) {
            return new View(Requirement.NOT_REQUIRED, snapshot);
        }
        Requirement strictest = Requirement.NOT_REQUIRED;
        for (String readerId : readerIds) {
            Requirement requirement = guard.requirementOf(readerId);
            if (requirement == Requirement.REQUIRED) {
                strictest = requirement;
                break;
            }
            if (requirement == Requirement.UNKNOWN) {
                strictest = requirement;
            }
        }
        if (strictest == Requirement.NOT_REQUIRED) {
            return new View(strictest, snapshot);
        }
        return guard.filtered(strictest, null, "the group's own records", gc, snapshot);
    }

    /**
     * {@code entries} (a slice of {@code gc}'s transcript) as the engine's records
     * may count them (see {@link #recordView}): each entry, or {@code null} in
     * place of one that does not verify for every reader, so positions hold.
     */
    public static List<TranscriptEntry> recordedEntries(GroupSigningGuard guard, GroupConversation gc, AgentGroupConfiguration config,
                                                        List<TranscriptEntry> entries) {
        View records = recordView(guard, gc, readersOf(config, gc));
        if (entries == null || !records.verifying()) {
            return entries;
        }
        return entries.stream().map(entry -> records.trusts(entry) ? entry : null).toList();
    }

    /**
     * The subject of each of {@code tasks} (from {@code gc}'s task list) as the
     * agent {@code readerId} may be shown it, in order: as stored, or
     * {@link TaskRecordView#WITHHELD} (see {@link TaskRecordView#showsTask}). What
     * a member's task tool lists, so it shows the member no task text the engine's
     * own prompts withhold from it.
     */
    public static List<String> taskSubjectsSeenBy(GroupSigningGuard guard, String readerId, GroupConversation gc, List<TaskItem> tasks) {
        var records = TaskRecordView.of(viewOf(guard, readerId, gc), readerId, gc, null);
        return tasks.stream().map(task -> records.showsTask(task) ? task.subject() : TaskRecordView.WITHHELD).toList();
    }

    /**
     * {@code task} (from {@code gc}'s task list) as the engine's records may carry
     * it beyond this discussion, for instance into a standing team's backlog: see
     * {@link #recordView} and {@link TaskRecordView}. {@code config} is the group's
     * configuration; {@code null} means it could not be read, and the task is then
     * counted as for a reader that requires verification. {@code guard} is
     * {@code null} where none was wired, which carries the task as stored.
     */
    public static TaskItem recordedTask(GroupSigningGuard guard, GroupConversation gc, AgentGroupConfiguration config, TaskItem task) {
        if (guard == null || task == null) {
            return task;
        }
        View records = config != null
                ? recordView(guard, gc, readersOf(config, gc))
                : guard.agentStore == null || guard.agentSigningService == null
                        ? new View(Requirement.NOT_REQUIRED, snapshotOf(gc.getTranscript()))
                        : guard.filtered(Requirement.UNKNOWN, null, "the group's own records", gc, snapshotOf(gc.getTranscript()));
        return TaskRecordView.of(records, null, gc, config).seen(task);
    }

    /**
     * Everyone whose verification setting decides what the engine's records count:
     * the members (recruits included, humans excluded), the moderator and the
     * facilitator.
     */
    static Set<String> readersOf(AgentGroupConfiguration config, GroupConversation gc) {
        Set<String> readers = new LinkedHashSet<>();
        if (config == null) {
            return readers;
        }
        for (GroupMember member : GroupConversationService.rosterWithRecruits(config, gc)) {
            if (member != null && member.agentId() != null && member.memberType() != MemberType.HUMAN) {
                readers.add(member.agentId());
            }
        }
        if (config.getModeratorAgentId() != null && !config.getModeratorAgentId().isBlank()) {
            readers.add(config.getModeratorAgentId());
        }
        if (config.getFacilitator() != null && config.getFacilitator().agentId() != null) {
            readers.add(config.getFacilitator().agentId());
        }
        return readers;
    }

    private View filtered(Requirement requirement, String receiverAgentId, String readerLabel, GroupConversation gc,
                          List<TranscriptEntry> snapshot) {
        List<Verdict> verdicts = verify(gc, snapshot, receiverAgentId);
        List<TranscriptEntry> visible = new ArrayList<>(snapshot.size());
        for (int i = 0; i < snapshot.size(); i++) {
            TranscriptEntry entry = snapshot.get(i);
            Verdict verdict = verdicts.get(i);
            if (verdict == Verdict.NOT_AGENT_AUTHORED) {
                visible.add(asNonAgent(entry));
            } else if (verdict.trusted()) {
                visible.add(entry);
            } else {
                report(readerLabel, gc, i, entry, verdict);
                visible.add(withheld(entry, verdict));
            }
        }
        Map<String, Boolean> signs = new ConcurrentHashMap<>();
        return new View(requirement, snapshot, List.copyOf(visible), verdicts,
                agentId -> signs.computeIfAbsent(agentId, this::signsVerifiably));
    }

    private static List<TranscriptEntry> snapshotOf(List<TranscriptEntry> transcript) {
        synchronized (transcript) {
            return List.copyOf(transcript);
        }
    }

    /**
     * Whether the agent {@code agentId} sees only what verifies: it requires peer
     * verification, or its setting cannot be read (and was never read on this
     * node). A tool that would hand it text no signature covers is not given to it.
     */
    public boolean requiresVerification(String agentId) {
        return agentStore != null && agentSigningService != null && requirementOf(agentId) != Requirement.NOT_REQUIRED;
    }

    /** Whether {@code entry} is a placeholder {@link #visibleTo} put in. */
    public static boolean isWithheld(TranscriptEntry entry) {
        return entry != null && WITHHELD_MARKER.equals(entry.errorReason());
    }

    /**
     * One verdict per entry of {@code transcript}, in order, as the agent
     * {@code receiverAgentId} is to see them. Every entry is checked on every call
     * (the Ed25519 check itself is remembered): a key revoked mid-discussion takes
     * effect on the next turn.
     */
    List<Verdict> verify(GroupConversation gc, List<TranscriptEntry> transcript, String receiverAgentId) {
        var pass = new Pass();
        List<Verdict> verdicts = new ArrayList<>(transcript.size());
        for (int i = 0; i < transcript.size(); i++) {
            verdicts.add(verdict(gc, i, transcript.get(i), receiverAgentId, pass));
        }
        return verdicts;
    }

    /** What one {@link #verify} pass has learned so far. */
    private static final class Pass {
        final Map<String, Speaker> speakers = new HashMap<>();
        final Set<String> verifiedNonces = new HashSet<>();
        int highestRound;
    }

    private Verdict verdict(GroupConversation gc, int index, TranscriptEntry entry, String receiverAgentId, Pass pass) {
        String speakerId = entry.speakerAgentId();
        if (entry.content() == null) {
            return Verdict.NOT_AGENT_AUTHORED;
        }
        if (speakerId == null || ENGINE_SPEAKERS.contains(speakerId)) {
            // The engine records no synthesis under its own name or the user's, and a
            // synthesis is looked up by type and quoted as the group's conclusion.
            return entry.type() == TranscriptEntryType.SYNTHESIS ? Verdict.ENGINE_SYNTHESIS : Verdict.NOT_AGENT_AUTHORED;
        }
        Speaker speaker = pass.speakers.computeIfAbsent(speakerId, this::lookUpSpeaker);
        // A receiver that signs has its own entries checked like a peer's, since every
        // honest one is signed: it is quoted them as the answer it revises or defends.
        // One that does not sign has nothing to check them against, and withholding
        // them would only blank that answer.
        if (speakerId.equals(receiverAgentId) && !speaker.signs()) {
            return Verdict.RECEIVERS_OWN;
        }
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
        // Whatever time the entry claims: that time is the signer's own choice, so a
        // leaked key could otherwise date its forgeries before the revocation.
        if (isRevoked(speaker.identity(), keyVersion)) {
            return Verdict.REVOKED;
        }
        long signedAt = entry.signatureTimestampMs();
        if (!keyValidAt(speaker.identity(), keyVersion, signedAt)
                || gc.getCreated() != null && signedAt < gc.getCreated().toEpochMilli() - CLOCK_SKEW_MS) {
            return Verdict.OUT_OF_WINDOW;
        }
        Integer round = entry.signatureRound();
        if (round == null || !signatureMatches(new SignedEnvelope(speakerId, gc.getGroupId(),
                signedPayload(gc.getId(), entry, keyVersion, round), entry.signatureNonce(), signedAt, entry.signature(), keyVersion),
                publicKey)) {
            return Verdict.BAD_SIGNATURE;
        }
        // Rounds only grow along the transcript, and the current round's entries start
        // at roundStartTranscriptIndex: an entry shown in another round's place is
        // shown as an answer to a question it never answered.
        boolean inCurrentRound = index >= gc.getRoundStartTranscriptIndex();
        if (round < pass.highestRound || (inCurrentRound ? round != gc.getRound() : round >= gc.getRound())) {
            return Verdict.WRONG_ROUND;
        }
        // The first verified occurrence of a nonce wins, so a forged entry carrying
        // a genuine entry's nonce cannot get the genuine one withheld.
        if (!pass.verifiedNonces.add(entry.signatureNonce())) {
            return Verdict.DUPLICATE;
        }
        pass.highestRound = round;
        return Verdict.VERIFIED;
    }

    /**
     * Whether {@code publicKey} vouches for {@code envelope}. The outcome is
     * remembered by the SHA-256 of everything it depends on, so a verifying member
     * does not re-run Ed25519 over the whole transcript on every turn. Text that is
     * not well-formed UTF-16 never verifies: its UTF-8 bytes would be ambiguous.
     */
    private boolean signatureMatches(SignedEnvelope envelope, String publicKey) {
        String canonical;
        try {
            canonical = envelope.canonicalForm();
        } catch (Exception e) {
            return false;
        }
        if (hasUnpairedSurrogate(canonical)) {
            return false;
        }
        String checked = sha256(publicKey + "\n" + envelope.signature() + "\n" + canonical);
        Boolean known = signatureChecks.getIfPresent(checked);
        if (known != null) {
            return known;
        }
        boolean matches = agentSigningService.verifyEnvelope(envelope, publicKey);
        signatureChecks.put(checked, matches);
        return matches;
    }

    /**
     * The speaker as the agent store knows it. An id that names no agent (a human
     * member, a nested group, a reviewer) is not agent-authored; an agent whose
     * configuration cannot be read is, with no key, and counts as signing, so its
     * entries are withheld, the receiver's own included.
     */
    private Speaker lookUpSpeaker(String speakerId) {
        try {
            var resourceId = agentStore.getCurrentResourceId(speakerId);
            if (resourceId == null) {
                return Speaker.NOT_AN_AGENT;
            }
            AgentConfiguration config = agentConfigs.read(speakerId, resourceId.getVersion());
            var security = config.getSecurity();
            return new Speaker(true, config.getIdentity(), security != null && security.isSignInterAgentMessages());
        } catch (ResourceNotFoundException e) {
            return Speaker.NOT_AN_AGENT;
        } catch (Exception e) {
            LOGGER.warnf("Could not read agent '%s' to verify its group messages: %s", LogSanitizer.sanitize(speakerId), e.getMessage());
            return new Speaker(true, null, true);
        }
    }

    private record Speaker(boolean isAgent, AgentIdentity identity, boolean signs) {
        static final Speaker NOT_AN_AGENT = new Speaker(false, null, false);
    }

    /**
     * Whether {@code agentId} is an agent that signs with a key that would sign
     * now: declared, not revoked and inside its validity window. An agent whose
     * configuration cannot be read does not.
     */
    private boolean signsVerifiably(String agentId) {
        Speaker speaker = lookUpSpeaker(agentId);
        AgentIdentity identity = speaker.identity();
        if (!speaker.isAgent() || !speaker.signs() || identity == null) {
            return false;
        }
        int keyVersion = identity.signingKeyVersion();
        return identity.getKeyForVersion(keyVersion) != null && !isRevoked(identity, keyVersion)
                && keyValidAt(identity, keyVersion, System.currentTimeMillis());
    }

    private Requirement requirementOf(String receiverAgentId) {
        return requirementOf(receiverAgentId, new HashSet<>());
    }

    private Requirement requirementOf(String receiverAgentId, Set<String> groupsVisited) {
        try {
            var resourceId = agentStore.getCurrentResourceId(receiverAgentId);
            if (resourceId == null) {
                return groupRequirement(receiverAgentId, groupsVisited);
            }
            AgentConfiguration config = agentConfigs.read(receiverAgentId, resourceId.getVersion());
            var security = config.getSecurity();
            boolean required = security != null && security.isRequirePeerVerification();
            lastKnownRequirement.put(receiverAgentId, required);
            return required ? Requirement.REQUIRED : Requirement.NOT_REQUIRED;
        } catch (ResourceNotFoundException e) {
            return groupRequirement(receiverAgentId, groupsVisited);
        } catch (Exception e) {
            Boolean lastKnown = lastKnownRequirement.getIfPresent(receiverAgentId);
            LOGGER.warnf("Could not read agent '%s' to learn whether it requires peer verification (%s) — %s",
                    LogSanitizer.sanitize(receiverAgentId), e.getMessage(),
                    lastKnown != null ? "using the setting last read: " + lastKnown : "the setting was never read on this node");
            if (lastKnown == null) {
                return Requirement.UNKNOWN;
            }
            return lastKnown ? Requirement.REQUIRED : Requirement.NOT_REQUIRED;
        }
    }

    /**
     * The requirement of an id that names no agent: for a nested group, that of its
     * strictest reader as {@link #readersOf} counts them (its agent members, its
     * moderator and its facilitator; groups nested in it included, each visited
     * once); otherwise none. A group that cannot be read is {@code UNKNOWN}.
     */
    private Requirement groupRequirement(String groupId, Set<String> groupsVisited) {
        if (groupStore == null || !groupsVisited.add(groupId)) {
            return Requirement.NOT_REQUIRED;
        }
        AgentGroupConfiguration group;
        try {
            var resourceId = groupStore.getCurrentResourceId(groupId);
            if (resourceId == null) {
                return Requirement.NOT_REQUIRED;
            }
            group = groupStore.read(groupId, resourceId.getVersion());
        } catch (ResourceNotFoundException e) {
            return Requirement.NOT_REQUIRED;
        } catch (Exception e) {
            LOGGER.warnf("Could not read group '%s' to learn whether its agents require peer verification (%s)",
                    LogSanitizer.sanitize(groupId), e.getMessage());
            return Requirement.UNKNOWN;
        }
        Requirement strictest = Requirement.NOT_REQUIRED;
        for (String readerId : readersOf(group, null)) {
            Requirement requirement = requirementOf(readerId, groupsVisited);
            if (requirement == Requirement.REQUIRED) {
                return requirement;
            }
            if (requirement == Requirement.UNKNOWN) {
                strictest = requirement;
            }
        }
        return strictest;
    }

    /**
     * The payload a transcript entry's signature covers. Signing and verification
     * both build it here, from the entry itself, so the two cannot drift apart. The
     * key version is part of it because {@link SignedEnvelope#canonicalForm()}
     * leaves the envelope's own {@code keyVersion} out.
     */
    private static Map<String, Object> signedPayload(String conversationId, TranscriptEntry entry, int keyVersion, int round) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("conversationId", conversationId);
        payload.put("round", round);
        payload.put("phaseIndex", entry.phaseIndex());
        payload.put("keyVersion", keyVersion);
        payload.put("content", entry.content());
        putIfPresent(payload, "type", entry.type() != null ? entry.type().name() : null);
        putIfPresent(payload, "phase", entry.phaseName());
        putIfPresent(payload, "speakerName", entry.speakerDisplayName());
        putIfPresent(payload, "targetAgentId", entry.targetAgentId());
        putIfPresent(payload, "errorReason", entry.errorReason());
        return payload;
    }

    private static void putIfPresent(Map<String, Object> payload, String key, String value) {
        if (value != null) {
            payload.put(key, value);
        }
    }

    /**
     * Whether any declared entry of key {@code version} is revoked. A revoked key
     * vouches for nothing, whatever time an entry claims to have been signed at.
     */
    private static boolean isRevoked(AgentIdentity identity, int version) {
        return identity.getKeys() != null && identity.getKeys().stream().anyMatch(key -> key.version() == version && key.revoked());
    }

    /**
     * Whether key {@code version} may sign at {@code epochMs}: its window, when the
     * agent declares one for it ({@code identity.keys}); the legacy unversioned key
     * has none. The start of the window tolerates {@link #CLOCK_SKEW_MS}; the end
     * does not. The time checked is the one the signer put in the envelope, so the
     * window limits honest use; it does not stop a leaked key, which
     * {@link #isRevoked} does.
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

    /**
     * {@code entry} under a name that cannot be taken for a member agent's (a group
     * save refuses these names for agent members). Which entries must verify is
     * decided by the speaker id, while a prompt names the speaker by its stored
     * display name; without this, a store writer could put any text under an
     * agent's name by giving the entry a non-agent speaker id.
     */
    private static TranscriptEntry asNonAgent(TranscriptEntry entry) {
        if (entry.content() == null) {
            return entry;
        }
        String speakerId = entry.speakerAgentId();
        String name = speakerId == null || ENGINE_SPEAKERS.contains(speakerId)
                ? engineSpeakerName(speakerId)
                : (entry.speakerDisplayName() != null ? entry.speakerDisplayName() : speakerId) + AgentGroupStore.NOT_AN_AGENT_SUFFIX;
        if (name.equals(entry.speakerDisplayName())) {
            return entry;
        }
        return new TranscriptEntry(speakerId, name, entry.content(), entry.phaseIndex(), entry.phaseName(), entry.type(), entry.timestamp(),
                entry.errorReason(), entry.targetAgentId());
    }

    /** The fixed label of the user ({@code user}) or the engine (any other). */
    private static String engineSpeakerName(String speakerId) {
        return "user".equals(speakerId) ? AgentGroupStore.USER_SPEAKER_NAME : AgentGroupStore.SYSTEM_SPEAKER_NAME;
    }

    /**
     * Whether {@code entry} is shown under a name that says no agent wrote it: one
     * {@link #asNonAgent} gives, which a group save refuses for agent members. A
     * view that hides who wrote what (ANONYMOUS scope) keeps that much, so such an
     * entry cannot read like a verified peer's there either.
     */
    static boolean isShownAsNonAgent(TranscriptEntry entry) {
        String name = entry.speakerDisplayName();
        return name != null && (name.equals(AgentGroupStore.SYSTEM_SPEAKER_NAME) || name.equals(AgentGroupStore.USER_SPEAKER_NAME)
                || name.endsWith(AgentGroupStore.NOT_AN_AGENT_SUFFIX));
    }

    /**
     * The placeholder for an entry that did not verify. It keeps what decides where
     * the entry appears (speaker id, phase index, type, target) and drops every
     * text field: the stored display name and phase name are as unverified as the
     * content, so the speaker is named by its agent id (a store-generated id, since
     * an id that names no agent is not withheld but relabelled), or for a synthesis
     * under the engine's or the user's name, by that fixed label.
     */
    private static TranscriptEntry withheld(TranscriptEntry entry, Verdict verdict) {
        String speakerId = entry.speakerAgentId();
        String name = speakerId == null || ENGINE_SPEAKERS.contains(speakerId) ? engineSpeakerName(speakerId) : speakerId;
        return new TranscriptEntry(speakerId, name,
                "[withheld: this message could not be verified, because " + verdict.reason + "]",
                entry.phaseIndex(), null, entry.type(), entry.timestamp(), WITHHELD_MARKER, entry.targetAgentId());
    }

    private static TranscriptEntry unsignedBecause(TranscriptEntry entry, String why) {
        LOGGER.errorf("Agent '%s' is configured to sign its group messages but cannot: %s — sending unsigned; "
                + "peers that require verification will withhold this entry", LogSanitizer.sanitize(entry.speakerAgentId()), why);
        return entry;
    }

    /**
     * {@code text} with every unpaired UTF-16 surrogate replaced by U+FFFD. Java's
     * UTF-8 encoder turns one into {@code ?}, so a text holding one would sign the
     * same bytes as the text with {@code ?}, and would not survive a store that
     * writes real UTF-8.
     */
    static String wellFormed(String text) {
        if (text == null || !hasUnpairedSurrogate(text)) {
            return text;
        }
        var builder = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
                builder.append(c).append(text.charAt(++i));
            } else {
                builder.append(Character.isSurrogate(c) ? REPLACEMENT_CHARACTER : c);
            }
        }
        return builder.toString();
    }

    /**
     * Hex SHA-256 of {@code text}, which {@link #signatureMatches} has checked for
     * unpaired surrogates, so its UTF-8 bytes are unambiguous.
     */
    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static boolean hasUnpairedSurrogate(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
                i++;
            } else if (Character.isSurrogate(c)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Logs a withheld entry at ERROR the first time it is withheld in a
     * conversation, and at DEBUG after that: every member that requires
     * verification sees the same entry on every turn.
     */
    private void report(String reader, GroupConversation gc, int index, TranscriptEntry entry, Verdict verdict) {
        boolean first = gc.getId() == null
                || reported.get(gc.getId(), ignored -> ConcurrentHashMap.newKeySet()).add(index + ":" + verdict);
        String message = "Withheld transcript entry %d from agent '%s' in group conversation '%s' from %s, "
                + "which requires peer verification: %s (keyV=%s, nonce=%s)";
        Object[] args = {index, LogSanitizer.sanitize(entry.speakerAgentId()), LogSanitizer.sanitize(gc.getId()),
                LogSanitizer.sanitize(reader), verdict, entry.signatureKeyVersion(), LogSanitizer.sanitize(entry.signatureNonce())};
        if (first) {
            LOGGER.errorf(message, args);
        } else {
            LOGGER.debugf(message, args);
        }
    }

    /**
     * Drops what was logged for a group conversation. Called when a discussion leg
     * ends (outside an HITL pause, which keeps it so a resume does not log the same
     * entries again). A conversation that pauses here and ends on another node is
     * dropped by size or after a day without use, so the log may repeat one ERROR.
     */
    public void forgetConversation(String gcId) {
        reported.invalidate(gcId);
    }

    private static <V> Cache<String, V> boundedCache() {
        return Caffeine.newBuilder().maximumSize(MAX_CACHED_ENTRIES).expireAfterAccess(CACHE_EXPIRY).build();
    }
}
