/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.attachments;

import ai.labs.eddi.engine.memory.model.Attachment;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * <em>The</em> set of files a conversation's model may name — defined once and
 * used by every tool that shares, reads or opens a file.
 * <p>
 * The listing is the roots {@link IAttachmentStore#listAccessible} returns for
 * the conversation (owned or granted), minus the roots the conversation
 * withholds — files of a turn an input guardrail refused. "Withheld" is state
 * of the conversation's memory, not of the blob, so the caller, who holds the
 * memory, computes it (usually
 * {@code AttachmentContextExtractor.withheldStorageRefs(memory)}) and passes it
 * in. A file that is not in the listing cannot be named, so it cannot be
 * shared: {@link AttachmentShareService#share} accepts only selectors of a
 * listing entry.
 * <p>
 * An entry's {@link Entry#displayName()} is what the model sees and writes
 * back: the file name, or {@value #UNNAMED} — never the {@code storageRef},
 * which is a capability a model must not hold. Entries are numbered from 1 in
 * listing order, so a model can also name an entry by its number. The order is
 * deterministic (display name, case-insensitive, then reference), so the same
 * files list the same way on every turn; but a file granted or uploaded later
 * can shift the numbers, so a number means something only against the listing
 * shown in the same turn.
 * <p>
 * A listing records the conversation it was built for and the roots that
 * conversation withholds, so {@link AttachmentShareService#share} can refuse a
 * listing built for another conversation and a withheld root even in a listing
 * built by hand.
 *
 * @param conversationId
 *            the conversation the listing was built for; {@code null} for the
 *            empty listing, which nothing can be shared from
 * @param entries
 *            the entries, in listing order
 * @param withheldRootRefs
 *            the roots the conversation withholds; never listed, never shared
 * @since 6.7.0
 */
public record ConversationListing(String conversationId, List<Entry> entries, Set<String> withheldRootRefs) {

    private static final Logger LOGGER = Logger.getLogger(ConversationListing.class);

    /** The display name of a file stored without a name. */
    public static final String UNNAMED = "(unnamed)";

    private static final ConversationListing EMPTY = new ConversationListing(null, List.of(), Set.of());

    /** Listing order: display name (case-insensitive), then the root reference. */
    private static final Comparator<Entry> ORDER = Comparator
            .comparing((Entry e) -> e.displayName().toLowerCase(Locale.ROOT))
            .thenComparing(Entry::displayName)
            .thenComparing(Entry::rootRef);

    public ConversationListing {
        entries = entries == null ? List.of() : List.copyOf(entries);
        withheldRootRefs = withheldRootRefs == null ? Set.of() : Set.copyOf(withheldRootRefs);
    }

    /**
     * One file the conversation may name.
     *
     * @param displayName
     *            what the model sees — the file name without surrounding
     *            whitespace, or {@value #UNNAMED}
     * @param selector
     *            the file (or recorded part) this entry stands for
     * @param mimeType
     *            the store-validated MIME type
     * @param sizeBytes
     *            the size of the stored blob
     * @param derived
     *            whether this is a recorded view of a root rather than a root
     */
    public record Entry(String displayName, AttachmentSelector selector, String mimeType, long sizeBytes, boolean derived) {

        public Entry {
            Objects.requireNonNull(displayName, "displayName");
            // A null selector fails here too, on the dereference.
            Objects.requireNonNull(selector.storageRef(), "selector.storageRef");
        }

        /**
         * The root blob the entry belongs to — the one access and grants are decided
         * on. Every entry is a root today; recorded derived views are added to the
         * listing in a later work package and carry their root in
         * {@link AttachmentSelector#META_ROOT}.
         */
        public String rootRef() {
            return selector.storageRef();
        }
    }

    /** A listing with no entries. */
    public static ConversationListing empty() {
        return EMPTY;
    }

    /**
     * Builds the listing of {@code conversationId}.
     * <p>
     * Never throws: no conversation id, no store or a failing store all give an
     * empty listing, so every name then resolves to "not found".
     *
     * @param conversationId
     *            the conversation whose model names files — the tool's bound
     *            conversation, never a model-supplied id
     * @param store
     *            the attachment store, or {@code null} when none is configured
     * @param withheldRootRefs
     *            roots this conversation withholds (may be {@code null})
     * @param recordedDerived
     *            derived selectors recorded on this conversation's steps. Not
     *            consulted yet: recorded parts join the listing together with unit
     *            resolution. Pass an empty list.
     */
    public static ConversationListing of(String conversationId, IAttachmentStore store, Set<String> withheldRootRefs,
                                         List<Attachment> recordedDerived) {
        if (conversationId == null || conversationId.isBlank() || store == null) {
            return EMPTY;
        }
        List<IAttachmentStore.Attachment> accessible;
        try {
            accessible = store.listAccessible(conversationId);
        } catch (RuntimeException e) {
            LOGGER.warnf("Could not list the attachments of conversation '%s': %s", sanitize(conversationId),
                    sanitize(e.getMessage()));
            return EMPTY;
        }
        if (accessible == null) {
            accessible = List.of();
        }
        Set<String> withheld = withheldRootRefs != null ? withheldRootRefs : Set.of();
        Set<String> withheldCopy = new HashSet<>();
        for (String ref : withheld) {
            if (ref != null) {
                withheldCopy.add(ref);
            }
        }
        // Keyed by storageRef: a blob both owned and granted is listed once.
        Map<String, Entry> unique = new LinkedHashMap<>();
        for (IAttachmentStore.Attachment a : accessible) {
            if (a == null || a.storageRef() == null || a.storageRef().isBlank() || withheld.contains(a.storageRef())) {
                continue;
            }
            // Stripped, because SelectorText strips what the model writes: a stored
            // "notes.txt " must be nameable as "notes.txt".
            String name = a.filename() != null && !a.filename().isBlank() ? a.filename().strip() : UNNAMED;
            unique.putIfAbsent(a.storageRef(),
                    new Entry(name, AttachmentSelector.wholeFile(a.storageRef()), a.mimeType(), a.sizeBytes(), false));
        }
        List<Entry> ordered = new ArrayList<>(unique.values());
        ordered.sort(ORDER);
        return new ConversationListing(conversationId, ordered, withheldCopy);
    }

    /**
     * Whether a root is withheld by the conversation this listing was built for.
     */
    public boolean isWithheld(String rootRef) {
        return rootRef != null && withheldRootRefs.contains(rootRef);
    }

    /** The display names, in listing order (duplicates kept). */
    public List<String> displayNames() {
        return entries.stream().map(Entry::displayName).toList();
    }

    /**
     * The entry a selector stands for: one with an equal selector.
     *
     * @return the entry, or empty when the selector is not in this listing
     */
    public Optional<Entry> entryFor(AttachmentSelector selector) {
        if (selector == null) {
            return Optional.empty();
        }
        return entries.stream().filter(e -> e.selector().equals(selector)).findFirst();
    }

    /**
     * The entry of a root, by its storage reference.
     *
     * @return the entry, or empty when the root is not in this listing
     */
    public Optional<Entry> entryForRoot(String storageRef) {
        if (storageRef == null) {
            return Optional.empty();
        }
        return entries.stream().filter(e -> storageRef.equals(e.rootRef())).findFirst();
    }

    /** Whether the listing has no entries. */
    public boolean isEmpty() {
        return entries.isEmpty();
    }
}
