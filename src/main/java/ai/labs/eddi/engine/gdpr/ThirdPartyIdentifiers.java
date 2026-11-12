/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.gdpr;

import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntry;
import ai.labs.eddi.configs.groups.model.GroupConversation.TranscriptEntryType;
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
     * The transcript of {@code discussion} with every human member other than the
     * subject masked — speaker and target id and display name. Agent members stay
     * as they are: an agent id is configuration, not a person.
     * <p>
     * A transcript entry does not record whether its speaker was a human, so the
     * humans are taken from what does: the speakers of {@code HUMAN_INPUT} entries,
     * the member a pending human turn waits on, and any speaker or target id that
     * is an e-mail address (the usual principal of a human member, and never an
     * agent id).
     */
    public static List<TranscriptEntry> transcriptFor(GroupConversation discussion, String subjectId) {
        List<TranscriptEntry> stored = discussion.getTranscript();
        if (stored == null || stored.isEmpty()) {
            return stored;
        }
        List<TranscriptEntry> transcript;
        synchronized (stored) {
            // The model's list is a synchronized list; iterating it needs the lock.
            transcript = new ArrayList<>(stored);
        }
        Set<String> humans = new HashSet<>();
        for (TranscriptEntry entry : transcript) {
            if (entry != null && entry.type() == TranscriptEntryType.HUMAN_INPUT && entry.speakerAgentId() != null) {
                humans.add(entry.speakerAgentId());
            }
        }
        if (discussion.getPendingHumanInput() != null && discussion.getPendingHumanInput().memberId() != null) {
            humans.add(discussion.getPendingHumanInput().memberId());
        }
        List<TranscriptEntry> masked = new ArrayList<>(transcript.size());
        for (TranscriptEntry entry : transcript) {
            if (entry == null) {
                masked.add(null);
                continue;
            }
            boolean speakerMasked = isOtherHuman(entry.speakerAgentId(), subjectId, humans);
            boolean targetMasked = isOtherHuman(entry.targetAgentId(), subjectId, humans);
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

    private static boolean isOtherHuman(String id, String subjectId, Set<String> humans) {
        if (id == null || id.isBlank() || id.equals(subjectId) || namesNoPerson(id)) {
            return false;
        }
        Optional<String> address = AddressNormalizer.tryNormalize(id);
        if (address.isPresent() && subjectId != null && address.equals(AddressNormalizer.tryNormalize(subjectId))) {
            // The subject's own address, spelled differently (case, IDN form).
            return false;
        }
        return humans.contains(id) || address.isPresent();
    }
}
