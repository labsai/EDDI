/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.drive;

import ai.labs.eddi.connections.ResolvedCredential;
import ai.labs.eddi.modules.ingestion.connector.SourceException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A Shared Drive in memory, answering the Drive v3 calls the connector makes at
 * the {@link DriveTransport} seam. Synthetic ids and names only.
 *
 * <p>
 * It serves what Drive serves for a Shared Drive listing — the direct children
 * of one folder that are not trashed, paged — and can be told to answer any
 * request matching a URL fragment with a scripted error first.
 */
public final class FakeDrive implements DriveTransport {

    public static final String FOLDER = DriveFormat.FOLDER_MIME;
    public static final String SHORTCUT = DriveFormat.SHORTCUT_MIME;
    public static final String DOC = "application/vnd.google-apps.document";
    public static final String SHEET = "application/vnd.google-apps.spreadsheet";
    public static final String SLIDES = "application/vnd.google-apps.presentation";

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern PARENT = Pattern.compile("^'([^']+)' in parents and trashed = false$");

    /** One file or folder. */
    record Item(String id, String name, String mimeType, String parent, Long size, String md5, Long version,
            String shortcutTarget, String shortcutTargetMime, boolean trashed) {
    }

    final String driveId;
    final Map<String, Item> items = new LinkedHashMap<>();
    final Map<String, byte[]> contents = new LinkedHashMap<>();
    final Map<String, Deque<DriveResponse>> scripted = new LinkedHashMap<>();
    public final List<URI> requests = new ArrayList<>();
    final List<String> authorizations = new ArrayList<>();
    public boolean driveVisible = true;
    int pageSize = 1000;

    public FakeDrive(String driveId) {
        this.driveId = driveId;
    }

    public FakeDrive folder(String id, String name, String parent) {
        items.put(id, new Item(id, name, FOLDER, parent, null, null, 1L, null, null, false));
        return this;
    }

    public FakeDrive file(String id, String name, String mimeType, String parent, String content) {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        return file(id, name, mimeType, parent, bytes, "md5-" + id);
    }

    public FakeDrive file(String id, String name, String mimeType, String parent, byte[] bytes, String md5) {
        items.put(id, new Item(id, name, mimeType, parent, (long) bytes.length, md5, 3L, null, null, false));
        contents.put(id, bytes);
        return this;
    }

    /** A Google-native file; {@code exports} maps export MIME type to content. */
    public FakeDrive nativeFile(String id, String name, String mimeType, String parent, long version,
                                Map<String, byte[]> exports) {
        items.put(id, new Item(id, name, mimeType, parent, null, null, version, null, null, false));
        exports.forEach((mime, bytes) -> contents.put(id + "|" + mime, bytes));
        return this;
    }

    public FakeDrive shortcut(String id, String name, String parent, String target, String targetMime) {
        items.put(id, new Item(id, name, SHORTCUT, parent, null, null, 1L, target, targetMime, false));
        return this;
    }

    /** Moves an item to the trash: it stops being listed, and reads as trashed. */
    public FakeDrive trash(String id) {
        Item item = items.get(id);
        items.put(id, new Item(item.id(), item.name(), item.mimeType(), item.parent(), item.size(), item.md5(),
                item.version(), item.shortcutTarget(), item.shortcutTargetMime(), true));
        return this;
    }

    /**
     * Answers the next request whose URL contains {@code fragment} with these
     * responses first.
     */
    public FakeDrive script(String fragment, DriveResponse... responses) {
        scripted.computeIfAbsent(fragment, key -> new ArrayDeque<>()).addAll(List.of(responses));
        return this;
    }

    public static DriveResponse error(int status, String reason, String message) {
        return error(status, reason, message, null);
    }

    public static DriveResponse error(int status, String reason, String message, String retryAfter) {
        ObjectNode body = JSON.createObjectNode();
        ObjectNode error = body.putObject("error");
        error.put("code", status);
        error.put("message", message);
        if (reason != null) {
            error.putArray("errors").addObject().put("domain", "global").put("reason", reason).put("message", message);
        }
        Map<String, List<String>> headers = retryAfter == null ? Map.of() : Map.of("Retry-After", List.of(retryAfter));
        return new DriveResponse(status, headers, bytes(body), false);
    }

    public int countRequests(String fragment) {
        return (int) requests.stream().filter(uri -> uri.toString().contains(fragment)).count();
    }

    @Override
    public DriveResponse get(URI uri, ResolvedCredential credential, long maxBytes) throws SourceException {
        requests.add(uri);
        authorizations.add(credential.headerValue());
        for (Map.Entry<String, Deque<DriveResponse>> entry : scripted.entrySet()) {
            if (uri.toString().contains(entry.getKey()) && !entry.getValue().isEmpty()) {
                return entry.getValue().poll();
            }
        }
        Map<String, String> query = query(uri);
        String path = uri.getRawPath();
        if (path.startsWith("/drive/v3/drives/")) {
            String id = path.substring("/drive/v3/drives/".length());
            if (!driveVisible || !id.equals(driveId)) {
                return error(404, "notFound", "Shared drive not found: " + id);
            }
            ObjectNode drive = JSON.createObjectNode().put("id", driveId).put("name", "Handbook");
            return ok(drive, maxBytes);
        }
        if (path.equals("/drive/v3/files")) {
            return list(query, maxBytes);
        }
        if (path.endsWith("/export")) {
            String id = path.substring("/drive/v3/files/".length(), path.length() - "/export".length());
            byte[] content = contents.get(id + "|" + query.get("mimeType"));
            if (content == null) {
                return error(400, "badRequest", "Export only supports Docs Editors files.");
            }
            return content.length > maxBytes
                    ? DriveResponse.ofTooLarge(200)
                    : new DriveResponse(200, Map.of(), content, false);
        }
        String id = path.substring("/drive/v3/files/".length());
        Item item = items.get(id);
        if (item == null) {
            return error(404, "notFound", "File not found: " + id);
        }
        if ("media".equals(query.get("alt"))) {
            byte[] content = contents.get(id);
            return content.length > maxBytes
                    ? DriveResponse.ofTooLarge(200)
                    : new DriveResponse(200, Map.of(), content, false);
        }
        ObjectNode file = JSON.createObjectNode().put("id", id).put("name", item.name())
                .put("mimeType", item.mimeType()).put("driveId", driveId).put("trashed", item.trashed());
        return ok(file, maxBytes);
    }

    private DriveResponse list(Map<String, String> query, long maxBytes) {
        if (!"drive".equals(query.get("corpora")) || !driveId.equals(query.get("driveId"))
                || !"true".equals(query.get("supportsAllDrives"))
                || !"true".equals(query.get("includeItemsFromAllDrives"))) {
            return error(400, "invalid", "A Shared Drive listing needs corpora, driveId and the all-drives flags");
        }
        Matcher parent = PARENT.matcher(query.getOrDefault("q", ""));
        if (!parent.matches()) {
            return error(400, "invalidQuery", "Invalid Value");
        }
        List<Item> children = new ArrayList<>();
        if (driveVisible) {
            for (Item item : items.values()) {
                if (parent.group(1).equals(item.parent()) && !item.trashed()) {
                    children.add(item);
                }
            }
        }
        int offset = query.containsKey("pageToken") ? Integer.parseInt(query.get("pageToken").substring(1)) : 0;
        int end = Math.min(children.size(), offset + pageSize);
        ObjectNode page = JSON.createObjectNode();
        ArrayNode files = page.putArray("files");
        for (Item item : children.subList(offset, end)) {
            ObjectNode file = files.addObject().put("id", item.id()).put("name", item.name())
                    .put("mimeType", item.mimeType())
                    .put("webViewLink", "https://docs.google.com/fake/" + item.id())
                    .put("modifiedTime", "2026-11-01T10:00:00.000Z");
            file.putObject("lastModifyingUser").put("displayName", "Test Author");
            if (item.size() != null) {
                file.put("size", String.valueOf(item.size()));
            }
            if (item.md5() != null) {
                file.put("md5Checksum", item.md5());
            }
            if (item.version() != null) {
                file.put("version", String.valueOf(item.version()));
            }
            if (item.shortcutTarget() != null) {
                file.putObject("shortcutDetails").put("targetId", item.shortcutTarget())
                        .put("targetMimeType", item.shortcutTargetMime());
            }
        }
        if (end < children.size()) {
            page.put("nextPageToken", "p" + end);
        }
        return ok(page, maxBytes);
    }

    private static DriveResponse ok(ObjectNode body, long maxBytes) {
        byte[] bytes = bytes(body);
        return bytes.length > maxBytes ? DriveResponse.ofTooLarge(200) : new DriveResponse(200, Map.of(), bytes, false);
    }

    private static byte[] bytes(ObjectNode node) {
        try {
            return JSON.writeValueAsBytes(node);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> query = new LinkedHashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null) {
            return query;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            query.put(key, value);
        }
        return query;
    }
}
