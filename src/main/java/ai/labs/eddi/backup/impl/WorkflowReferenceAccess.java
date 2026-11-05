/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.impl;

import ai.labs.eddi.configs.descriptors.model.AccessLevel;
import ai.labs.eddi.configs.parser.model.ParserConfiguration;
import ai.labs.eddi.configs.workflows.model.WorkflowConfiguration;
import ai.labs.eddi.configs.workflows.rest.RestWorkflowStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.utils.RestUtilities;

import java.net.URI;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
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
     * The resource ids a workflow's structural references name with {@code VIEW} —
     * the ones the authoring-time check has already vetted for reading.
     * <p>
     * An agent or group reference is left out: the authoring check gave it only
     * {@code USE}, and an id is an id whatever the authority it is spelled under.
     * An agent-shaped reference carrying a configuration's id used to exempt that
     * configuration, named elsewhere in the workflow, from the export's own
     * {@code VIEW} check — so {@code USE} on it was enough to carry its content
     * away.
     */
    static Set<String> referencedIds(WorkflowConfiguration workflow) {
        Set<String> ids = new HashSet<>();
        for (URI uri : RestWorkflowStore.referencedResources(workflow).values()) {
            if (!USE_ONLY_REFERENCE_TYPES.contains(uri.getHost())) {
                ids.add(RestUtilities.extractResourceId(uri).getId());
            }
        }
        return ids;
    }

    /**
     * The reference types {@code RestWorkflowStore} checks for {@code USE} only;
     * the same set as its own.
     */
    private static final Set<String> USE_ONLY_REFERENCE_TYPES = Set.of("ai.labs.agent", "ai.labs.group");

    /**
     * {@code RestParserStore}'s rule for a parser document written straight to its
     * store: every dictionary (or other resource) its extensions name needs
     * {@code VIEW}. Import and upgrade create parsers through {@code IParserStore},
     * so a parser could keep naming a private dictionary the archive left out, and
     * the standalone parse endpoint then matched input against that dictionary's
     * expressions. Resources this run created pass, because their creator owns
     * them.
     *
     * @throws io.quarkus.security.ForbiddenException
     *             naming the first reference the caller may not read
     */
    static void requireAccessToParserReferences(ResourceAccessGuard guard, ParserConfiguration parser) {
        if (guard == null || guard.seesEverything() || parser == null) {
            return;
        }
        Map<String, URI> references = new LinkedHashMap<>();
        RestWorkflowStore.addExtensionReferences(parser.getExtensions(), references);
        for (URI uri : references.values()) {
            guard.requireAccess(RestUtilities.extractResourceId(uri).getId(), AccessLevel.VIEW,
                    "configuration referenced by this parser (" + uri.getHost() + ")");
        }
    }

    /**
     * The key {@link RestWorkflowStore#referencedResources} files a reference
     * under.
     */
    static String keyOf(URI uri) {
        return uri.getHost() + "|" + RestUtilities.extractResourceId(uri).getId();
    }
}
