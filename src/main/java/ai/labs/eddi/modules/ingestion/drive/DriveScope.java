/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.drive;

import ai.labs.eddi.configs.rag.model.IngestionSource.DriveFolder;
import ai.labs.eddi.configs.rag.model.IngestionSource.GoogleDriveSource;
import ai.labs.eddi.modules.ingestion.connector.SourceError;
import ai.labs.eddi.modules.ingestion.connector.SourceException;
import ai.labs.eddi.modules.ingestion.drive.DriveApi.DriveApiException;
import ai.labs.eddi.modules.ingestion.drive.DriveApi.Folder;
import ai.labs.eddi.modules.ingestion.drive.DriveApi.FolderPage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * The folders one run of a Google Drive source reads and the subtrees it leaves
 * out, resolved to ids.
 *
 * <h2>Paths</h2>
 * <p>
 * A configured path is resolved from the drive's root one folder name at a
 * time, against the subfolders Drive lists for the level above. Names are
 * compared in Unicode NFC — a name typed on one system and stored decomposed by
 * another is the same name — exactly first, then ignoring case. Two folders
 * that match at one level are an error naming both ids: guessing would read one
 * team's folder in place of another's.
 *
 * <p>
 * The id a path resolved to is recorded in the source's ingestion state, and
 * later runs read that id rather than the path. A folder renamed or moved
 * within the drive therefore keeps being read, and the run reports where it is
 * now. Only when the recorded folder is gone — deleted, in the trash, moved to
 * another drive — is the path resolved again.
 *
 * <h2>Exclusion wins</h2>
 * <p>
 * An excluded folder is never listed, and neither is anything under it: a
 * configured folder that lies inside an excluded one is dropped, and a shortcut
 * whose target lies inside one is not followed. The walk's own path says
 * nothing about a shortcut target's real place in the drive, so its ancestors
 * are looked up.
 *
 * <h2>Failing rather than shrinking</h2>
 * <p>
 * A folder to read that cannot be found fails the run. Read as "nothing there",
 * a typo or a deleted folder would let a complete listing reconcile its
 * documents away. A folder to exclude that cannot be found is reported and
 * ignored only when it was resolved before and has since gone — there is
 * nothing left in the drive to exclude; one that never resolved fails the run,
 * since a mistyped exclusion would read what was meant to stay out.
 */
final class DriveScope {

    /** Subfolders one path level may have; a level with more is refused. */
    static final int MAX_FOLDERS_PER_LEVEL = 10_000;

    /** Folders above any folder of a drive; a deeper chain is refused. */
    static final int MAX_ANCESTRY = 64;

    private static final String INCLUDE = "include:";
    private static final String EXCLUDE = "exclude:";
    private static final ObjectMapper JSON = new ObjectMapper();

    /** A folder this run reads from, and how the configuration named it. */
    record Root(String id, String label) {
    }

    /** Whether a folder found during the listing may be read. */
    enum Admission {
        ADMIT, EXCLUDED, UNKNOWN
    }

    private final DriveApi api;
    private final GoogleDriveSource drive;
    private final Map<String, JsonNode> metadata = new HashMap<>();
    private final Map<String, Admission> admissions = new HashMap<>();
    private final List<Root> roots = new ArrayList<>();
    private final Set<String> excluded = new LinkedHashSet<>();
    private final List<String> warnings = new ArrayList<>();
    private final Map<String, String> resolved = new LinkedHashMap<>();
    private final Map<String, String> recorded;

    private DriveScope(DriveApi api, GoogleDriveSource drive, Map<String, String> recorded) {
        this.api = api;
        this.drive = drive;
        this.recorded = recorded;
    }

    /**
     * Resolves a source's configured folders and exclusions.
     *
     * @param recorded
     *            what an earlier run recorded, from {@link #recordedState()}; empty
     *            for none
     * @throws SourceException
     *             a folder to read is missing, ambiguous, outside the drive or not
     *             listable; or every folder to read is excluded
     */
    static DriveScope resolve(DriveApi api, GoogleDriveSource drive, Optional<String> recorded)
            throws SourceException {
        DriveScope scope = new DriveScope(api, drive, parseRecorded(recorded.orElse(null)));
        scope.resolveExclusions();
        scope.resolveRoots();
        return scope;
    }

    /** The folders to list, in configuration order, without repeats. */
    List<Root> roots() {
        return List.copyOf(roots);
    }

    /** The ids whose subtrees are not read. */
    Set<String> excluded() {
        return Set.copyOf(excluded);
    }

    /**
     * What the run should report: renamed folders, dropped roots, gone exclusions.
     */
    List<String> warnings() {
        return List.copyOf(warnings);
    }

    /**
     * A signature of what this scope reads: the root ids, the excluded ids and
     * whether subfolders are read. Ids, not paths, so renaming a folder is not a
     * scope change, and resolving the same path to another folder is.
     */
    String signature() {
        StringBuilder text = new StringBuilder("drive=").append(drive.getSharedDriveId())
                .append("\nsubfolders=").append(drive.isIncludeSubfolders()).append("\nroots=");
        text.append(String.join(",", new TreeSet<>(roots.stream().map(Root::id).toList())));
        text.append("\nexcluded=").append(String.join(",", new TreeSet<>(excluded)));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8));
            return "drive-scope:" + HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /**
     * What to record for the next run: the id every configured path resolved to.
     * Null when nothing changed since what was recorded, so an unchanged scope
     * costs no write.
     */
    String recordedState() {
        if (resolved.equals(recorded)) {
            return null;
        }
        ObjectNode node = JSON.createObjectNode();
        ObjectNode paths = node.putObject("paths");
        resolved.forEach(paths::put);
        return node.toString();
    }

    /**
     * Whether a folder found during the listing may be read. A subfolder found by
     * descending is checked by id alone — its ancestors are the walk's. A shortcut
     * target is checked by its real ancestors too, which costs one metadata request
     * per folder not seen before, and only when there is anything to exclude.
     *
     * @return {@link Admission#UNKNOWN} when the target's place in the drive could
     *         not be established, which the caller must not read as coverage
     */
    Admission admit(String folderId, boolean viaShortcut) {
        if (excluded.contains(folderId)) {
            return Admission.EXCLUDED;
        }
        if (!viaShortcut || excluded.isEmpty()) {
            return Admission.ADMIT;
        }
        Admission known = admissions.get(folderId);
        if (known != null) {
            return known;
        }
        Admission admission;
        try {
            admission = underExclusion(folderId) ? Admission.EXCLUDED : Admission.ADMIT;
        } catch (SourceException e) {
            admission = Admission.UNKNOWN;
        }
        admissions.put(folderId, admission);
        return admission;
    }

    private void resolveExclusions() throws SourceException {
        for (DriveFolder folder : drive.getExcludeFolders()) {
            if (hasId(folder)) {
                String id = folder.getId();
                if (id.equals(drive.getSharedDriveId())) {
                    throw rejected("googleDrive.excludeFolders names the Shared Drive itself, which excludes "
                            + "everything");
                }
                Optional<JsonNode> meta = existingFolder(id);
                if (meta.isEmpty()) {
                    warnings.add("excluded folder " + id + " no longer exists in the drive; nothing is excluded for it");
                    continue;
                }
                excluded.add(id);
            } else {
                String key = EXCLUDE + nfc(folder.normalizedPath());
                Optional<String> id = resolveRecordedOrFresh(folder, key, false);
                id.ifPresent(excluded::add);
            }
        }
    }

    private void resolveRoots() throws SourceException {
        if (!drive.hasConfiguredFolders()) {
            roots.add(new Root(drive.getSharedDriveId(), "the drive's root"));
            return;
        }
        Set<String> seen = new LinkedHashSet<>();
        int dropped = 0;
        for (DriveFolder folder : drive.includedFolders()) {
            String id;
            String label;
            if (hasId(folder)) {
                id = folder.getId();
                label = folder.getLabel() != null && !folder.getLabel().isBlank()
                        ? folder.getLabel()
                        : "folder " + id;
                if (!id.equals(drive.getSharedDriveId())) {
                    requireReadableFolder(id, meta(id));
                }
            } else {
                String key = INCLUDE + nfc(folder.normalizedPath());
                id = resolveRecordedOrFresh(folder, key, true).orElseThrow();
                label = "'" + folder.normalizedPath() + "'";
            }
            if (!seen.add(id)) {
                continue;
            }
            if (excluded.contains(id) || (!excluded.isEmpty() && underExclusion(id))) {
                // Exclusion wins at any depth, including over a folder the
                // configuration names explicitly.
                warnings.add(label + " lies inside googleDrive.excludeFolders and is not read");
                dropped++;
                continue;
            }
            roots.add(new Root(id, label));
        }
        if (roots.isEmpty() && dropped > 0) {
            throw rejected("Every folder googleDrive.folders names lies inside googleDrive.excludeFolders, so the "
                    + "source would read nothing — and a complete listing of nothing would remove every document "
                    + "it ingested. Remove the exclusion or the folders.");
        }
    }

    /**
     * The id a configured path stands for: the recorded one while that folder still
     * exists in the drive, otherwise the path resolved now.
     *
     * @param required
     *            a folder to read, which must resolve; an exclusion that was
     *            recorded and has since gone resolves to empty
     */
    private Optional<String> resolveRecordedOrFresh(DriveFolder folder, String key, boolean required)
            throws SourceException {
        String path = folder.normalizedPath();
        String field = required ? "googleDrive.folders" : "googleDrive.excludeFolders";
        String recordedId = recorded.get(key);
        if (recordedId != null && GoogleDriveSource.DRIVE_ID.matcher(recordedId).matches()) {
            Optional<JsonNode> meta = existingFolder(recordedId);
            if (meta.isPresent()) {
                String now = currentPath(recordedId);
                if (now != null && !samePath(path, now)) {
                    warnings.add("'" + path + "' is now '" + now + "'; it is still read by its id " + recordedId
                            + ". Update " + field + " to the new path to keep the configuration readable.");
                }
                if (required) {
                    requireReadableFolder(recordedId, meta.get());
                }
                resolved.put(key, recordedId);
                return Optional.of(recordedId);
            }
            Optional<String> fresh = resolvePath(path, field, false);
            if (fresh.isPresent()) {
                warnings.add("The folder '" + path + "' was resolved to (" + recordedId + ") is gone; '" + path
                        + "' now resolves to " + fresh.get());
                if (required) {
                    requireReadableFolder(fresh.get(), meta(fresh.get()));
                }
                resolved.put(key, fresh.get());
                return fresh;
            }
            if (required) {
                throw new SourceException(new SourceError.Auth("The folder '" + path + "' of " + field + " (id "
                        + recordedId + ") was deleted, moved out of the Shared Drive or put in the trash, and no "
                        + "folder has that path now. Restore it, or remove it from " + field + "."));
            }
            warnings.add("The excluded folder '" + path + "' is gone from the drive; nothing is excluded for it");
            return Optional.empty();
        }
        String id = resolvePath(path, field, true).orElseThrow();
        if (required) {
            requireReadableFolder(id, meta(id));
        }
        resolved.put(key, id);
        return Optional.of(id);
    }

    /**
     * Walks a path from the drive's root.
     *
     * @param failIfMissing
     *            throw when a level has no such folder; otherwise answer empty
     * @throws SourceException
     *             a level with two matching folders, always
     */
    private Optional<String> resolvePath(String path, String field, boolean failIfMissing) throws SourceException {
        String parent = drive.getSharedDriveId();
        StringBuilder walked = new StringBuilder();
        for (String segment : DriveFolder.segmentsOf(path)) {
            List<Folder> children = subfolders(parent, walked.isEmpty() ? "the drive's root" : "'" + walked + "'");
            List<Folder> matches = match(segment, children);
            String where = walked.isEmpty() ? "the drive's root" : "'" + walked + "'";
            if (matches.size() > 1) {
                throw rejected(field + " path '" + path + "' is ambiguous: " + where + " holds " + matches.size()
                        + " folders named '" + segment + "' (" + String.join(", ",
                                matches.stream().map(Folder::id).toList())
                        + "). Name the one you mean by its id instead.");
            }
            if (matches.isEmpty()) {
                if (!failIfMissing) {
                    return Optional.empty();
                }
                throw rejected(field + " path '" + path + "' does not exist: " + where + " has no folder named '"
                        + segment + "' that the service account can see");
            }
            parent = matches.get(0).id();
            if (!walked.isEmpty()) {
                walked.append('/');
            }
            walked.append(matches.get(0).name());
        }
        return Optional.of(parent);
    }

    /**
     * The folders at one level that a name matches: exact matches in NFC if there
     * are any, otherwise those that match ignoring case.
     */
    static List<Folder> match(String segment, List<Folder> children) {
        String wanted = nfc(segment);
        List<Folder> exact = new ArrayList<>();
        List<Folder> folded = new ArrayList<>();
        String wantedFolded = wanted.toLowerCase(Locale.ROOT);
        for (Folder child : children) {
            String name = nfc(child.name());
            if (name.equals(wanted)) {
                exact.add(child);
            } else if (name.toLowerCase(Locale.ROOT).equals(wantedFolded)) {
                folded.add(child);
            }
        }
        return exact.isEmpty() ? folded : exact;
    }

    private List<Folder> subfolders(String parent, String where) throws SourceException {
        List<Folder> children = new ArrayList<>();
        String pageToken = null;
        do {
            FolderPage page = api.listFolders(drive.getSharedDriveId(), parent, pageToken, 1000);
            if (page.incompleteSearch()) {
                throw new SourceException(new SourceError.Transient("Google Drive returned an incomplete list of the "
                        + "folders in " + where + "; a path through it cannot be resolved with certainty"));
            }
            children.addAll(page.folders());
            if (children.size() > MAX_FOLDERS_PER_LEVEL) {
                throw rejected(where + " holds more than " + MAX_FOLDERS_PER_LEVEL + " folders; name the folder by "
                        + "its id instead of its path");
            }
            pageToken = page.nextPageToken();
        } while (pageToken != null);
        return children;
    }

    /**
     * The folder's path from the drive's root as it is now, or null when its
     * ancestors cannot be read.
     */
    private String currentPath(String folderId) {
        try {
            List<String> names = new ArrayList<>();
            for (JsonNode node : ancestry(folderId)) {
                names.add(node.path("name").asText(node.path("id").asText()));
            }
            return String.join("/", names);
        } catch (SourceException e) {
            return null;
        }
    }

    /** Whether a folder lies inside an excluded one. */
    private boolean underExclusion(String folderId) throws SourceException {
        for (JsonNode node : ancestry(folderId)) {
            if (excluded.contains(node.path("id").asText())) {
                return true;
            }
        }
        return false;
    }

    /**
     * The folder and every folder above it, from the top-level folder down — not
     * the drive's root itself.
     */
    private List<JsonNode> ancestry(String folderId) throws SourceException {
        List<JsonNode> chain = new ArrayList<>();
        String current = folderId;
        while (current != null && !current.equals(drive.getSharedDriveId())) {
            if (chain.size() >= MAX_ANCESTRY) {
                throw rejected("Folder " + folderId + " lies more than " + MAX_ANCESTRY + " folders deep");
            }
            JsonNode node = meta(current);
            chain.add(0, node);
            JsonNode parents = node.path("parents");
            String parent = parents.isArray() && !parents.isEmpty() ? parents.get(0).asText(null) : null;
            if (parent != null && !GoogleDriveSource.DRIVE_ID.matcher(parent).matches()) {
                parent = null;
            }
            current = parent;
        }
        return chain;
    }

    /** {@code files.get}, once per folder per run. */
    private JsonNode meta(String folderId) throws SourceException {
        JsonNode known = metadata.get(folderId);
        if (known != null) {
            return known;
        }
        JsonNode node = api.getFile(folderId);
        metadata.put(folderId, node);
        return node;
    }

    /**
     * The folder's metadata, when it is still a folder of this drive and not in the
     * trash; empty when it is gone.
     *
     * @throws SourceException
     *             anything other than "it is gone" — an id that names a file is a
     *             configuration error, and a refusal says nothing about existence
     */
    private Optional<JsonNode> existingFolder(String folderId) throws SourceException {
        JsonNode node;
        try {
            node = meta(folderId);
        } catch (DriveApiException e) {
            if (e.error().kind() == SourceError.Kind.NOT_FOUND) {
                return Optional.empty();
            }
            throw e;
        }
        if (node.path("trashed").asBoolean(false) || !drive.getSharedDriveId().equals(node.path("driveId").asText())) {
            return Optional.empty();
        }
        if (!DriveFormat.FOLDER_MIME.equals(node.path("mimeType").asText())) {
            throw rejected("Drive id " + folderId + " names a file, not a folder");
        }
        return Optional.of(node);
    }

    /** The checks a folder to read must pass, as the folder ids always had. */
    private void requireReadableFolder(String folderId, JsonNode folder) throws SourceException {
        if (!DriveFormat.FOLDER_MIME.equals(folder.path("mimeType").asText())) {
            throw rejected("googleDrive.folders names " + folderId + ", which is not a folder");
        }
        if (!drive.getSharedDriveId().equals(folder.path("driveId").asText())) {
            throw rejected("Folder " + folderId + " is not in Shared Drive " + drive.getSharedDriveId());
        }
        if (!folder.path("capabilities").path("canListChildren").asBoolean(false)) {
            throw new SourceException(new SourceError.Auth("The service account cannot list the children of folder "
                    + folderId + ": Drive shows it but lists it as empty. Share the folder with the account."));
        }
        if (folder.path("trashed").asBoolean(false)) {
            throw new SourceException(new SourceError.Auth("Folder " + folderId + " is in the trash. Restore it, or "
                    + "remove it from googleDrive.folders."));
        }
    }

    private static boolean samePath(String configured, String current) {
        List<String> a = DriveFolder.segmentsOf(configured);
        String[] b = current.split("/", -1);
        if (a.size() != b.length) {
            return false;
        }
        for (int i = 0; i < b.length; i++) {
            if (!nfc(a.get(i)).toLowerCase(Locale.ROOT).equals(nfc(b[i]).toLowerCase(Locale.ROOT))) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasId(DriveFolder folder) {
        return folder.getId() != null && !folder.getId().isBlank();
    }

    static String nfc(String text) {
        return Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFC);
    }

    private static SourceException rejected(String reason) {
        return new SourceException(new SourceError.Rejected(reason));
    }

    /**
     * Reads what an earlier run recorded, tolerating anything unreadable as
     * nothing.
     */
    private static Map<String, String> parseRecorded(String state) {
        Map<String, String> paths = new LinkedHashMap<>();
        if (state == null || state.isBlank()) {
            return paths;
        }
        try {
            JsonNode node = JSON.readTree(state).path("paths");
            node.fields().forEachRemaining(entry -> {
                if (entry.getValue().isTextual()) {
                    paths.put(entry.getKey(), entry.getValue().asText());
                }
            });
        } catch (IOException | RuntimeException e) {
            // Unreadable state resolves every path afresh, which is what a source
            // that never recorded any does.
            paths.clear();
        }
        return paths;
    }
}
