/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.backup.model;

import java.net.URI;
import java.util.List;

/**
 * Outcome of an upgrade/sync run, returned in the body of
 * {@code POST /backup/import?strategy=upgrade},
 * {@code POST /backup/import/sync} and each entry of
 * {@code POST /backup/import/sync/batch}.
 * <p>
 * Per-resource failures used to be logged at WARN and then discarded: the agent
 * version was bumped anyway and the caller got 201 CREATED, so a half-applied
 * sync was indistinguishable from a clean one. Anything that did not land is
 * listed in {@link #failures()} and the endpoint answers 207 Multi-Status.
 *
 * @param agentUri
 *            the resulting agent URI — the new version when the agent config
 *            was rewritten, otherwise the version that was already there
 * @param agentUpdated
 *            whether the agent configuration itself was rewritten (a new agent
 *            version). False when nothing referenced by the agent changed
 * @param updated
 *            number of existing resources updated in place
 * @param created
 *            number of resources created in the target
 * @param skipped
 *            number of matched resources whose content was already identical
 * @param failures
 *            resources that could not be processed, with the reason
 * @since 6.0.0
 */
public record UpgradeResult(
        URI agentUri,
        boolean agentUpdated,
        int updated,
        int created,
        int skipped,
        List<ResourceFailure> failures) {

    /**
     * A resource the upgrade could not process.
     *
     * @param sourceId
     *            the resource's id in the source system
     * @param resourceType
     *            "workflow", "langchain", "snippet", …
     * @param name
     *            human-readable name, when the source knew one
     * @param reason
     *            why it failed
     */
    public record ResourceFailure(
            String sourceId,
            String resourceType,
            String name,
            String reason) {
    }

    public boolean hasFailures() {
        return failures != null && !failures.isEmpty();
    }

    /** Whether anything at all was written to the target. */
    public boolean wroteAnything() {
        return agentUpdated || updated > 0 || created > 0;
    }
}
