/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments;

import ai.labs.eddi.engine.audit.AuditLedgerService;
import ai.labs.eddi.engine.audit.model.AuditEntry;
import ai.labs.eddi.utils.LogSanitizer;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The one rule for handing a file from one conversation to another — used by
 * every agent-to-agent path (delegation, group tasks, nested groups, handoff,
 * A2A, MCP).
 * <p>
 * <strong>Names in, capabilities never.</strong> A model picks entries of its
 * own conversation's {@link ConversationListing}; it never supplies a
 * {@code storageRef}, a source conversation or a target conversation. The
 * source is the conversation the calling tool is bound to and the target one
 * the caller created, both decided by server code. {@link #share} therefore
 * accepts a selector only when it is an entry of the listing it is given, and
 * re-checks with {@link #mayRead} that the source can still read the file, so a
 * stale or hand-built listing cannot widen access.
 * <p>
 * Each share grants the target read access to the file's <em>root</em> — access
 * to a stored view is decided on its root — counts
 * {@code eddi.attachment.shared{path}} and writes one audit entry to the
 * <em>source</em> conversation's ledger. A refusal counts
 * {@code eddi.attachment.share.refused{reason}} and uses up no slot of either
 * cap. Without an attachment store every method refuses; none throws.
 *
 * @since 6.7.0
 */
@ApplicationScoped
public class AttachmentShareService {

    private static final Logger LOGGER = Logger.getLogger(AttachmentShareService.class);

    /** Counter of shared files, tagged {@code path} ({@link ShareReason#tag()}). */
    public static final String SHARED_METER = "eddi.attachment.shared";
    /**
     * Counter of refused requests, tagged {@code reason} ({@link Refusal#tag()}).
     */
    public static final String REFUSED_METER = "eddi.attachment.share.refused";

    /** The requested name of a refusal for a file the source cannot name. */
    public static final String UNKNOWN_FILE = "(unknown file)";

    /** {@code taskId} of a share's audit entry. */
    public static final String AUDIT_TASK_ID = "attachment.share";
    /** {@code taskType} of a share's audit entry. */
    public static final String AUDIT_TASK_TYPE = "attachments";

    /**
     * Why a request was refused: the metric tag and the text the model reads. The
     * text never says whether a file it cannot name exists elsewhere.
     */
    public enum Refusal {
        NOT_FOUND("not found"), AMBIGUOUS("ambiguous"), PART("parts of a file cannot be shared yet; share the whole file"), MIME_TYPE(
                "this kind of file may not be shared"), DEPTH("files cannot be passed on any further from here"), FILES_PER_SHARE(
                        "too many files for one share"), SHARES_PER_TURN("too many files shared this turn"), TARGET(
                                "files can only be shared with a conversation started for it"), GRANT_FAILED(
                                        "the file could not be shared"), UNAVAILABLE("file sharing is not available");

        private final String text;

        Refusal(String text) {
            this.text = text;
        }

        /** The model-facing text. */
        public String text() {
            return text;
        }

        /** The metric tag value. */
        public String tag() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private final Instance<IAttachmentStore> storeInstance;
    private final IAttachmentStore directStore;
    private final MeterRegistry meterRegistry;
    private final AuditLedgerService auditLedger;

    @Inject
    public AttachmentShareService(Instance<IAttachmentStore> attachmentStore, MeterRegistry meterRegistry,
            AuditLedgerService auditLedger) {
        this.storeInstance = attachmentStore;
        this.directStore = null;
        this.meterRegistry = meterRegistry;
        this.auditLedger = auditLedger;
    }

    /**
     * Direct construction, for code that holds a store but no CDI context (the
     * group attachment binder's two-argument constructor) and for tests.
     *
     * @param attachmentStore
     *            the store, or {@code null} — every method then refuses
     * @param meterRegistry
     *            the registry, or {@code null} for no metrics
     * @param auditLedger
     *            the ledger, or {@code null} for no audit entries
     */
    public AttachmentShareService(IAttachmentStore attachmentStore, MeterRegistry meterRegistry, AuditLedgerService auditLedger) {
        this.storeInstance = null;
        this.directStore = attachmentStore;
        this.meterRegistry = meterRegistry;
        this.auditLedger = auditLedger;
    }

    private IAttachmentStore store() {
        if (storeInstance != null) {
            return storeInstance.isResolvable() ? storeInstance.get() : null;
        }
        return directStore;
    }

    /**
     * The capability rule: can {@code conversationId} read {@code storageRef}, as
     * its owner or a grantee? Decided by the store's own owner-or-grant check.
     *
     * @return {@code false} for a blank argument, an absent store, an unknown blob,
     *         a denied read or a store failure
     */
    public boolean mayRead(String storageRef, String conversationId) {
        IAttachmentStore store = store();
        if (store == null || storageRef == null || storageRef.isBlank() || conversationId == null || conversationId.isBlank()) {
            return false;
        }
        try {
            return store.getMetadata(storageRef, conversationId) != null;
        } catch (Exception e) {
            LOGGER.debugf("Conversation '%s' may not read an attachment: %s", LogSanitizer.sanitize(conversationId),
                    LogSanitizer.sanitize(e.getMessage()));
            return false;
        }
    }

    /**
     * Resolves what a model wrote to entries of {@code listing}: an exact display
     * name first, then a 1-based entry number. A name that matches several entries
     * is refused with their numbers; a name with a part suffix ({@code #u3},
     * {@code #p4-9}) is refused, since parts cannot be shared yet. Anything else is
     * "not found" — the same text whether the file exists somewhere else or
     * nowhere, so the answer is no oracle.
     */
    public Resolution resolveNames(ConversationListing listing, List<String> names) {
        ConversationListing list = listing != null ? listing : ConversationListing.empty();
        List<ConversationListing.Entry> resolved = new ArrayList<>();
        List<ShareResult.Refused> refused = new ArrayList<>();
        if (names == null) {
            return new Resolution(resolved, refused);
        }
        List<String> displayNames = list.displayNames();
        for (String raw : names) {
            SelectorText.Parsed parsed = SelectorText.parse(raw, displayNames);
            String requested = raw == null ? "" : raw.strip();
            if (parsed.hasPart()) {
                refuse(refused, requested, Refusal.PART);
                continue;
            }
            List<Integer> matches = new ArrayList<>();
            for (int i = 0; i < displayNames.size(); i++) {
                if (!parsed.name().isEmpty() && displayNames.get(i).equals(parsed.name())) {
                    matches.add(i);
                }
            }
            if (matches.size() > 1) {
                String candidates = matches.stream().map(i -> String.valueOf(i + 1)).collect(Collectors.joining(", "));
                countRefusal(Refusal.AMBIGUOUS);
                refused.add(new ShareResult.Refused(requested,
                        "ambiguous: " + matches.size() + " files have this name; name one by its number (" + candidates + ")"));
                continue;
            }
            Integer index = matches.isEmpty() ? listingNumber(parsed.name(), displayNames.size()) : matches.get(0);
            if (index == null) {
                refuse(refused, requested, Refusal.NOT_FOUND);
                continue;
            }
            ConversationListing.Entry entry = list.entries().get(index);
            if (!resolved.contains(entry)) {
                resolved.add(entry);
            }
        }
        return new Resolution(resolved, refused);
    }

    /** The 0-based index a 1-based entry number names, or {@code null}. */
    private static Integer listingNumber(String name, int size) {
        if (name.isEmpty() || name.length() > 9 || !name.chars().allMatch(Character::isDigit)) {
            return null;
        }
        int number = Integer.parseInt(name);
        return number >= 1 && number <= size ? number - 1 : null;
    }

    /**
     * Shares files of the source conversation with the target conversation.
     * <p>
     * Source and target are derived by the <strong>caller</strong> (server code),
     * never by a model. Per selector, in order: it must be an entry of
     * {@code listing}, whose root the source can read ({@link #mayRead} — so a
     * stale or another conversation's listing widens nothing); a whole file (parts
     * cannot be shared yet); of an allowed MIME type; the source must be below
     * {@code maxDepth}; the call below {@code maxFilesPerShare}; the turn below
     * {@code maxSharesPerTurn}. Then the root is granted to the target, the share
     * is counted against both caps, metered and audited. A selector repeated in the
     * same call is shared once.
     * <p>
     * There is deliberately no overload without {@code parties}: the user id on the
     * audit entry is what lets the ledger pseudonymise it when the user is erased,
     * so every caller has to say who it is (or pass {@link ShareParties#UNKNOWN} on
     * purpose).
     *
     * @param sourceConversationId
     *            the conversation the calling tool is bound to
     * @param listing
     *            the source conversation's listing
     * @param selectors
     *            what to share — each must be an entry of {@code listing}
     * @param targetConversationId
     *            a conversation the caller created for this purpose
     * @param reason
     *            the path the share takes
     * @param policy
     *            the limits
     * @param parties
     *            who the share is between, for the audit entry; {@code null} is
     *            read as {@link ShareParties#UNKNOWN}
     */
    public ShareResult share(String sourceConversationId, ConversationListing listing, List<AttachmentSelector> selectors,
                             String targetConversationId, ShareReason reason, SharePolicy policy, ShareParties parties) {
        List<ShareResult.Shared> shared = new ArrayList<>();
        List<ShareResult.Refused> refused = new ArrayList<>();
        if (selectors == null || selectors.isEmpty()) {
            return new ShareResult(shared, refused);
        }
        ConversationListing list = listing != null ? listing : ConversationListing.empty();
        IAttachmentStore store = store();
        Refusal callRefusal = null;
        if (store == null || reason == null || policy == null) {
            callRefusal = Refusal.UNAVAILABLE;
        } else if (isBlank(sourceConversationId) || isBlank(targetConversationId)
                || sourceConversationId.equals(targetConversationId)) {
            callRefusal = Refusal.TARGET;
        }
        int sharedByThisCall = 0;
        Set<AttachmentSelector> seen = new HashSet<>();
        for (AttachmentSelector selector : selectors) {
            if (selector != null && !seen.add(selector)) {
                // The same file asked for twice in one call: answered once.
                continue;
            }
            if (callRefusal != null) {
                // Nothing in this call is shared, so no file is looked up: no store
                // reads, and no name of a file the source might not be able to read.
                refuse(refused, UNKNOWN_FILE, callRefusal);
                continue;
            }
            Optional<ConversationListing.Entry> listed = selector != null ? list.entryForRoot(selector.storageRef()) : Optional.empty();
            // A file the listing does not hold has no name the model may see.
            String name = listed.map(ConversationListing.Entry::displayName).orElse(UNKNOWN_FILE);
            if (listed.isEmpty()) {
                refuse(refused, name, Refusal.NOT_FOUND);
                continue;
            }
            // The listing must be this source's: a stale or hand-built one cannot widen
            // access, and a file the source cannot read keeps its name to itself.
            if (!mayRead(listed.get().rootRef(), sourceConversationId)) {
                refuse(refused, UNKNOWN_FILE, Refusal.NOT_FOUND);
                continue;
            }
            if (!selector.isWholeFile()) {
                refuse(refused, name, Refusal.PART);
                continue;
            }
            Optional<ConversationListing.Entry> entry = list.entryFor(selector);
            if (entry.isEmpty()) {
                refuse(refused, name, Refusal.NOT_FOUND);
                continue;
            }
            ConversationListing.Entry e = entry.get();
            if (!policy.allowsMimeType(e.mimeType())) {
                refuse(refused, name, Refusal.MIME_TYPE);
                continue;
            }
            if (policy.currentDepth() >= policy.maxDepth()) {
                refuse(refused, name, Refusal.DEPTH);
                continue;
            }
            if (sharedByThisCall >= policy.maxFilesPerShare()) {
                refuse(refused, name, Refusal.FILES_PER_SHARE);
                continue;
            }
            if (policy.sharesThisTurn().get() >= policy.maxSharesPerTurn()) {
                refuse(refused, name, Refusal.SHARES_PER_TURN);
                continue;
            }
            String root = e.rootRef();
            try {
                store.grantAccess(root, targetConversationId);
            } catch (Exception ex) {
                LOGGER.warnf("Could not grant conversation '%s' an attachment of conversation '%s': %s",
                        LogSanitizer.sanitize(targetConversationId), LogSanitizer.sanitize(sourceConversationId),
                        LogSanitizer.sanitize(ex.getMessage()));
                refuse(refused, name, Refusal.GRANT_FAILED);
                continue;
            }
            sharedByThisCall++;
            policy.sharesThisTurn().increment();
            shared.add(new ShareResult.Shared(e.selector(), e.displayName(), e.mimeType(), e.sizeBytes()));
            if (meterRegistry != null) {
                meterRegistry.counter(SHARED_METER, "path", reason.tag()).increment();
            }
            audit(sourceConversationId, targetConversationId, reason, e, parties != null ? parties : ShareParties.UNKNOWN);
        }
        return new ShareResult(shared, refused);
    }

    private void refuse(List<ShareResult.Refused> refused, String name, Refusal refusal) {
        countRefusal(refusal);
        refused.add(new ShareResult.Refused(name, refusal.text()));
    }

    private void countRefusal(Refusal refusal) {
        if (meterRegistry != null) {
            meterRegistry.counter(REFUSED_METER, "reason", refusal.tag()).increment();
        }
    }

    /**
     * One entry in the source conversation's ledger. It records the root, the
     * target, the reason and the file's MIME type and size — no bytes and no file
     * name. Never throws into the share path.
     */
    private void audit(String sourceConversationId, String targetConversationId, ShareReason reason,
                       ConversationListing.Entry entry, ShareParties parties) {
        if (auditLedger == null || !auditLedger.isEnabled()) {
            return;
        }
        try {
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("reason", reason.name());
            output.put("storageRef", entry.rootRef());
            output.put("targetConversationId", targetConversationId);
            if (parties.targetAgentId() != null) {
                output.put("targetAgentId", parties.targetAgentId());
            }
            if (entry.mimeType() != null) {
                output.put("mimeType", entry.mimeType());
            }
            output.put("sizeBytes", entry.sizeBytes());
            if (!entry.selector().isWholeFile()) {
                output.putAll(entry.selector().toMetadata());
            }
            auditLedger.submit(new AuditEntry(UUID.randomUUID().toString(), sourceConversationId, parties.sourceAgentId(),
                    parties.sourceAgentVersion(), parties.userId(), parties.environment(), -1, AUDIT_TASK_ID, AUDIT_TASK_TYPE, -1,
                    0L, Map.of(), output, null, null, List.of(), 0.0, Instant.now(), null, null));
        } catch (RuntimeException ex) {
            LOGGER.warnf("Failed to audit an attachment share of conversation '%s': %s", LogSanitizer.sanitize(sourceConversationId),
                    LogSanitizer.sanitize(ex.getMessage()));
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
