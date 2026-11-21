/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools;

import ai.labs.eddi.engine.attachments.AttachmentShareService;
import ai.labs.eddi.engine.attachments.AttachmentShareService.Refusal;
import ai.labs.eddi.engine.attachments.ConversationListing;
import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.attachments.Resolution;
import ai.labs.eddi.engine.attachments.ShareParties;
import ai.labs.eddi.engine.attachments.SharePolicy;
import ai.labs.eddi.engine.attachments.ShareReason;
import ai.labs.eddi.engine.attachments.ShareResult;
import ai.labs.eddi.engine.memory.AttachmentContextExtractor;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.MemoryKeys;
import ai.labs.eddi.engine.memory.model.Data;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The files side of one turn's {@code converseWithAgent}: a per-turn object
 * bound to the <em>calling</em> conversation's memory, never cached, never
 * taking a conversation id from the model.
 * <p>
 * <strong>Names in, capabilities never (I1).</strong> The model names files of
 * its own conversation's {@link ConversationListing}; the binding resolves them
 * and asks {@link AttachmentShareService} to grant them to the target. The
 * target must be a conversation this conversation started — recorded on its own
 * steps under {@link MemoryKeys#DELEGATIONS_STARTED} by {@link #recordStarted}
 * the moment the tool starts one. A model-supplied {@code conversationId} that
 * is not recorded there receives nothing, even when it belongs to the same
 * user: being the same user's conversation says nothing about which agent is
 * behind it now.
 * <p>
 * The attachment store is touched only by {@link #shareTo} with at least one
 * name: building the binding, recording a start and checking a target read
 * memory only (I8).
 *
 * @since 6.7.0
 */
public final class AttachmentShareBinding {

    private final IConversationMemory memory;
    private final AttachmentShareService service;
    private final IAttachmentStore store;
    private final SharePolicy policy;

    /**
     * @param memory
     *            the calling conversation's memory — the source of every share
     * @param service
     *            the share rule
     * @param store
     *            the attachment store the listing is read from
     * @param policy
     *            the effective limits; {@code currentDepth} is how many hops the
     *            calling conversation's files have already travelled
     */
    public AttachmentShareBinding(IConversationMemory memory, AttachmentShareService service, IAttachmentStore store, SharePolicy policy) {
        this.memory = Objects.requireNonNull(memory, "memory");
        this.service = Objects.requireNonNull(service, "service");
        this.store = store;
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    /** How many hops the calling conversation's files have travelled. */
    public int currentDepth() {
        return policy.currentDepth();
    }

    /** The most files one call may share. */
    public int maxFilesPerShare() {
        return policy.maxFilesPerShare();
    }

    /**
     * Records that the calling conversation started {@code conversationId}, on its
     * current step. Read-modify-write: a turn may start several.
     */
    public void recordStarted(String conversationId) {
        IWritableConversationStep step = memory.getCurrentStep();
        if (conversationId == null || conversationId.isBlank() || step == null) {
            return;
        }
        List<String> started = new ArrayList<>(asStrings(step.getData(MemoryKeys.DELEGATIONS_STARTED)));
        if (!started.contains(conversationId)) {
            started.add(conversationId);
            step.storeData(new Data<>(MemoryKeys.DELEGATIONS_STARTED.key(), started));
        }
    }

    /**
     * Whether the calling conversation started {@code conversationId}: recorded on
     * any of its steps, the current one included. Memory only.
     */
    public boolean startedHere(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return false;
        }
        var allSteps = memory.getAllSteps();
        if (allSteps == null) {
            return false;
        }
        // Exact-key read: getAllLatestData would match by prefix.
        List<IData<Object>> perStep = allSteps.getExactDataPerStep(MemoryKeys.DELEGATIONS_STARTED.key());
        if (perStep == null) {
            return false;
        }
        for (IData<Object> data : perStep) {
            if (asStrings(data).contains(conversationId)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Shares the named files of the calling conversation with
     * {@code targetConversationId}, which must be one it started
     * ({@link #startedHere}); otherwise every name is refused and nothing is read
     * or granted. Names resolve against the conversation's listing (built here, on
     * demand, minus withheld files); the shared files are recorded on the current
     * step under {@link MemoryKeys#ATTACHMENTS_SHARED}.
     *
     * @param targetConversationId
     *            the conversation the tool is about to message
     * @param targetAgentId
     *            the agent behind it, for the audit entry and the step record
     * @param names
     *            what the model wrote; blank entries are ignored
     * @return what was shared and what was refused, in text the model may read
     */
    public ShareResult shareTo(String targetConversationId, String targetAgentId, List<String> names) {
        List<String> requested = names == null
                ? List.of()
                : names.stream().filter(n -> n != null && !n.isBlank()).toList();
        if (requested.isEmpty()) {
            return new ShareResult(List.of(), List.of());
        }
        if (!startedHere(targetConversationId)) {
            return refuseAll(requested, Refusal.TARGET);
        }
        String source = memory.getConversationId();
        ConversationListing listing = ConversationListing.of(source, store,
                AttachmentContextExtractor.withheldStorageRefsIncludingCurrentStep(memory),
                List.of());
        Resolution resolution = service.resolveNames(listing, requested);
        ShareParties parties = new ShareParties(memory.getUserId(), memory.getAgentId(), memory.getAgentVersion(), targetAgentId, null);
        ShareResult result = service.share(source, listing, resolution.selectors(), targetConversationId, ShareReason.DELEGATION, policy,
                parties);
        List<ShareResult.Refused> refused = new ArrayList<>(resolution.refused());
        refused.addAll(result.refused());
        ShareResult merged = new ShareResult(result.shared(), refused);
        recordShared(merged.toStepRecords(targetAgentId, ShareReason.DELEGATION));
        return merged;
    }

    /**
     * Every named file refused for one reason, without reading the store: for a
     * target that may not receive files.
     */
    public ShareResult refuseAll(List<String> names, Refusal reason) {
        List<ShareResult.Refused> refused = names == null
                ? List.of()
                : names.stream().filter(n -> n != null && !n.isBlank()).map(n -> new ShareResult.Refused(echo(n), reason.text())).toList();
        return new ShareResult(List.of(), refused);
    }

    /** Appends to {@code attachments:shared} on the current step. */
    private void recordShared(List<Map<String, String>> rows) {
        IWritableConversationStep step = memory.getCurrentStep();
        if (rows.isEmpty() || step == null) {
            return;
        }
        List<Object> all = new ArrayList<>();
        IData<List<Map<String, String>>> existing = step.getData(MemoryKeys.ATTACHMENTS_SHARED);
        if (existing != null && existing.getResult() != null) {
            all.addAll(existing.getResult());
        }
        all.addAll(rows);
        step.storeData(new Data<>(MemoryKeys.ATTACHMENTS_SHARED.key(), all));
    }

    /** What the model wrote, as a refusal echoes it: stripped and bounded. */
    private static String echo(String raw) {
        String s = raw.strip();
        int max = AttachmentShareService.MAX_ECHO_CHARS;
        if (s.length() <= max) {
            return s;
        }
        // Never cut between the halves of a surrogate pair.
        int end = Character.isHighSurrogate(s.charAt(max - 1)) ? max - 1 : max;
        return s.substring(0, end) + "…";
    }

    /**
     * The string elements of a stored list (live, or reloaded from the store).
     */
    private static List<String> asStrings(IData<?> data) {
        if (data == null || !(data.getResult() instanceof List<?> list)) {
            return List.of();
        }
        List<String> strings = new ArrayList<>();
        for (Object o : list) {
            if (o != null) {
                strings.add(o.toString());
            }
        }
        return strings;
    }
}
