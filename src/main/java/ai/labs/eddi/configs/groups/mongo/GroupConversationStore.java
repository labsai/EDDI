/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.mongo;

import ai.labs.eddi.configs.groups.IGroupConversationStore;
import ai.labs.eddi.configs.groups.model.GroupConversation;
import ai.labs.eddi.datastore.IResourceFilter;
import ai.labs.eddi.datastore.IResourceStorage;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.serialization.IDocumentBuilder;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * DB-agnostic store for group conversation transcripts. Uses
 * {@link IResourceStorageFactory} to create storage backed by either MongoDB or
 * PostgreSQL.
 * <p>
 * Group conversations are non-versioned entities (single version = 1).
 *
 * @author ginccc
 */
@ApplicationScoped
public class GroupConversationStore implements IGroupConversationStore {

    private static final Logger LOGGER = Logger.getLogger(GroupConversationStore.class);
    private static final int SINGLE_VERSION = 1;

    /**
     * Ids are hex ObjectIds or UUIDs — no regex metacharacters. This makes plain
     * anchoring ({@code ^id$}) an exact match on BOTH regex engines. Never use
     * {@link Pattern#quote} here: its {@code \Q...\E} output is Java-specific and
     * rejected by PostgreSQL's regex engine (the DB-agnostic findResources maps
     * String filters to {@code ~} on Postgres and to a Mongo regex on MongoDB).
     */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9-]+");

    private final IResourceStorage<GroupConversation> storage;

    @Inject
    public GroupConversationStore(IResourceStorageFactory storageFactory, IDocumentBuilder documentBuilder) {
        this.storage = storageFactory.create("groupconversations", documentBuilder, GroupConversation.class, "groupId", "state");
    }

    @Override
    public String create(GroupConversation conversation) throws IResourceStore.ResourceStoreException {
        try {
            IResourceStorage.IResource<GroupConversation> resource = storage.newResource(conversation);
            storage.store(resource);
            String id = resource.getId();
            conversation.setId(id);
            return id;
        } catch (IOException e) {
            throw new IResourceStore.ResourceStoreException("Failed to create group conversation: " + e.getMessage(), e);
        }
    }

    @Override
    public GroupConversation read(String id) throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {
        try {
            IResourceStorage.IResource<GroupConversation> resource = storage.read(id, SINGLE_VERSION);
            if (resource == null) {
                throw new IResourceStore.ResourceNotFoundException("Group conversation not found.");
            }
            GroupConversation conversation = resource.getData();
            conversation.setId(id);
            return conversation;
        } catch (IOException e) {
            throw new IResourceStore.ResourceStoreException("Failed to read group conversation: " + e.getMessage(), e);
        }
    }

    @Override
    public void update(GroupConversation conversation) throws IResourceStore.ResourceStoreException {
        try {
            IResourceStorage.IResource<GroupConversation> resource = storage.newResource(conversation.getId(), SINGLE_VERSION, conversation);
            storage.store(resource);
        } catch (IOException e) {
            throw new IResourceStore.ResourceStoreException("Failed to update group conversation: " + e.getMessage(), e);
        }
    }

    @Override
    public void delete(String id) throws IResourceStore.ResourceStoreException {
        storage.removeAllPermanently(id);
    }

    @Override
    public List<GroupConversation> listByGroupId(String groupId, int index, int limit) throws IResourceStore.ResourceStoreException {
        // For now, use a simple approach — filter by groupId.
        // The IResourceStorage.findResources() API can be used for more complex
        // queries.
        // This is a placeholder that works with both DB backends.
        var results = new ArrayList<GroupConversation>();
        if (groupId == null || groupId.isBlank() || !SAFE_ID.matcher(groupId).matches()) {
            // no stored group can carry such an id — honest empty result, and the
            // value never reaches either backend's regex engine
            LOGGER.warnf("listByGroupId called with non-id value — returning empty");
            return results;
        }
        try {
            // Anchored exact match (see SAFE_ID): findResources turns a String
            // filter value into an unanchored regex, so a raw groupId would
            // substring-match other groups.
            var filter = new ai.labs.eddi.datastore.IResourceFilter.QueryFilters(
                    java.util.List.of(new ai.labs.eddi.datastore.IResourceFilter.QueryFilter(
                            "groupId", "^" + groupId + "$")));
            var resourceIds = storage.findResources(new ai.labs.eddi.datastore.IResourceFilter.QueryFilters[]{filter}, "lastModified", index, limit);
            for (var resourceId : resourceIds) {
                try {
                    var resource = storage.read(resourceId.getId(), SINGLE_VERSION);
                    if (resource != null) {
                        GroupConversation gc = resource.getData();
                        gc.setId(resourceId.getId());
                        results.add(gc);
                    }
                } catch (IOException e) {
                    LOGGER.warnf("Failed to read group conversation %s: %s", resourceId.getId(), e.getMessage());
                }
            }
        } catch (Exception e) {
            throw new IResourceStore.ResourceStoreException("Failed to list group conversations: " + e.getMessage(), e);
        }
        return results;
    }

    @Override
    public boolean compareAndSetState(String id, GroupConversation.GroupConversationState expectedState,
                                      GroupConversation.GroupConversationState newState)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceNotFoundException {
        GroupConversation gc = read(id);
        if (gc.getState() != expectedState) {
            // Fast path: clearly the wrong state — no need to attempt the write.
            return false;
        }
        gc.setState(newState);
        gc.setLastModified(java.time.Instant.now());
        try {
            // Conditional write — the persisted state must STILL be expectedState. The
            // read-check above alone was a read-check-update (single-node only): two
            // racing callers could both pass it and both write. storeIfFieldEquals makes
            // the transition atomic across processes.
            updateIfState(gc, expectedState);
            return true;
        } catch (IResourceStore.ResourceModifiedException e) {
            // Another writer transitioned the conversation between our read and our
            // write — this CAS lost the race.
            return false;
        } catch (GroupConversationGoneException e) {
            throw new IResourceStore.ResourceNotFoundException(
                    "Group conversation no longer exists.");
        }
    }

    @Override
    public void updateIfState(GroupConversation gc, GroupConversation.GroupConversationState expectedState)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceModifiedException {
        try {
            IResourceStorage.IResource<GroupConversation> resource = storage.newResource(gc.getId(), SINGLE_VERSION, gc);
            storage.storeIfFieldEquals(resource, "state", expectedState.name());
        } catch (IResourceStore.ResourceNotFoundException e) {
            // deleted-vs-mismatch distinction from the storage CAS: surface the
            // deletion as its own (unchecked) type so callers can answer 404
            throw new GroupConversationGoneException(
                    "Group conversation no longer exists.", e);
        } catch (IOException e) {
            throw new IResourceStore.ResourceStoreException("Failed conditional update: " + e.getMessage(), e);
        }
    }

    /**
     * Upper bound on erasure pages, purely so a pathological store cannot spin
     * forever. At {@link IResourceStorage#MAX_RESULT_LIMIT} per pass this covers
     * far more transcripts than any single user plausibly has.
     */
    private static final int MAX_ERASURE_PASSES = 1_000;

    /**
     * Delete every group-conversation transcript belonging to {@code userId} (GDPR
     * Art. 17 erasure).
     * <p>
     * A {@link GroupConversation} stores the user's id alongside the verbatim
     * transcript of the discussion — the original question and every agent turn —
     * so a cascade that skipped this store left the user's own words behind after
     * an erasure that reported success.
     * <p>
     * Candidates are fetched with an anchored, escaped regex and then
     * <em>re-checked in Java</em> with an exact string comparison before deletion.
     * The storage layer turns a String filter into a regex on both backends (Mongo
     * {@code $regex}, Postgres {@code ~}) whose metacharacter handling is not
     * identical, and an over-broad match here would delete another user's
     * transcripts. The regex therefore only narrows the scan; the equality check
     * decides.
     *
     * @param userId
     *            the user whose transcripts to erase
     * @return number of transcripts deleted
     */
    public long deleteAllForUser(String userId) throws IResourceStore.ResourceStoreException {
        if (userId == null || userId.isBlank()) {
            return 0;
        }

        long deleted = 0;
        var processed = new HashSet<String>();
        try {
            var filter = new IResourceFilter.QueryFilters(
                    List.of(new IResourceFilter.QueryFilter("userId", "^" + escapeRegex(userId) + "$")));

            // findResources caps any page at MAX_RESULT_LIMIT (a limit < 1 does NOT
            // mean "unbounded" — it resolves to the cap). A single call would erase
            // at most 10,000 transcripts and then report success, leaving the rest of
            // the user's words in the store; a partial erasure that claims to be
            // complete is the worst outcome available here. Page until a pass finds
            // nothing. Rows are removed as we go, so the next page is always fetched
            // from offset 0.
            for (int pass = 0; pass < MAX_ERASURE_PASSES; pass++) {
                var resourceIds = storage.findResources(
                        new IResourceFilter.QueryFilters[]{filter}, "lastModified", 0, IResourceStorage.MAX_RESULT_LIMIT);
                if (resourceIds == null || resourceIds.isEmpty()) {
                    return deleted;
                }

                long newThisPass = 0;
                for (var resourceId : resourceIds) {
                    if (!processed.add(resourceId.getId())) {
                        // already handled in an earlier pass — the query is handing back
                        // rows we have dealt with, so this page carries no new work
                        continue;
                    }
                    newThisPass++;
                    try {
                        var resource = storage.read(resourceId.getId(), SINGLE_VERSION);
                        if (resource == null) {
                            continue;
                        }
                        if (!userId.equals(resource.getData().getUserId())) {
                            // regex matched more than it should have — never delete on it
                            LOGGER.warnf("Skipping group conversation %s during erasure: userId is not an exact match", resourceId.getId());
                            continue;
                        }
                        storage.removeAllPermanently(resourceId.getId());
                        deleted++;
                    } catch (IOException e) {
                        LOGGER.warnf("Failed to erase group conversation %s: %s", resourceId.getId(), e.getMessage());
                    }
                }

                // No row in this page was new. Either every candidate was rejected by
                // the exact-match re-check, or the store keeps returning rows we have
                // already removed. Re-querying would return the same page forever, so
                // stop rather than spin. Termination therefore does not depend on the
                // delete actually taking effect.
                if (newThisPass == 0) {
                    LOGGER.warnf("Erasure made no progress with %d candidate(s) still matching; stopping", resourceIds.size());
                    return deleted;
                }
            }
            LOGGER.warnf("Erasure stopped after %d passes; more group conversations may remain", MAX_ERASURE_PASSES);
        } catch (Exception e) {
            throw new IResourceStore.ResourceStoreException("Failed to delete group conversations for user: " + e.getMessage(), e);
        }
        return deleted;
    }

    /**
     * Backslash-escape the regex metacharacters shared by both backends' engines.
     * Deliberately not {@link Pattern#quote}: its {@code \Q...\E} form is
     * Java-specific and PostgreSQL rejects it.
     */
    private static String escapeRegex(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if ("\\^$.|?*+()[]{}".indexOf(c) >= 0) {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.toString();
    }

    @Override
    public List<GroupConversation> findByState(GroupConversation.GroupConversationState state)
            throws IResourceStore.ResourceStoreException {
        return findByState(state, null, 1000);
    }

    @Override
    public List<GroupConversation> findByState(GroupConversation.GroupConversationState state, String groupId, int limit)
            throws IResourceStore.ResourceStoreException {
        var filterList = new ArrayList<ai.labs.eddi.datastore.IResourceFilter.QueryFilter>();
        filterList.add(new ai.labs.eddi.datastore.IResourceFilter.QueryFilter("state", "^" + state.name() + "$"));
        if (groupId != null) {
            if (!SAFE_ID.matcher(groupId).matches()) {
                LOGGER.warnf("findByState called with non-id groupId — returning empty");
                return new ArrayList<>();
            }
            // Anchored exact match (see SAFE_ID): a raw groupId would leak
            // conversations from other groups whose id contains it as a substring.
            filterList.add(new ai.labs.eddi.datastore.IResourceFilter.QueryFilter(
                    "groupId", "^" + groupId + "$"));
        }
        var filters = new ai.labs.eddi.datastore.IResourceFilter.QueryFilters[]{
                new ai.labs.eddi.datastore.IResourceFilter.QueryFilters(filterList)};
        List<IResourceStore.IResourceId> ids = storage.findResources(filters, "lastModified", 0, limit);
        List<GroupConversation> out = new ArrayList<>();
        for (var id : ids) {
            try {
                GroupConversation gc = read(id.getId());
                if (gc != null)
                    out.add(gc);
            } catch (IResourceStore.ResourceNotFoundException e) {
                LOGGER.warnf("Group conversation %s disappeared during findByState: %s", id.getId(), e.getMessage());
            } catch (IResourceStore.ResourceStoreException e) {
                // A single unreadable record (e.g. wrapped IOException) must not abort
                // the whole scan — this backs crash recovery and pending-approvals
                // listing. Log the id and continue (mirrors listByGroupId).
                LOGGER.warnf("Failed to read group conversation %s during findByState: %s", id.getId(), e.getMessage());
            }
        }
        if (ids.size() >= limit) {
            // never truncate silently — callers (pending listings, crash recovery)
            // must be able to see that there were more results than the cap
            LOGGER.warnf("findByState(%s) hit its limit of %d — results are truncated", state, limit);
        }
        return out;
    }
}
