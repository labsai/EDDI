/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.gdpr;

import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.GroupMember;
import ai.labs.eddi.configs.groups.model.AgentGroupConfiguration.MemberType;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.engine.mail.AddressNormalizer;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Masks the identifiers of <em>other</em> people in a data subject's export
 * (GDPR Art. 15(4): the right of access must not adversely affect the rights of
 * others).
 * <p>
 * Records reached by the subject's id can name someone else as well: the
 * approver of a pause in the subject's conversation, the owner of a
 * conversation the subject approved in, the reviewer of a sample, another human
 * member of a group discussion the subject started. The subject is entitled to
 * the fact that such a person was involved, not to who it was, so their
 * identifier becomes {@value #OTHER_PERSON}. The subject's own id is kept, and
 * so are values that name no person — {@code system:*} principals and the
 * {@code unknown} / {@code anonymous} placeholders.
 */
public final class ThirdPartyIdentifiers {

    /** What another person's identifier is replaced with. */
    public static final String OTHER_PERSON = "[other person]";

    private ThirdPartyIdentifiers() {
    }

    /**
     * {@code value} when it is the subject's own id, empty or names no person;
     * {@link #OTHER_PERSON} otherwise.
     */
    public static String keepOwn(String value, String subjectId) {
        if (value == null || value.isBlank() || value.equals(subjectId) || namesNoPerson(value)) {
            return value;
        }
        return OTHER_PERSON;
    }

    private static boolean namesNoPerson(String value) {
        return value.startsWith("system:") || "unknown".equals(value) || "anonymous".equals(value);
    }

    /**
     * The transcript of {@code discussion} with nobody but the subject and the
     * group's agents left recognisable, without a group configuration — see
     * {@link #transcriptFor(GroupConversation, String, Set)}.
     */
    public static List<TranscriptEntry> transcriptFor(GroupConversation discussion, String subjectId) {
        return transcriptFor(discussion, subjectId, Set.of());
    }

    /**
     * The transcript of {@code discussion} with nobody but the subject and the
     * group's agents left recognisable. Fails closed: an id is kept only when it is
     * absent (a system entry names no speaker), the subject's own, names no person
     * ({@code system:*}, {@code unknown}, {@code anonymous}) or is <em>positively
     * known</em> to be an agent. Every other speaker id and target id, and the
     * display name of a masked speaker, becomes {@value #OTHER_PERSON}; the content
     * of the entries is not changed.
     * <p>
     * A transcript entry does not record whether its speaker was human — a human
     * member's turn can carry any entry type, and a human id need not look like an
     * address — so a person cannot be ruled out from the transcript alone. An id is
     * a known agent only on evidence the stored discussion itself recorded at the
     * time: a member conversation was started for it (only agents get one), it was
     * recruited or created during the discussion, or it is a dynamically added
     * member persisted with a non-{@code HUMAN} type. The group's <em>current</em>
     * configuration is never evidence that an id is an agent — it may have changed
     * since, and an id that was a HUMAN member then may be listed as an AGENT now —
     * so it can only add masks: an id it lists as {@code HUMAN}
     * ({@code configuredHumans}) is masked even where the discussion's own record
     * would keep it, and so is the member a pending human turn waits on, and a
     * dynamic member persisted as {@code HUMAN}.
     * <p>
     * An agent that never got a member conversation (one that was only ever
     * skipped, say) is therefore masked as well: the bundle shows
     * {@value #OTHER_PERSON} for it, which costs the data subject an agent's name,
     * never another person's identity.
     */
    public static List<TranscriptEntry> transcriptFor(GroupConversation discussion, String subjectId, Set<String> configuredHumans) {
        List<TranscriptEntry> stored = discussion.getTranscript();
        if (stored == null || stored.isEmpty()) {
            return stored;
        }
        List<TranscriptEntry> transcript;
        synchronized (stored) {
            // The model's list is a synchronized list; iterating it needs the lock.
            transcript = new ArrayList<>(stored);
        }
        Set<String> agents = new HashSet<>();
        Set<String> humans = new HashSet<>();
        if (configuredHumans != null) {
            humans.addAll(configuredHumans);
        }
        if (discussion.getDynamicMembers() != null) {
            for (GroupMember member : discussion.getDynamicMembers()) {
                if (member != null && member.agentId() != null) {
                    (member.memberType() == MemberType.HUMAN ? humans : agents).add(member.agentId());
                }
            }
        }
        if (discussion.getMemberConversationIds() != null) {
            agents.addAll(discussion.getMemberConversationIds().keySet());
        }
        if (discussion.getRecruitedAgentIds() != null) {
            agents.addAll(discussion.getRecruitedAgentIds());
        }
        if (discussion.getCreatedAgentIds() != null) {
            agents.addAll(discussion.getCreatedAgentIds());
        }
        agents.removeAll(humans);
        if (discussion.getPendingHumanInput() != null) {
            agents.remove(discussion.getPendingHumanInput().memberId());
        }
        List<TranscriptEntry> masked = new ArrayList<>(transcript.size());
        for (TranscriptEntry entry : transcript) {
            if (entry == null) {
                masked.add(null);
                continue;
            }
            boolean speakerMasked = !recognisable(entry.speakerAgentId(), subjectId, agents);
            boolean targetMasked = !recognisable(entry.targetAgentId(), subjectId, agents);
            if (!speakerMasked && !targetMasked) {
                masked.add(entry);
                continue;
            }
            masked.add(new TranscriptEntry(speakerMasked ? OTHER_PERSON : entry.speakerAgentId(),
                    speakerMasked ? OTHER_PERSON : entry.speakerDisplayName(), entry.content(), entry.phaseIndex(), entry.phaseName(),
                    entry.type(), entry.timestamp(), entry.errorReason(), targetMasked ? OTHER_PERSON : entry.targetAgentId(),
                    entry.signature(), entry.signatureNonce(), entry.signatureTimestampMs(), entry.signatureKeyVersion()));
        }
        return masked;
    }

    /**
     * Whether {@code id} may stay in the subject's bundle: absent, the subject's
     * own, no person, or a known agent.
     */
    private static boolean recognisable(String id, String subjectId, Set<String> agents) {
        if (id == null || id.isBlank() || id.equals(subjectId) || namesNoPerson(id) || agents.contains(id)) {
            return true;
        }
        Optional<String> address = AddressNormalizer.tryNormalize(id);
        // The subject's own address, spelled differently (case, IDN form).
        return address.isPresent() && subjectId != null && address.equals(AddressNormalizer.tryNormalize(subjectId));
    }
}
