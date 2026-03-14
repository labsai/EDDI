package ai.labs.eddi.configs.admin.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.net.URI;

/**
 * Represents an orphaned resource that is not referenced by any bot or package.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class OrphanInfo {
    private URI resourceUri;
    private String type;        // e.g., "ai.labs.package", "ai.labs.behavior"
    private String name;        // human-readable name from descriptor
    private boolean deleted;    // soft-deleted?
}
