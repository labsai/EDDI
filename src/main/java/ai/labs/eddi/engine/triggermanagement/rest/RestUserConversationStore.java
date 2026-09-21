/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.triggermanagement.rest;

import ai.labs.eddi.engine.triggermanagement.IRestUserConversationStore;
import ai.labs.eddi.engine.triggermanagement.IUserConversationStore;
import ai.labs.eddi.datastore.IResourceStore;
import ai.labs.eddi.engine.caching.ICache;
import ai.labs.eddi.engine.caching.ICacheFactory;
import ai.labs.eddi.engine.security.OwnershipValidator;
import ai.labs.eddi.engine.triggermanagement.model.UserConversation;
import io.quarkus.security.identity.SecurityIdentity;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.BadRequestException;

import static ai.labs.eddi.engine.exception.SneakyThrow.sneakyThrow;

/**
 * @author ginccc
 */

@ApplicationScoped
public class RestUserConversationStore implements IRestUserConversationStore {
    private static final String CACHE_NAME = "userConversations";
    private final IUserConversationStore userConversationStore;
    private final ICache<String, UserConversation> userConversationCache;
    private final SecurityIdentity identity;
    private final OwnershipValidator ownershipValidator;

    @Inject
    public RestUserConversationStore(IUserConversationStore userConversationStore, ICacheFactory cacheFactory,
            SecurityIdentity identity, OwnershipValidator ownershipValidator) {
        this.userConversationStore = userConversationStore;
        this.identity = identity;
        this.ownershipValidator = ownershipValidator;
        userConversationCache = cacheFactory.getCache(CACHE_NAME);
    }

    @Override
    public UserConversation readUserConversation(String intent, String userId) {
        ownershipValidator.validateUserAccess(identity, userId);
        try {
            String cacheKey = calculateCacheKey(intent, userId);
            UserConversation userConversation = userConversationCache.get(cacheKey);
            if (userConversation == null) {
                userConversation = userConversationStore.readUserConversation(intent, userId);
                if (userConversation != null) {
                    userConversationCache.put(cacheKey, userConversation);
                }
            }

            if (userConversation == null) {
                String message = "UserConversation with intent=%s and userId=%s does not exist.";
                message = String.format(message, intent, userId);
                throw new IResourceStore.ResourceNotFoundException(message);
            }

            return userConversation;

        } catch (IResourceStore.ResourceStoreException | IResourceStore.ResourceNotFoundException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public Response createUserConversation(String intent, String userId, UserConversation userConversation) {
        ownershipValidator.validateUserAccess(identity, userId);
        // The guard above authorises the PATH userId, but the body carries its own
        // intent/userId and it is the BODY that gets persisted. Left unchecked, a
        // caller authorised for their own path could post a body naming someone
        // else and write that user's mapping — the exact bypass the guard exists to
        // prevent — while the cache entry went in under the path key, so a later
        // read for (intent, path user) would serve the other user's record.
        // Reject a divergence rather than silently rewriting it: a mismatch is
        // either an attack or a client bug, and both deserve to be seen.
        if (userConversation == null) {
            // The Mongo store rejects null with checkNotNull, which surfaces as a 500.
            // A missing body is a client error, so say so.
            throw new BadRequestException("a UserConversation body is required");
        }
        if (userConversation.getUserId() != null && !userConversation.getUserId().equals(userId)) {
            throw new BadRequestException("userId in the request body must match the userId in the path");
        }
        if (userConversation.getIntent() != null && !userConversation.getIntent().equals(intent)) {
            throw new BadRequestException("intent in the request body must match the intent in the path");
        }
        // A body that omits them inherits the authorised path values.
        userConversation.setUserId(userId);
        userConversation.setIntent(intent);
        try {
            userConversationStore.createUserConversation(userConversation);
            userConversationCache.put(calculateCacheKey(intent, userId), userConversation);
            return Response.ok().build();
        } catch (IResourceStore.ResourceAlreadyExistsException | IResourceStore.ResourceStoreException e) {
            throw sneakyThrow(e);
        }
    }

    @Override
    public Response deleteUserConversation(String intent, String userId) {
        ownershipValidator.validateUserAccess(identity, userId);
        try {
            userConversationStore.deleteUserConversation(intent, userId);
            userConversationCache.remove(calculateCacheKey(intent, userId));
            return Response.ok().build();
        } catch (IResourceStore.ResourceStoreException e) {
            throw sneakyThrow(e);
        }
    }

    static String calculateCacheKey(String intent, String userId) {
        return intent + "::" + userId;
    }
}
