package ai.labs.eddi.backup.model;

import java.util.List;

/**
 * Preview of what an import with merge strategy would do.
 * Returned by POST /backup/import/preview.
 */
public record ImportPreview(
        String botOriginId,
        String botName,
        List<ResourceDiff> resources
) {
    public record ResourceDiff(
            String originId,
            String resourceType,  // "bot", "package", "behavior", "httpcalls", etc.
            String name,
            DiffAction action,    // CREATE, UPDATE, SKIP
            String localId,       // null if CREATE, existing ID if UPDATE/SKIP
            Integer localVersion  // null if CREATE
    ) {
    }

    public enum DiffAction {CREATE, UPDATE, SKIP}
}
