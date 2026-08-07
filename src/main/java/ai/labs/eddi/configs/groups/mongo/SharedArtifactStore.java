/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.groups.mongo;

import ai.labs.eddi.configs.groups.ISharedArtifactStore;
import ai.labs.eddi.configs.groups.model.SharedArtifact;
import ai.labs.eddi.datastore.IResourceFilter;
import ai.labs.eddi.datastore.IResourceStorage;
import ai.labs.eddi.datastore.IResourceStorageFactory;
import ai.labs.eddi.datastore.IResourceStore;
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
 * DB-agnostic store for {@link SharedArtifact}s (I17). Mirrors
 * {@link GroupConversationStore}'s single-version runtime-document shape (the
 * {@code mongo} package name is historic — this class is backend-neutral via
 * {@link IResourceStorageFactory}).
 * <p>
 * The version CAS goes through the numeric
 * {@code storeIfFieldEquals(resource, "version", long)} overload: the
 * artifact's {@code version} is a JSON number, and MongoDB's typed BSON
 * equality would never match it against a string.
 *
 * @author ginccc
 */
@ApplicationScoped
public class SharedArtifactStore implements ISharedArtifactStore {

    private static final Logger LOGGER = Logger.getLogger(SharedArtifactStore.class);

    private static final int SINGLE_VERSION = 1;

    /**
     * Both backends turn a String filter into an UNANCHORED regex — ids must be
     * validated before they are interpolated into one. Group-conversation ids are
     * Mongo ObjectIds or UUIDs on the two backends, both within this set.
     */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9-]+");

    /** Same spin bound and rationale as {@code GroupConversationStore}. */
    private static final int MAX_ERASURE_PASSES = 1_000;

    private final IResourceStorage<SharedArtifact> storage;

    @Inject
    public SharedArtifactStore(IResourceStorageFactory storageFactory, IDocumentBuilder documentBuilder) {
        this.storage = storageFactory.create("sharedartifacts", documentBuilder, SharedArtifact.class,
                "groupConversationId", "ownerUserId");
    }

    @Override
    public String create(SharedArtifact artifact) throws IResourceStore.ResourceStoreException {
        try {
            IResourceStorage.IResource<SharedArtifact> resource = storage.newResource(artifact);
            storage.store(resource);
            String id = resource.getId();
            artifact.setId(id);
            return id;
        } catch (IOException e) {
            throw new IResourceStore.ResourceStoreException("Failed to create shared artifact: " + e.getMessage(), e);
        }
    }

    @Override
    public SharedArtifact read(String id) throws IResourceStore.ResourceNotFoundException, IResourceStore.ResourceStoreException {
        try {
            IResourceStorage.IResource<SharedArtifact> resource = storage.read(id, SINGLE_VERSION);
            if (resource == null) {
                // Deliberately does not embed the caller-supplied id — reflected-value
                // sink, same rule as GroupConversationStore.read.
                throw new IResourceStore.ResourceNotFoundException("Shared artifact not found.");
            }
            SharedArtifact artifact = resource.getData();
            artifact.setId(id);
            return artifact;
        } catch (IOException e) {
            throw new IResourceStore.ResourceStoreException("Failed to read shared artifact: " + e.getMessage(), e);
        }
    }

    @Override
    public void updateIfVersion(SharedArtifact artifact, long expectedVersion)
            throws IResourceStore.ResourceStoreException, IResourceStore.ResourceModifiedException {
        try {
            IResourceStorage.IResource<SharedArtifact> resource = storage.newResource(artifact.getId(), SINGLE_VERSION, artifact);
            storage.storeIfFieldEquals(resource, "version", expectedVersion);
        } catch (IResourceStore.ResourceNotFoundException e) {
            // Unchecked, so CAS call sites can tell "gone" (404) from a genuine
            // version conflict (409) — same shape as GroupConversationGoneException.
            throw new ArtifactGoneException("Shared artifact no longer exists.", e);
        } catch (IOException e) {
            throw new IResourceStore.ResourceStoreException("Failed to update shared artifact: " + e.getMessage(), e);
        }
    }

    @Override
    public void delete(String id) throws IResourceStore.ResourceStoreException {
        try {
            storage.removeAllPermanently(id);
        } catch (Exception e) {
            throw new IResourceStore.ResourceStoreException("Failed to delete shared artifact: " + e.getMessage(), e);
        }
    }

    @Override
    public List<SharedArtifact> listByGroupConversationId(String groupConversationId) throws IResourceStore.ResourceStoreException {
        if (groupConversationId == null || !SAFE_ID.matcher(groupConversationId).matches()) {
            return List.of();
        }
        try {
            var filter = new IResourceFilter.QueryFilters(
                    List.of(new IResourceFilter.QueryFilter("groupConversationId", "^" + groupConversationId + "$")));
            var resourceIds = storage.findResources(
                    new IResourceFilter.QueryFilters[]{filter}, "createdAt", 0, IResourceStorage.MAX_RESULT_LIMIT);
            List<SharedArtifact> artifacts = new ArrayList<>();
            for (var resourceId : resourceIds) {
                try {
                    var resource = storage.read(resourceId.getId(), SINGLE_VERSION);
                    if (resource == null) {
                        continue;
                    }
                    SharedArtifact artifact = resource.getData();
                    // The anchored regex only narrows; equality decides — same
                    // narrow-then-recheck rule as every other cross-document filter.
                    if (!groupConversationId.equals(artifact.getGroupConversationId())) {
                        continue;
                    }
                    artifact.setId(resourceId.getId());
                    artifacts.add(artifact);
                } catch (IOException e) {
                    LOGGER.warnf("Skipping unreadable shared artifact %s: %s", resourceId.getId(), e.getMessage());
                }
            }
            return artifacts;
        } catch (Exception e) {
            throw new IResourceStore.ResourceStoreException("Failed to list shared artifacts: " + e.getMessage(), e);
        }
    }

    @Override
    public long deleteByGroupConversationId(String groupConversationId) throws IResourceStore.ResourceStoreException {
        if (groupConversationId == null || !SAFE_ID.matcher(groupConversationId).matches()) {
            return 0;
        }
        long deleted = 0;
        // maxArtifactsPerDiscussion bounds a discussion's artifacts far below one
        // page, but the loop stays honest anyway: delete until a pass finds nothing.
        for (int pass = 0; pass < MAX_ERASURE_PASSES; pass++) {
            List<SharedArtifact> artifacts = listByGroupConversationId(groupConversationId);
            if (artifacts.isEmpty()) {
                return deleted;
            }
            for (SharedArtifact artifact : artifacts) {
                delete(artifact.getId());
                deleted++;
            }
        }
        return deleted;
    }

    @Override
    public long deleteAllForUser(String userId) throws IResourceStore.ResourceStoreException {
        if (userId == null || userId.isBlank()) {
            return 0;
        }
        long deleted = 0;
        var processed = new HashSet<String>();
        try {
            var filter = new IResourceFilter.QueryFilters(
                    List.of(new IResourceFilter.QueryFilter("ownerUserId", "^" + escapeRegex(userId) + "$")));

            // Page until an empty pass, always from offset 0 (rows are removed as we
            // go); fail loudly on an owned row that will not delete. Contract and
            // reasoning identical to GroupConversationStore.deleteAllForUser.
            for (int pass = 0; pass < MAX_ERASURE_PASSES; pass++) {
                var resourceIds = storage.findResources(
                        new IResourceFilter.QueryFilters[]{filter}, "createdAt", 0, IResourceStorage.MAX_RESULT_LIMIT);
                if (resourceIds == null || resourceIds.isEmpty()) {
                    return deleted;
                }

                long newThisPass = 0;
                long failedToDelete = 0;
                for (var resourceId : resourceIds) {
                    if (!processed.add(resourceId.getId())) {
                        continue;
                    }
                    newThisPass++;
                    try {
                        var resource = storage.read(resourceId.getId(), SINGLE_VERSION);
                        if (resource == null) {
                            continue;
                        }
                        if (!userId.equals(resource.getData().getOwnerUserId())) {
                            // regex matched more than it should have — never delete on it
                            LOGGER.warnf("Skipping shared artifact %s during erasure: ownerUserId is not an exact match", resourceId.getId());
                            continue;
                        }
                        storage.removeAllPermanently(resourceId.getId());
                        deleted++;
                    } catch (IOException e) {
                        failedToDelete++;
                        LOGGER.warnf("Failed to erase shared artifact %s: %s", resourceId.getId(), e.getMessage());
                    }
                }

                if (failedToDelete > 0) {
                    throw new IResourceStore.ResourceStoreException(
                            "Erasure incomplete: " + failedToDelete + " shared artifact(s) belonging to the user could not be deleted after "
                                    + deleted + " successful deletion(s)");
                }
                if (newThisPass == 0) {
                    return deleted;
                }
            }
            LOGGER.warnf("Erasure stopped after %d passes; more shared artifacts may remain", MAX_ERASURE_PASSES);
        } catch (IResourceStore.ResourceStoreException e) {
            throw e;
        } catch (Exception e) {
            throw new IResourceStore.ResourceStoreException("Failed to delete shared artifacts for user: " + e.getMessage(), e);
        }
        return deleted;
    }

    /**
     * Backslash-escape the regex metacharacters shared by both backends' engines.
     * Not {@link Pattern#quote}: its {@code \Q...\E} form is Java-specific and
     * PostgreSQL rejects it.
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
}
