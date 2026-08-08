/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.MemberType;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.PhaseType;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.Concession;
import ai.labs.eddi.configs.groups.model.GroupConversation.DecisionRecord;
import ai.labs.eddi.configs.groups.model.GroupConversation.DecisionType;
import ai.labs.eddi.configs.groups.model.GroupConversation.NegotiationState;
import ai.labs.eddi.configs.groups.model.GroupConversation.Proposal;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The negotiation protocol's state machine (I11): parses PROPOSAL and BARGAIN
 * turns into {@link NegotiationState} mutations, detects unanimous agreement,
 * and renders the ledger every bargaining turn is held accountable to.
 * <p>
 * EDDI's other decision forms are win/lose; this is the <b>trade</b> form. The
 * typed structure — every acceptance names a proposal id, every concession
 * names what was received in return, and the ledger is quoted back into every
 * turn — is precisely what stops sycophantic instant-agreement: an agent cannot
 * "agree" without signing a specific set of terms on the record.
 * <p>
 * Parse discipline mirrors {@link VoteTallyEngine}: three tiers (strict JSON →
 * JSON embedded in prose/fence → give up), {@code FAIL_ON_TRAILING_TOKENS}, and
 * an unreadable turn is prose with NO state effect (WARN) — never a guessed
 * acceptance.
 *
 * @author ginccc
 */
public final class NegotiationEngine {

    private static final Logger LOGGER = Logger.getLogger(NegotiationEngine.class);

    /** The mechanism tag on an AGREEMENT reached by unanimous acceptance. */
    public static final String METHOD_NEGOTIATION = "negotiation";
    /**
     * The mechanism tag on a VERDICT the moderator arbitrated after failed
     * bargaining.
     */
    public static final String METHOD_ARBITRATION = "arbitration";

    /**
     * Terms are quoted into every turn's ledger — bound the quoting, not the entry.
     */
    static final int MAX_QUOTED_TERMS_CHARS = 600;

    private static final ObjectMapper MAPPER = JsonMapper.builder()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .build();

    private NegotiationEngine() {
    }

    /**
     * One parsed BARGAIN turn. Any of the parts may be absent; a turn that carries
     * none of them is a non-move.
     */
    record BargainMove(String accept, String proposalTerms, List<ParsedConcession> concessions) {
    }

    record ParsedConcession(String gaveUp, String inReturnFor) {
    }

    /**
     * Applies one completed PROPOSAL/BARGAIN repeat to the negotiation state.
     * <p>
     * PROPOSAL entries: the whole turn is the proposal's terms (String v1). BARGAIN
     * entries: the JSON contract — {@code accept} joins an OPEN proposal's
     * signatories, {@code proposal} supersedes the mover's own open proposals and
     * puts new terms on the table, {@code concessions} append to the ledger. An
     * unparseable BARGAIN turn is prose with no state effect.
     *
     * @param transcriptOffset
     *            absolute transcript index of the repeat's first entry — what turns
     *            a position in {@code repeatEntries} into the signed entry index an
     *            acceptance records
     */
    public static void applyRepeat(GroupConversation gc, DiscussionPhase phase, List<TranscriptEntry> repeatEntries,
                                   int transcriptOffset, int round) {
        if (repeatEntries == null || repeatEntries.isEmpty()) {
            return;
        }
        NegotiationState state = gc.negotiationState();
        for (int i = 0; i < repeatEntries.size(); i++) {
            TranscriptEntry entry = repeatEntries.get(i);
            if (entry == null || entry.content() == null || entry.content().isBlank()) {
                continue;
            }
            int entryIndex = transcriptOffset + i;
            if (entry.type() == TranscriptEntryType.PROPOSAL) {
                addProposal(state, entry.speakerAgentId(), entry.content().trim(), round, entryIndex);
            } else if (entry.type() == TranscriptEntryType.BARGAIN) {
                BargainMove move = parseBargain(entry.content());
                if (move == null) {
                    LOGGER.warnf("Group %s: unparseable BARGAIN turn from '%s' — prose only, no state effect",
                            gc.getId(), entry.speakerAgentId());
                    continue;
                }
                applyMove(gc, state, entry.speakerAgentId(), move, round, entryIndex);
            }
        }
    }

    /**
     * Three-tier BARGAIN parse. {@code null} = not a readable move.
     */
    static BargainMove parseBargain(String content) {
        JsonNode node = readJson(content);
        if (node == null) {
            node = readJson(embeddedJson(content));
        }
        if (node == null || !node.isObject()) {
            return null;
        }
        String accept = node.path("accept").isTextual() && !node.path("accept").asText().isBlank()
                ? node.path("accept").asText().trim()
                : null;
        String proposalTerms = null;
        JsonNode proposal = node.path("proposal");
        if (proposal.isObject() && proposal.path("terms").isTextual() && !proposal.path("terms").asText().isBlank()) {
            proposalTerms = proposal.path("terms").asText().trim();
        }
        List<ParsedConcession> concessions = new ArrayList<>();
        if (node.path("concessions").isArray()) {
            for (JsonNode c : node.path("concessions")) {
                String gaveUp = c.path("gaveUp").isTextual() ? c.path("gaveUp").asText().trim() : null;
                String inReturnFor = c.path("inReturnFor").isTextual() ? c.path("inReturnFor").asText().trim() : null;
                // The rule IS the structure: a concession that does not name what
                // was received in return is not recorded.
                if (gaveUp != null && !gaveUp.isBlank() && inReturnFor != null && !inReturnFor.isBlank()) {
                    concessions.add(new ParsedConcession(gaveUp, inReturnFor));
                }
            }
        }
        if (accept == null && proposalTerms == null && concessions.isEmpty()) {
            return null;
        }
        return new BargainMove(accept, proposalTerms, concessions);
    }

    private static void applyMove(GroupConversation gc, NegotiationState state, String agentId, BargainMove move,
                                  int round, int entryIndex) {
        if (move.accept() != null) {
            Proposal target = findById(state, move.accept());
            if (target == null || !GroupConversation.PROPOSAL_OPEN.equals(target.status())) {
                LOGGER.warnf("Group %s: '%s' accepted %s proposal '%s' — ignored",
                        gc.getId(), agentId, target == null ? "unknown" : "non-open", move.accept());
            } else if (!target.acceptedBy().contains(agentId)) {
                List<String> acceptedBy = new ArrayList<>(target.acceptedBy());
                acceptedBy.add(agentId);
                Map<String, Integer> indices = new LinkedHashMap<>(target.acceptanceEntryIndices());
                indices.put(agentId, entryIndex);
                replace(state, target, new Proposal(target.id(), target.byAgentId(), target.round(), target.terms(),
                        target.status(), List.copyOf(acceptedBy), Map.copyOf(indices)));
            }
        }
        Proposal added = null;
        if (move.proposalTerms() != null) {
            added = addProposal(state, agentId, move.proposalTerms(), round, entryIndex);
        }
        for (ParsedConcession c : move.concessions()) {
            state.getConcessions().add(new Concession(agentId, round, c.gaveUp(), c.inReturnFor(),
                    added != null ? added.id() : null));
        }
    }

    /**
     * Puts new terms on the table. The proposer's own OPEN proposals are SUPERSEDED
     * — one live offer per agent — and the proposer signs their own terms
     * implicitly (their authoring entry is the signature).
     */
    private static Proposal addProposal(NegotiationState state, String agentId, String terms, int round, int entryIndex) {
        for (Proposal p : state.getProposals()) {
            if (GroupConversation.PROPOSAL_OPEN.equals(p.status()) && p.byAgentId().equals(agentId)) {
                replace(state, p, new Proposal(p.id(), p.byAgentId(), p.round(), p.terms(),
                        GroupConversation.PROPOSAL_SUPERSEDED, p.acceptedBy(), p.acceptanceEntryIndices()));
            }
        }
        Proposal proposal = new Proposal("p" + (state.getProposals().size() + 1), agentId, round, terms,
                GroupConversation.PROPOSAL_OPEN, List.of(agentId), Map.of(agentId, entryIndex));
        state.getProposals().add(proposal);
        return proposal;
    }

    private static Proposal findById(NegotiationState state, String id) {
        return state.getProposals().stream().filter(p -> p.id().equals(id)).findFirst().orElse(null);
    }

    private static void replace(NegotiationState state, Proposal oldOne, Proposal newOne) {
        int idx = state.getProposals().indexOf(oldOne);
        if (idx >= 0) {
            state.getProposals().set(idx, newOne);
        }
    }

    /**
     * Unanimous agreement: every non-moderator AGENT participant has signed the
     * SAME open proposal. Records the AGREEMENT decision — the signed acceptance
     * entries ARE the co-signatures; their transcript indices ride
     * {@code tally.signedAcceptances}, no new crypto — and returns {@code true} so
     * the caller can end the phase's repeats early (the I2 plumbing).
     */
    public static boolean checkAndRecordAgreement(GroupConversation gc, List<GroupMember> participants,
                                                  String moderatorAgentId, String phaseName) {
        NegotiationState state = gc.getNegotiation();
        if (state == null || state.getProposals().isEmpty() || participants == null || participants.isEmpty()) {
            return false;
        }
        List<String> required = participants.stream()
                .filter(Objects::nonNull)
                .filter(m -> m.memberType() != MemberType.GROUP)
                .map(GroupMember::agentId)
                .filter(id -> !id.equals(moderatorAgentId))
                .distinct()
                .toList();
        if (required.isEmpty()) {
            return false;
        }
        for (Proposal p : state.getProposals()) {
            if (!GroupConversation.PROPOSAL_OPEN.equals(p.status())) {
                continue;
            }
            if (p.acceptedBy().containsAll(required)) {
                Map<String, Object> tally = new LinkedHashMap<>();
                tally.put("proposalId", p.id());
                tally.put("proposedBy", p.byAgentId());
                tally.put("terms", p.terms());
                tally.put("signedAcceptances", p.acceptanceEntryIndices());
                tally.put("concessions", state.getConcessions().stream().map(c -> {
                    Map<String, Object> line = new LinkedHashMap<>();
                    line.put("by", c.byAgentId());
                    line.put("gaveUp", c.gaveUp());
                    line.put("inReturnFor", c.inReturnFor());
                    return line;
                }).toList());
                String outcome = "Agreement on proposal %s (by %s), signed by all %d participants: %s%s".formatted(
                        p.id(), p.byAgentId(), required.size(), truncate(p.terms()),
                        state.getConcessions().isEmpty()
                                ? ""
                                : " — " + state.getConcessions().size() + " concession(s) on the ledger.");
                gc.setDecision(new DecisionRecord(DecisionType.AGREEMENT, outcome, p.id(), tally, List.of(),
                        METHOD_NEGOTIATION, phaseName, null));
                LOGGER.infof("Group %s: negotiation reached agreement on proposal %s", gc.getId(), p.id());
                return true;
            }
        }
        return false;
    }

    /**
     * Records the moderator's arbitration as the discussion's VERDICT (I11) — the
     * bargaining failed, so the last arbitration entry's prose is the decision.
     * Never overwrites an existing decision.
     */
    public static void recordArbitration(GroupConversation gc, List<TranscriptEntry> repeatEntries, String phaseName) {
        if (gc.getDecision() != null || repeatEntries == null) {
            return;
        }
        repeatEntries.stream()
                .filter(e -> e != null && e.type() == TranscriptEntryType.SYNTHESIS && e.content() != null
                        && !e.content().isBlank())
                .reduce((first, second) -> second)
                .ifPresent(e -> gc.setDecision(new DecisionRecord(DecisionType.VERDICT, e.content(), null, null,
                        List.of(), METHOD_ARBITRATION, phaseName, null)));
    }

    /**
     * Renders the negotiation state into a turn's input: open proposals (with ids,
     * so an acceptance can name one) and the concession ledger. Appended to
     * PROPOSAL/BARGAIN turns and to negotiation SYNTHESIS turns (arbitration and
     * final synthesis quote the ledger); a no-op for every other phase or when
     * there is no state yet.
     */
    public static String appendStateIfRelevant(String input, GroupConversation gc, DiscussionPhase phase) {
        NegotiationState state = gc.getNegotiation();
        if (state == null || (state.getProposals().isEmpty() && state.getConcessions().isEmpty())) {
            return input;
        }
        if (phase.type() != PhaseType.PROPOSAL && phase.type() != PhaseType.BARGAIN
                && phase.type() != PhaseType.SYNTHESIS) {
            return input;
        }
        var sb = new StringBuilder(input);
        sb.append("\n\nNEGOTIATION TABLE (the record — it will be quoted in the outcome):\n");
        sb.append("Open proposals:\n");
        boolean anyOpen = false;
        for (Proposal p : state.getProposals()) {
            if (GroupConversation.PROPOSAL_OPEN.equals(p.status())) {
                anyOpen = true;
                sb.append("- [").append(p.id()).append("] by ").append(p.byAgentId())
                        .append(" (accepted by: ").append(String.join(", ", p.acceptedBy())).append("): ")
                        .append(truncate(p.terms())).append('\n');
            }
        }
        if (!anyOpen) {
            sb.append("- (none)\n");
        }
        sb.append("Concession ledger:\n");
        if (state.getConcessions().isEmpty()) {
            sb.append("- (empty)\n");
        } else {
            for (Concession c : state.getConcessions()) {
                sb.append("- ").append(c.byAgentId()).append(" gave up \"").append(truncate(c.gaveUp()))
                        .append("\" in return for \"").append(truncate(c.inReturnFor())).append("\"\n");
            }
        }
        return sb.toString();
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= MAX_QUOTED_TERMS_CHARS ? value : value.substring(0, MAX_QUOTED_TERMS_CHARS) + "…";
    }

    private static JsonNode readJson(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readTree(content.trim());
        } catch (Exception e) {
            return null;
        }
    }

    /** JSON embedded in prose or a code fence — first balanced object. */
    private static String embeddedJson(String content) {
        if (content == null) {
            return null;
        }
        int start = content.indexOf('{');
        if (start < 0) {
            return null;
        }
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < content.length(); i++) {
            char c = content.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
            } else if (c == '"') {
                inString = !inString;
            } else if (!inString) {
                if (c == '{') {
                    depth++;
                } else if (c == '}') {
                    depth--;
                    if (depth == 0) {
                        return content.substring(start, i + 1);
                    }
                }
            }
        }
        return null;
    }
}
