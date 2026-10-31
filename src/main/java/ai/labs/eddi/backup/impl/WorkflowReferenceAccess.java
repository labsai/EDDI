/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.configs.workflows.rest.RestWorkflowStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.utils.RestUtilities;

import java.net.URI;
import java.util.HashSet;
import java.util.Set;

/**
 * The workflow-reference rule of {@code RestWorkflowStore}, for the backup
 * paths that write a workflow straight to its store.
 * <p>
 * An import and an upgrade create workflows through {@code IWorkflowStore}
 * directly, so the check the REST store runs on every create — every
 * configuration a step names needs {@code VIEW}, an agent or group it names
 * needs {@code USE} — never ran. The backup side only rewrites the references
 * it recognises by exact pattern, while the engine resolves more spellings than
 * that (legacy authorities, for one), so a reference it did not recognise
 * survived into the stored workflow pointing at another space's private
 * configuration: read back through an export of the caller's own agent, or run
 * in a conversation. The references are walked by the same code the REST store
 * uses ({@link RestWorkflowStore#referencedResources}), which keys by id
 * whatever the authority.
 * <p>
 * A no-op unless workspaces are enforced, and for administrators — exactly as
 * on the REST path. References to resources this very import or upgrade just
 * created pass, because their creator owns them.
 */
final class WorkflowReferenceAccess {

    private WorkflowReferenceAccess() {
    }

    /**
     * @throws io.quarkus.security.ForbiddenException
     *             naming the first reference the caller may not use
     */
    static void requireAccessToReferences(ResourceAccessGuard guard, WorkflowConfiguration workflow) {
        requireAccessToReferences(guard, workflow, Set.of());
    }

    /**
     * @param exempt
     *            references, keyed {@code <host>|<id>} like
     *            {@link RestWorkflowStore#referencedResources}, that the caller is
     *            about to replace with resources it creates itself — checked before
     *            those exist, so a refusal leaves nothing behind
     */
    static void requireAccessToReferences(ResourceAccessGuard guard, WorkflowConfiguration workflow, Set<String> exempt) {
        RestWorkflowStore.requireAccessToReferences(guard, workflow, exempt);
    }

    /**
     * The resource ids a workflow's structural references name — the ones the
     * authoring-time check has already vetted.
     */
    static Set<String> referencedIds(WorkflowConfiguration workflow) {
        Set<String> ids = new HashSet<>();
        for (URI uri : RestWorkflowStore.referencedResources(workflow).values()) {
            ids.add(RestUtilities.extractResourceId(uri).getId());
        }
        return ids;
    }

    /**
     * The key {@link RestWorkflowStore#referencedResources} files a reference
     * under.
     */
    static String keyOf(URI uri) {
        return uri.getHost() + "|" + RestUtilities.extractResourceId(uri).getId();
    }
}
