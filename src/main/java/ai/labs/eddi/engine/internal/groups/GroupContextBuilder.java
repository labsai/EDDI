/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal.groups;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.ContextScope;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.DiscussionPhase;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.PhaseType;
import ai.labs.eddi.configs.groups.model.DiscussionStylePresets;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
import ai.labs.eddi.engine.memory.ConversationOutputExtractor;
import ai.labs.eddi.engine.memory.model.SimpleConversationMemorySnapshot;
import ai.labs.eddi.modules.templating.ITemplatingEngine;
import org.jboss.logging.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Builds phase-specific input for a group member's turn and decides what
 * scope-filtered slice of the transcript each participant sees. Extracted from
 * {@code GroupConversationService} (Wave R, R1 step 2) as a pure move — no
 * behavior change.
 * <p>
 * This is the single place that decides what any participant sees; a future
 * visibility matrix (peer-hidden entry types, windowing) lands here rather than
 * being scattered across the discussion loop.
 *
 * @author ginccc
 */
public class GroupContextBuilder {

    private static final Logger LOGGER = Logger.getLogger(GroupContextBuilder.class);

    private final ITemplatingEngine templatingEngine;

    public GroupContextBuilder(ITemplatingEngine templatingEngine) {
        this.templatingEngine = templatingEngine;
    }

    public String buildPhaseInput(DiscussionPhase phase, GroupMember speaker, String question, List<TranscriptEntry> transcript, int phaseIdx,
                                  GroupMember target) {
        return buildPhaseInput(phase, speaker, question, transcript, phaseIdx, target, null);
    }

    /**
     * @param allMembers
     *            the full group roster, used only by {@code ARGUE}/{@code
     *            REBUTTAL} to resolve which transcript entries came from the
     *            opposing team rather than merely "not me" (see
     *            {@link #opposingArguments}). {@code null} or empty falls back to
     *            the not-me filter, which is only correct for a 2-member debate —
     *            existing callers that never reach those two phase types are
     *            unaffected either way.
     */
    public String buildPhaseInput(DiscussionPhase phase, GroupMember speaker, String question, List<TranscriptEntry> transcript, int phaseIdx,
                                  GroupMember target, List<GroupMember> allMembers) {

        String template = phase.inputTemplate() != null
                ? phase.inputTemplate()
                : isDebateJudgment(phase, speaker, transcript, phaseIdx, allMembers)
                        ? DiscussionStylePresets.TEMPLATE_DEBATE_JUDGMENT
                        : selectDefaultTemplate(phase, transcript, phaseIdx);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("question", question);
        data.put("displayName", speaker.displayName());
        data.put("phaseIndex", phaseIdx);
        data.put("phaseName", phase.name());

        // Phase-type specific variables
        switch (phase.type()) {
            case OPINION -> {
                List<Map<String, Object>> prev = filterByScope(transcript, phase.contextScope(), phaseIdx, speaker);
                data.put("previousResponses", prev);
            }
            case CRITIQUE -> {
                if (target != null) {
                    data.put("targetName", target.displayName());
                    String targetResponse = findLatestResponse(transcript, target.agentId());
                    data.put("targetResponse", targetResponse != null ? targetResponse : "(no response)");
                }
            }
            case REVISION -> {
                String originalResponse = findLatestResponse(transcript, speaker.agentId());
                data.put("originalResponse", originalResponse != null ? originalResponse : "(no response)");
                // Feedback addressed TO this speaker
                List<Map<String, Object>> feedback = transcript.stream()
                        .filter(e -> e.type() == TranscriptEntryType.CRITIQUE && speaker.agentId().equals(e.targetAgentId())).map(e -> {
                            Map<String, Object> fb = new LinkedHashMap<>();
                            fb.put("reviewer", e.speakerDisplayName());
                            fb.put("content", e.content());
                            return fb;
                        }).collect(Collectors.toList());
                data.put("feedbackReceived", feedback);
            }
            case CHALLENGE -> {
                List<Map<String, Object>> opinions = transcript.stream().filter(e -> e.type() == TranscriptEntryType.OPINION && e.content() != null)
                        .map(e -> {
                            Map<String, Object> o = new LinkedHashMap<>();
                            o.put("speaker", e.speakerDisplayName());
                            o.put("content", e.content());
                            return o;
                        }).collect(Collectors.toList());
                data.put("allOpinions", opinions);
            }
            case DEFENSE -> {
                String originalResponse = findLatestResponse(transcript, speaker.agentId());
                data.put("originalResponse", originalResponse != null ? originalResponse : "(no response)");
                List<Map<String, Object>> challenges = transcript.stream()
                        .filter(e -> e.type() == TranscriptEntryType.CHALLENGE && e.content() != null).map(e -> {
                            Map<String, Object> c = new LinkedHashMap<>();
                            c.put("speaker", e.speakerDisplayName());
                            c.put("content", e.content());
                            return c;
                        }).collect(Collectors.toList());
                data.put("challenges", challenges);
            }
            case ARGUE, REBUTTAL -> {
                String role = speaker.role();
                data.put("teamSide", "PRO".equalsIgnoreCase(role) ? "FOR" : "AGAINST");
                Set<String> teammateIds = teammateAgentIds(speaker, allMembers);
                // Opposing arguments — filtered by TEAM (role), not by "any other
                // speaker" (V6(a), confirmed as a real defect rather than a
                // hypothetical one: DEBATE's phases select participants via
                // "ROLE:PRO"/"ROLE:CON", which resolveParticipants resolves against
                // every member sharing that role, with no cap of one per side. The
                // old "not me" filter only happened to be correct for exactly 1 PRO
                // + 1 CON; with 2+ members per side, a PRO speaker saw their own
                // PRO teammate's arguments folded into "opposingArguments" too.
                List<Map<String, Object>> opposing = transcript.stream()
                        .filter(e -> (e.type() == TranscriptEntryType.ARGUMENT || e.type() == TranscriptEntryType.REBUTTAL) && e.content() != null)
                        .filter(e -> !teammateIds.contains(e.speakerAgentId())).map(e -> {
                            Map<String, Object> a = new LinkedHashMap<>();
                            a.put("speaker", e.speakerDisplayName());
                            a.put("content", e.content());
                            return a;
                        }).collect(Collectors.toList());
                data.put("opposingArguments", opposing);
            }
            case SYNTHESIS -> {
                // Deliberately its own filter, not filterByScope's peer-visibility
                // matrix (Wave 0, F4): the synthesizer needs the full picture —
                // including ABSTAINED/CONVERGENCE/FACILITATION bookkeeping and any
                // still-running-phase VOTE/BID a regular peer would not yet see —
                // to write an accurate summary.
                List<Map<String, Object>> fullTranscript = transcript.stream()
                        .filter(e -> e.content() != null && e.type() != TranscriptEntryType.ERROR && e.type() != TranscriptEntryType.SKIPPED
                                && e.type() != TranscriptEntryType.QUESTION)
                        .map(e -> {
                            Map<String, Object> t = new LinkedHashMap<>();
                            t.put("speaker", e.speakerDisplayName());
                            t.put("content", e.content());
                            t.put("phaseName", e.phaseName() != null ? e.phaseName() : "");
                            return t;
                        }).collect(Collectors.toList());
                data.put("transcript", fullTranscript);
                data.put("totalPhases", phaseIdx);
            }
            case PLAN -> {
                // Unreachable in practice, and deliberately populates nothing —
                // symmetric with EXECUTE/VERIFY below. executeDiscussion routes
                // PLAN/EXECUTE/VERIFY to TaskForceEngine before any executor that
                // calls this method, and TaskForceEngine.executeTaskPlanPhase
                // builds its own template data with the real roster.
                //
                // This branch used to put an EMPTY "members" list here with a
                // comment claiming the caller populated it; no caller ever did.
                // Had the routing above ever changed, that empty list would have
                // rendered TEMPLATE_PLAN's "TEAM MEMBERS:" section blank and
                // silently degraded task assignment — a populated-looking key is
                // more dangerous than an absent one.
            }
            case EXECUTE -> {
                // Task-specific context populated by executeTaskPhase
            }
            case VERIFY -> {
                // Completed tasks populated by executeTaskPhase
            }
            case RETRO -> {
                // I8: the retro reviews the whole discussion — same full-picture
                // filter as SYNTHESIS (a retrospective on a windowed excerpt would
                // draw lessons from an excerpt).
                List<Map<String, Object>> fullTranscript = transcript.stream()
                        .filter(e -> e.content() != null && e.type() != TranscriptEntryType.ERROR && e.type() != TranscriptEntryType.SKIPPED
                                && e.type() != TranscriptEntryType.QUESTION)
                        .map(e -> {
                            Map<String, Object> t = new LinkedHashMap<>();
                            t.put("speaker", e.speakerDisplayName());
                            t.put("content", e.content());
                            t.put("phaseName", e.phaseName() != null ? e.phaseName() : "");
                            return t;
                        }).collect(Collectors.toList());
                data.put("transcript", fullTranscript);
                // The template QUOTES the default cap; RetroEngine ENFORCES the
                // configured one at parse time regardless of what the model was
                // told. Quoting the default keeps this builder free of the group
                // config (which it deliberately does not receive).
                data.put("maxLessonsPerRun", AgentGroupConfiguration.RetroConfig.DEFAULT_MAX_PER_RUN);
            }
            default -> {
                // All PhaseType values handled above; default required by checkstyle
            }
        }

        String rendered;
        try {
            rendered = templatingEngine.processTemplate(template, data, ITemplatingEngine.TemplateMode.TEXT);
        } catch (ITemplatingEngine.TemplateEngineException e) {
            LOGGER.warnf("Template processing failed for phase '%s', " + "using plain text: %s", phase.name(), e.getMessage());
            rendered = buildPlainTextFallback(phase, speaker, question, transcript);
        }
        // I4: appended AFTER rendering, and on the fallback path too. Appending here
        // rather than inside each template covers custom inputTemplates for free, and
        // covering the fallback matters because a template-engine failure would
        // otherwise silently disable abstention for exactly the turns already going
        // wrong — the member would be told nothing about PASS, answer normally, and
        // the phase would look like it simply had nothing to abstain about.
        return AbstentionDetector.isEnabledFor(phase) ? rendered + AbstentionDetector.ABSTENTION_INSTRUCTION : rendered;
    }

    /**
     * {@code transcript} and {@code phaseIdx} are unused, and stay. Static analysis
     * flags them on every run; removing them breaks the build. Characterization
     * tests reach this method through
     * {@code getDeclaredMethod("selectDefaultTemplate", DiscussionPhase.class, List.class, int.class)},
     * which resolves by exact parameter types, so a narrower signature is not
     * findable — and those tests are the regression net this whole refactor leans
     * on.
     * <p>
     * I3's debate-judgment selection deliberately does <em>not</em> live here: it
     * needs the speaker and the roster to decide, and a method that can only see
     * the transcript would have to guess at both. See {@link #isDebateJudgment},
     * which {@link #buildPhaseInput} consults first.
     */
    public String selectDefaultTemplate(DiscussionPhase phase, List<TranscriptEntry> transcript, int phaseIdx) {
        if (phase.type() == PhaseType.OPINION) {
            // Use independent template if no context, or context template if
            // there are prior responses
            if (phase.contextScope() == ContextScope.NONE) {
                return DiscussionStylePresets.TEMPLATE_OPINION_INDEPENDENT;
            }
            if (phase.contextScope() == ContextScope.ANONYMOUS) {
                return DiscussionStylePresets.TEMPLATE_OPINION_ANONYMOUS;
            }
            return DiscussionStylePresets.TEMPLATE_OPINION_WITH_CONTEXT;
        }
        return DiscussionStylePresets.defaultTemplate(phase.type());
    }

    /**
     * Whether {@code speaker} is concluding a two-sided debate, and so should be
     * asked for a verdict rather than a balanced summary (I3).
     * <p>
     * All four conditions are load-bearing, and each rules out a distinct way of
     * producing a confident structured winner for a discussion that never had one:
     * <ol>
     * <li><b>A SYNTHESIS phase with no {@code inputTemplate}.</b> An explicit
     * template is the config author's own instruction and always wins; asking for
     * JSON when they asked for something else would silently discard it.</li>
     * <li><b>Arguments already on the transcript, from a phase before this one.</b>
     * Read from the transcript rather than from the style field because the phases
     * that actually ran are what matter: a CUSTOM config arranging ARGUE/REBUTTAL
     * is still a debate, and a DEBATE-styled config whose argument phases were all
     * skipped has nothing to judge. Those two entry types come only from
     * {@code ARGUE}/{@code REBUTTAL} phases (see {@link #mapPhaseToEntryType}).
     * Entries at {@code phaseIdx} or later are ignored — a phase cannot judge
     * arguments it has not seen, and a resume can carry later-phase entries from an
     * earlier leg.</li>
     * <li><b>At least two distinct roles among the members.</b> The judgment prompt
     * asks the model to score PRO against CON, so a roster with no sides would have
     * it invent a winner: {@code create_group(style="DEBATE")} without
     * {@code memberRoles} makes every speaker argue the same side (see the
     * {@code ARGUE} branch above, which maps a null role to "AGAINST"), and a
     * verdict there would be pure fabrication.</li>
     * <li><b>A speaker who is not one of the debaters.</b> A partisan judging its
     * own debate reads its own "argue the FOR side" instructions back as recent
     * context — the contamination I2's {@code JUDGE_CONVERSATION_KEY} exists to
     * prevent — and stamping the result as {@code DecisionRecord.winner} would
     * present one side's opinion as the group's finding. A moderator-less debate
     * therefore concludes in prose, which is at least honest about who wrote
     * it.</li>
     * </ol>
     */
    public boolean isDebateJudgment(DiscussionPhase phase, GroupMember speaker, List<TranscriptEntry> transcript, int phaseIdx,
                                    List<GroupMember> allMembers) {
        if (phase == null || phase.type() != PhaseType.SYNTHESIS || phase.inputTemplate() != null || transcript == null) {
            return false;
        }
        boolean hasArguments = transcript.stream().anyMatch(e -> e != null && e.phaseIndex() < phaseIdx
                && (e.type() == TranscriptEntryType.ARGUMENT || e.type() == TranscriptEntryType.REBUTTAL));
        if (!hasArguments) {
            return false;
        }
        Set<String> debatingRoles = debatingRoles(allMembers);
        if (debatingRoles.size() < 2) {
            return false;
        }
        return speaker == null || speaker.role() == null || !debatingRoles.contains(speaker.role().toUpperCase(Locale.ROOT));
    }

    /** The distinct, non-blank member roles — the "sides" a debate has. */
    private static Set<String> debatingRoles(List<GroupMember> allMembers) {
        if (allMembers == null) {
            return Set.of();
        }
        return allMembers.stream()
                .map(GroupMember::role)
                .filter(r -> r != null && !r.isBlank())
                .map(r -> r.toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }

    public List<Map<String, Object>> filterByScope(List<TranscriptEntry> transcript, ContextScope scope, int currentPhaseIdx, GroupMember speaker) {
        if (scope == null || scope == ContextScope.NONE) {
            return List.of();
        }

        return transcript.stream().filter(e -> e.content() != null && e.type() != TranscriptEntryType.ERROR && e.type() != TranscriptEntryType.SKIPPED
                && e.type() != TranscriptEntryType.QUESTION).filter(e -> isVisibleToPeers(e, currentPhaseIdx)).filter(e -> switch (scope) {
                    case FULL -> true;
                    case LAST_PHASE -> e.phaseIndex() >= currentPhaseIdx - 1;
                    case ANONYMOUS -> true; // Content included, attribution stripped
                    case OWN_FEEDBACK -> speaker.agentId().equals(e.targetAgentId());
                    case NONE -> false;
                    case TASK_ONLY, TASK_WITH_DEPS -> false; // Handled by task-specific logic
                }).map(e -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    if (scope == ContextScope.ANONYMOUS) {
                        entry.put("speaker", "Anonymous");
                    } else {
                        entry.put("speaker", e.speakerDisplayName());
                    }
                    entry.put("content", e.content());
                    entry.put("phaseName", e.phaseName() != null ? e.phaseName() : "");
                    return entry;
                }).collect(Collectors.toList());
    }

    /**
     * The peer-visibility matrix (Wave 0, F4) — what a group MEMBER's own turn
     * context may include, as opposed to an observer (SSE/Slack), which reads
     * {@code GroupConversation#getTranscript()} directly and is never filtered
     * through this method.
     * <p>
     * {@code ABSTAINED}, {@code CONVERGENCE} and {@code FACILITATION} are never
     * peer-visible — they are process bookkeeping (a pass, a judge's score, a
     * facilitator's intervention), not a contribution another speaker should react
     * to. {@code VOTE} and {@code BID} are blind while their own phase is still
     * running (a ballot/bid cast so far THIS phase, {@code e.phaseIndex() ==
     * currentPhaseIdx}) and become visible once that phase completes and a later
     * phase looks back at it — commit-reveal, not permanent concealment. Everything
     * else (including the newer {@code DISSENT}, {@code PROPOSAL}, {@code BARGAIN},
     * {@code HUMAN_INPUT}, {@code RETRO}) is peer-visible, same as every pre-F4
     * entry type.
     */
    private static boolean isVisibleToPeers(TranscriptEntry e, int currentPhaseIdx) {
        return switch (e.type()) {
            case ABSTAINED, CONVERGENCE, FACILITATION -> false;
            case VOTE, BID -> e.phaseIndex() != currentPhaseIdx;
            default -> true;
        };
    }

    public String findLatestResponse(List<TranscriptEntry> transcript, String agentId) {
        return transcript.stream()
                .filter(e -> agentId.equals(e.speakerAgentId()) && e.content() != null && e.type() != TranscriptEntryType.ERROR
                        && e.type() != TranscriptEntryType.SKIPPED)
                .reduce((first, second) -> second) // last match
                .map(TranscriptEntry::content).orElse(null);
    }

    public TranscriptEntryType mapPhaseToEntryType(PhaseType type) {
        return switch (type) {
            case OPINION -> TranscriptEntryType.OPINION;
            case CRITIQUE -> TranscriptEntryType.CRITIQUE;
            case REVISION -> TranscriptEntryType.REVISION;
            case CHALLENGE -> TranscriptEntryType.CHALLENGE;
            case DEFENSE -> TranscriptEntryType.DEFENSE;
            case ARGUE -> TranscriptEntryType.ARGUMENT;
            case REBUTTAL -> TranscriptEntryType.REBUTTAL;
            case SYNTHESIS -> TranscriptEntryType.SYNTHESIS;
            case PLAN -> TranscriptEntryType.PLAN;
            case EXECUTE -> TranscriptEntryType.TASK_RESULT;
            case VERIFY -> TranscriptEntryType.VERIFICATION;
            // I8: retro contributions are public RETRO entries (F4 default).
            case RETRO -> TranscriptEntryType.RETRO;
        };
    }

    /**
     * Extracts the human-readable text from a conversation memory snapshot.
     * Delegates to the shared {@link ConversationOutputExtractor} utility.
     */
    public String extractResponse(SimpleConversationMemorySnapshot snapshot) {
        String result = ConversationOutputExtractor.extractResponse(snapshot);
        // Convert null to empty string for backward compatibility with GCS callers
        // that check for empty-string (pipeline metadata-only snapshots still return
        // null).
        return result != null ? result : "";
    }

    /**
     * {@code transcript} is unused and stays, for the same reason as
     * {@link #selectDefaultTemplate}: the characterization suite resolves this
     * method by exact parameter types. This is the fallback used when template
     * rendering fails, so it deliberately reads nothing that could fail again.
     */
    public String buildPlainTextFallback(DiscussionPhase phase, GroupMember speaker, String question, List<TranscriptEntry> transcript) {
        var sb = new StringBuilder();
        sb.append("Discussion phase: ").append(phase.name()).append("\n\n");
        sb.append("Question: \"").append(question).append("\"\n\n");
        sb.append("As ").append(speaker.displayName());
        sb.append(", please contribute to this phase of the discussion.");
        return sb.toString();
    }

    /**
     * Agent ids sharing {@code speaker}'s role (case-insensitively), including
     * {@code speaker} itself — "my team" for the purposes of excluding entries from
     * {@code opposingArguments}. Falls back to just {@code speaker}'s own id when
     * the roster is unavailable or {@code speaker.role()} is null, which reproduces
     * the pre-fix "not me" filter exactly for that case.
     */
    private static Set<String> teammateAgentIds(GroupMember speaker, List<GroupMember> allMembers) {
        if (allMembers == null || allMembers.isEmpty() || speaker.role() == null) {
            return Set.of(speaker.agentId());
        }
        return Stream.concat(
                allMembers.stream().filter(m -> speaker.role().equalsIgnoreCase(m.role())).map(GroupMember::agentId),
                Stream.of(speaker.agentId()))
                .collect(Collectors.toUnmodifiableSet());
    }
}
