/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.admin.model;

import java.util.List;

/**
 * Result of an orphan scan operation.
 */
public class OrphanReport {
    private int totalOrphans;
    private int deletedCount; // only set after purge
    private List<OrphanInfo> orphans;
    /**
     * Whether the reference scan behind this report completed.
     * <p>
     * A partial scan is missing references, and every resource whose only referrer
     * could not be read is then listed below as an "orphan". The purge refuses
     * outright in that case; the read-only scan still answers, so it has to say so
     * — the list is the operator's review surface for an irreversible deletion, and
     * without this flag a partial list is indistinguishable from a clean one.
     */
    private boolean scanComplete = true;
    /** Human-readable cause when {@link #scanComplete} is false. */
    private String scanWarning;

    public OrphanReport() {
    }

    public OrphanReport(int totalOrphans, int deletedCount, List<OrphanInfo> orphans) {
        this(totalOrphans, deletedCount, orphans, true, null);
    }

    public OrphanReport(int totalOrphans, int deletedCount, List<OrphanInfo> orphans, boolean scanComplete, String scanWarning) {
        this.totalOrphans = totalOrphans;
        this.deletedCount = deletedCount;
        this.orphans = orphans;
        this.scanComplete = scanComplete;
        this.scanWarning = scanWarning;
    }

    public boolean isScanComplete() {
        return scanComplete;
    }

    public void setScanComplete(boolean scanComplete) {
        this.scanComplete = scanComplete;
    }

    public String getScanWarning() {
        return scanWarning;
    }

    public void setScanWarning(String scanWarning) {
        this.scanWarning = scanWarning;
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
