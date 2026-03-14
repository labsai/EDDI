package ai.labs.eddi.configs.admin.model;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

/**
 * Result of an orphan scan operation.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class OrphanReport {
    private int totalOrphans;
    private int deletedCount;          // only set after purge
    private List<OrphanInfo> orphans;
}
