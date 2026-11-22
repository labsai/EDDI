/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.drive;

import ai.labs.eddi.configs.rag.model.IngestionSource.DriveFolder;
import ai.labs.eddi.configs.rag.model.IngestionSource.GoogleDriveSource;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * A Google Drive source's folder scope as retrieval applies it: which chunks of
 * the source may be returned, judged by the folder chain each chunk carries.
 *
 * <p>
 * Built from the source's <em>current</em> configuration and the folder ids its
 * paths were last resolved to, so a folder taken out of the scope or excluded
 * stops answering at once — not only after the next complete listing has
 * removed its vectors. A listing that never completes (throttled, over its time
 * budget) would otherwise leave them retrievable for good.
 *
 * <p>
 * What it cannot judge, it keeps: a chunk written before chains were recorded,
 * and a configured path no run has resolved yet. The next complete listing
 * settles both. A root path that is not resolved yet lifts the root restriction
 * (exclusions still apply), rather than hide every chunk of a source whose
 * scope was just narrowed to it.
 *
 * @param driveId
 *            the Shared Drive, the folder of a file at its top level
 * @param roots
 *            folder ids a chunk must lie under, or null for no restriction
 * @param excluded
 *            folder ids a chunk must not lie under
 * @param parents
 *            when subfolders are not read: the folder ids a chunk's own folder
 *            must be; null when subfolders are read
 */
public record DriveRetrievalScope(String driveId, Set<String> roots, Set<String> excluded, Set<String> parents) {

    /**
     * The scope of a source.
     *
     * @param recordedState
     *            what its runs recorded (the ingestion state's resolved scope), or
     *            null
     */
    public static DriveRetrievalScope of(GoogleDriveSource drive, String recordedState) {
        Map<String, String> recorded = DriveScope.recordedPaths(recordedState);
        Set<String> excluded = new LinkedHashSet<>();
        for (DriveFolder folder : drive.getExcludeFolders()) {
            if (folder.getId() != null && !folder.getId().isBlank()) {
                excluded.add(folder.getId());
            } else if (folder.getPath() != null) {
                String id = recorded.get(DriveScope.EXCLUDE + DriveScope.nfc(safePath(folder)));
                if (id != null) {
                    excluded.add(id);
                }
            }
        }
        Set<String> roots = null;
        if (drive.hasConfiguredFolders()) {
            roots = new LinkedHashSet<>();
            for (DriveFolder folder : drive.includedFolders()) {
                if (folder.getId() != null && !folder.getId().isBlank()) {
                    roots.add(folder.getId());
                    continue;
                }
                String id = folder.getPath() == null
                        ? null
                        : recorded.get(DriveScope.INCLUDE + DriveScope.nfc(safePath(folder)));
                if (id == null) {
                    roots = null;
                    break;
                }
                roots.add(id);
            }
        }
        Set<String> parents = null;
        if (!drive.isIncludeSubfolders()) {
            parents = roots == null && !drive.hasConfiguredFolders()
                    ? Set.of(drive.getSharedDriveId())
                    : roots;
        }
        return new DriveRetrievalScope(drive.getSharedDriveId(), roots == null ? null : Set.copyOf(roots),
                Set.copyOf(excluded), parents == null ? null : Set.copyOf(parents));
    }

    /** Whether this scope restricts anything at all. */
    public boolean restricts() {
        return roots != null || !excluded.isEmpty() || parents != null;
    }

    /**
     * Whether a chunk with this folder chain may be returned.
     *
     * @param chain
     *            the chunk's {@code folderIds}, {@code /a/b/}; null for a chunk
     *            written before chains were recorded, which is kept
     */
    public boolean admits(String chain) {
        if (chain == null || !chain.startsWith("/")) {
            return true;
        }
        String last = null;
        boolean underRoot = roots == null;
        for (String id : chain.split("/")) {
            if (id.isEmpty()) {
                continue;
            }
            if (excluded.contains(id)) {
                return false;
            }
            if (!underRoot && roots.contains(id)) {
                underRoot = true;
            }
            last = id;
        }
        if (!underRoot) {
            return false;
        }
        // A top-level file's chain is "/": its folder is the drive's root.
        return parents == null || parents.contains(last == null ? driveId : last);
    }

    private static String safePath(DriveFolder folder) {
        try {
            return folder.normalizedPath();
        } catch (IllegalArgumentException e) {
            return folder.getPath();
        }
    }
}
