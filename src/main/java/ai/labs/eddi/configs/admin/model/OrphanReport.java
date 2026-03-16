package ai.labs.eddi.configs.admin.model;


import java.util.List;

/**
 * Result of an orphan scan operation.
 */
public class OrphanReport {
    private int totalOrphans;
    private int deletedCount;          // only set after purge
    private List<OrphanInfo> orphans;

    public OrphanReport() {
    }

    public OrphanReport(int totalOrphans, int deletedCount, List<OrphanInfo> orphans) {
        this.totalOrphans = totalOrphans;
        this.deletedCount = deletedCount;
        this.orphans = orphans;
    }

    public int getTotalOrphans() {
        return totalOrphans;
    }

    public void setTotalOrphans(int totalOrphans) {
        this.totalOrphans = totalOrphans;
    }

    public int getDeletedCount() {
        return deletedCount;
    }

    public void setDeletedCount(int deletedCount) {
        this.deletedCount = deletedCount;
    }

    public List<OrphanInfo> getOrphans() {
        return orphans;
    }

    public void setOrphans(List<OrphanInfo> orphans) {
        this.orphans = orphans;
    }
}
