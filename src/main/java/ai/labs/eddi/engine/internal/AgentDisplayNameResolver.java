/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.internal;

import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * The display name of the agent a conversation talks to, for the people taking
 * part in it.
 * <p>
 * The name lives on the agent's descriptor, and the descriptor store is an
 * authoring endpoint ({@code eddi-admin}, {@code eddi-editor}): it also carries
 * the owner, the grants and the audit fields, and its sibling stores hold the
 * configuration. A chat user holding only {@code eddi-user} was therefore
 * refused, and the Chat UI could never show whom they were talking to. Rather
 * than open the descriptor store to end users, the conversation read — which
 * the chat already makes, and which is already scoped to the conversation's
 * owner — carries the one display field it needs.
 * <p>
 * Only the name is resolved, and only for a caller who may
 * {@link AccessLevel#USE use} the agent — the same gate {@code POST
 * /agents/{agentId}/start} applies. A caller who owns a conversation but has
 * since lost access to its agent keeps the transcript and loses the name.
 * Anything that cannot be resolved answers {@code null}: a name is decoration
 * and never a reason to fail a read.
 */
@ApplicationScoped
public class AgentDisplayNameResolver {

    private static final Logger LOGGER = Logger.getLogger(AgentDisplayNameResolver.class);

    private final ResourceAccessGuard resourceAccessGuard;
    private final IDocumentDescriptorStore documentDescriptorStore;

    @Inject
    public AgentDisplayNameResolver(ResourceAccessGuard resourceAccessGuard, IDocumentDescriptorStore documentDescriptorStore) {
        this.resourceAccessGuard = resourceAccessGuard;
        this.documentDescriptorStore = documentDescriptorStore;
    }

    /**
     * The name of {@code agentVersion} of {@code agentId}, or {@code null} when the
     * caller may not use the agent, or it has no readable, non-blank name.
     */
    public String resolve(String agentId, Integer agentVersion) {
        if (agentId == null || agentId.isBlank() || agentVersion == null) {
            return null;
        }
        try {
            if (!resourceAccessGuard.hasAccess(agentId, AccessLevel.USE)) {
                return null;
            }
            DocumentDescriptor descriptor = documentDescriptorStore.readDescriptor(agentId, agentVersion);
            String name = descriptor != null ? descriptor.getName() : null;
            return name == null || name.isBlank() ? null : name;
        } catch (Exception e) {
            LOGGER.debugf("Could not resolve the display name of agent %s: %s", sanitize(agentId), e.getMessage());
            return null;
        }
    }
}
