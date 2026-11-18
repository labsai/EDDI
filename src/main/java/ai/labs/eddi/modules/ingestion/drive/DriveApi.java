/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.drive;

import ai.labs.eddi.configs.rag.model.IngestionSource.GoogleDriveSource;
import ai.labs.eddi.connections.ResolvedCredential;
import ai.labs.eddi.modules.ingestion.connector.SourceContext;
import ai.labs.eddi.modules.ingestion.connector.SourceError;
import ai.labs.eddi.modules.ingestion.connector.SourceException;
import ai.labs.eddi.modules.ingestion.drive.DriveTransport.DriveResponse;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The Drive v3 calls one run makes, and what Drive's answers mean.
 *
 * <p>
 * Built per run, not shared: it carries the run's context and deadline. Every
 * request is authorised separately — the credential is resolved for its exact
 * URL — and retried on a rate limit or a transient failure with capped backoff
 * charged to the run's deadline.
 *
 * <h2>Classification by reason, not by status</h2>
 * <p>
 * Drive answers both "slow down" and "you may not" with 403; only the
 * {@code reason} in the error body tells them apart. Read as a status alone,
 * either a throttled run fails as an access problem, or a revoked grant is
 * retried as if it were a rate limit.
 */
final class DriveApi {

    /** The API origin; the connection's {@code baseUrlAllowlist} must list it. */
    static final String ORIGIN = "https://www.googleapis.com";

    static final String BASE = ORIGIN + "/drive/v3";

    /** Bytes of a JSON answer; a listing page of 1000 files is well under it. */
    private static final long MAX_JSON_BYTES = 8L * 1024 * 1024;

    private static final int MAX_RETRIES = 3;
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);
    private static final int MAX_REASON_LENGTH = 300;

    private static final String LIST_FIELDS = "nextPageToken,incompleteSearch,files(id,name,mimeType,size,"
            + "md5Checksum,headRevisionId,version,modifiedTime,webViewLink,lastModifyingUser(displayName),"
            + "shortcutDetails(targetId,targetMimeType))";

    private static final Set<String> THROTTLE_REASONS = Set.of("rateLimitExceeded", "userRateLimitExceeded",
            "dailyLimitExceeded", "sharingRateLimitExceeded", "quotaExceeded");

    /** The file exists but this file can never be read as it is. */
    private static final Set<String> UNREADABLE_REASONS = Set.of("cannotDownloadFile", "fileNotDownloadable",
            "cannotExportFile");

    /** The export exceeds Google's own export limit (10 MB of output). */
    static final String EXPORT_TOO_LARGE = "exportSizeLimitExceeded";

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Waits between retries; replaced in tests so nothing actually sleeps. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    static final Sleeper THREAD_SLEEPER = duration -> Thread.sleep(duration.toMillis());

    private final DriveTransport transport;
    private final SourceContext context;
    private final String connection;
    private final Instant deadline;
    private final Sleeper sleeper;
    private final Supplier<Instant> clock;
    private int requests;

    DriveApi(DriveTransport transport, SourceContext context, Instant deadline, Sleeper sleeper,
            Supplier<Instant> clock) {
        this.transport = transport;
        this.context = context;
        GoogleDriveSource drive = context.source().getGoogleDrive();
        this.connection = drive == null ? null : drive.getConnection();
        this.deadline = deadline;
        this.sleeper = sleeper;
        this.clock = clock;
    }

    /** Requests sent so far, retries included. */
    int requests() {
        return requests;
    }

    /** {@code drives.get}: the drive exists and this credential is a member. */
    JsonNode getDrive(String driveId) throws SourceException {
        return json(call(BASE + "/drives/" + driveId + "?fields=" + encode("id,name"), MAX_JSON_BYTES));
    }

    /** {@code files.get} for metadata only. */
    JsonNode getFile(String fileId) throws SourceException {
        return json(call(BASE + "/files/" + fileId + "?supportsAllDrives=true&fields="
                + encode("id,name,mimeType,driveId,trashed"), MAX_JSON_BYTES));
    }

    /**
     * One page of a folder's direct children that are not in the trash, limited to
     * the Shared Drive — a shortcut to a folder of another drive therefore lists
     * nothing, and cannot widen the source.
     */
    Page listChildren(String driveId, String folderId, String pageToken) throws SourceException {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("corpora", "drive");
        query.put("driveId", driveId);
        query.put("includeItemsFromAllDrives", "true");
        query.put("supportsAllDrives", "true");
        query.put("pageSize", "1000");
        // Both ids passed GoogleDriveSource.DRIVE_ID (or came from Drive and were
        // checked against it), so neither can close the quote.
        query.put("q", "'" + folderId + "' in parents and trashed = false");
        query.put("fields", LIST_FIELDS);
        if (pageToken != null) {
            query.put("pageToken", pageToken);
        }
        JsonNode page = json(call(BASE + "/files?" + queryString(query), MAX_JSON_BYTES));
        List<DriveFile> files = new ArrayList<>();
        for (JsonNode file : page.path("files")) {
            files.add(DriveFile.of(file));
        }
        String next = page.path("nextPageToken").asText(null);
        return new Page(files, next == null || next.isBlank() ? null : next,
                page.path("incompleteSearch").asBoolean(false));
    }

    /** {@code files.export}: a Google-native file in another format. */
    DriveResponse export(String fileId, String mimeType, long maxBytes) throws SourceException {
        return call(BASE + "/files/" + fileId + "/export?mimeType=" + encode(mimeType), maxBytes);
    }

    /** {@code files.get?alt=media}: a stored file's bytes. */
    DriveResponse download(String fileId, long maxBytes) throws SourceException {
        return call(BASE + "/files/" + fileId + "?alt=media&supportsAllDrives=true", maxBytes);
    }

    /**
     * One request, authorised for its own URL and retried while Drive says to wait.
     *
     * @return a 2xx answer, or one whose body was over the cap
     * @throws DriveApiException
     *             every other answer, classified
     */
    private DriveResponse call(String url, long maxBytes) throws SourceException {
        URI uri = URI.create(url);
        for (int attempt = 0;; attempt++) {
            SourceException failure;
            try {
                // Resolved per request: the allowlist is checked against this URL, and
                // the grant cache decides whether the token needs renewing.
                ResolvedCredential credential = context.credentials().resolve(connection, uri);
                requests++;
                DriveResponse response = transport.get(uri, credential, maxBytes);
                if (response.tooLarge() || (response.status() >= 200 && response.status() < 300)) {
                    return response;
                }
                failure = classify(response);
            } catch (SourceException e) {
                failure = e;
            }
            SourceError error = failure.error();
            boolean retryable = error.kind() == SourceError.Kind.THROTTLED
                    || error.kind() == SourceError.Kind.TRANSIENT;
            if (!retryable || attempt >= MAX_RETRIES) {
                throw failure;
            }
            Duration wait = backoff(error, attempt);
            if (clock.get().plus(wait).isAfter(deadline)) {
                // Waiting would outlive the run; the next run starts fresh.
                throw failure;
            }
            try {
                sleeper.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw failure;
            }
        }
    }

    private static Duration backoff(SourceError error, int attempt) {
        Duration exponential = Duration.ofSeconds(1L << attempt);
        Duration wait = error.retryAfter().filter(hint -> !hint.isNegative()).orElse(exponential);
        return wait.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : wait;
    }

    /**
     * What a non-2xx answer means, from Drive's error {@code reason} first and the
     * status only where the reason does not decide it.
     */
    static DriveApiException classify(DriveResponse response) {
        int status = response.status();
        Set<String> reasons = new LinkedHashSet<>();
        String message = null;
        try {
            JsonNode error = JSON.readTree(response.body()).path("error");
            message = error.path("message").asText(null);
            for (JsonNode item : error.path("errors")) {
                String reason = item.path("reason").asText(null);
                if (reason != null && !reason.isBlank()) {
                    reasons.add(reason);
                }
            }
        } catch (IOException | RuntimeException e) {
            // Not Drive's JSON error shape — a proxy page, an empty body. The status
            // decides.
        }
        Duration retryAfter = response.header("Retry-After").map(DriveApi::seconds).orElse(null);
        String reason = describe(status, reasons, message);

        SourceError error;
        if (status == 429 || reasons.stream().anyMatch(THROTTLE_REASONS::contains)) {
            error = new SourceError.Throttled(reason, retryAfter);
        } else if (reasons.contains(EXPORT_TOO_LARGE)) {
            error = new SourceError.Permanent(reason);
        } else if (reasons.stream().anyMatch(UNREADABLE_REASONS::contains)) {
            error = new SourceError.Permanent(reason);
        } else if (status == 401 || status == 403) {
            // insufficientPermissions, forbidden, accessNotConfigured (the API is not
            // enabled for the key's project), domainPolicy, or a reason not known
            // here: in every case the credential could not look, which says nothing
            // about the file.
            error = new SourceError.Auth(reason);
        } else if (status == 404 || status == 410) {
            error = new SourceError.NotFound(reason);
        } else if (status >= 500) {
            error = new SourceError.Transient(reason, retryAfter);
        } else {
            error = new SourceError.Rejected(reason);
        }
        return new DriveApiException(error, status, reasons);
    }

    private static String describe(int status, Set<String> reasons, String message) {
        StringBuilder text = new StringBuilder("Google Drive answered ").append(status);
        if (!reasons.isEmpty()) {
            text.append(" (").append(String.join(", ", reasons)).append(')');
        }
        if (message != null && !message.isBlank()) {
            text.append(": ").append(message.strip());
        }
        return text.length() <= MAX_REASON_LENGTH ? text.toString() : text.substring(0, MAX_REASON_LENGTH) + "…";
    }

    private static Duration seconds(String value) {
        try {
            long seconds = Long.parseLong(value.trim());
            return seconds < 0 ? null : Duration.ofSeconds(seconds);
        } catch (NumberFormatException e) {
            // An HTTP date: rare from Google, and the exponential backoff serves.
            return null;
        }
    }

    private static JsonNode json(DriveResponse response) throws SourceException {
        if (response.tooLarge()) {
            throw new SourceException(new SourceError.Rejected("A Google Drive API answer exceeded "
                    + MAX_JSON_BYTES + " bytes"));
        }
        try {
            JsonNode node = JSON.readTree(response.body());
            if (node == null || !node.isObject()) {
                throw new IOException("not a JSON object");
            }
            return node;
        } catch (IOException e) {
            throw new SourceException(new SourceError.Rejected(
                    "The Google Drive API answered with something that is not JSON"), e);
        }
    }

    private static String queryString(Map<String, String> query) {
        StringBuilder text = new StringBuilder();
        for (Map.Entry<String, String> entry : query.entrySet()) {
            if (!text.isEmpty()) {
                text.append('&');
            }
            text.append(entry.getKey()).append('=').append(encode(entry.getValue()));
        }
        return text.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    /** One page of a listing. */
    record Page(List<DriveFile> files, String nextPageToken, boolean incompleteSearch) {
    }

    /**
     * A file as a listing describes it — enough to filter it, to know whether it
     * changed, and to cite it, without downloading anything.
     */
    record DriveFile(String id, String name, String mimeType, Long size, String md5Checksum, String headRevisionId,
            Long version, String modifiedTime, String webViewLink, String author, String shortcutTargetId,
            String shortcutTargetMimeType) {

        static DriveFile of(JsonNode node) {
            JsonNode shortcut = node.path("shortcutDetails");
            return new DriveFile(text(node, "id"), text(node, "name"), text(node, "mimeType"),
                    number(node, "size"), text(node, "md5Checksum"), text(node, "headRevisionId"),
                    number(node, "version"), text(node, "modifiedTime"), text(node, "webViewLink"),
                    text(node.path("lastModifyingUser"), "displayName"), text(shortcut, "targetId"),
                    text(shortcut, "targetMimeType"));
        }

        private static String text(JsonNode node, String field) {
            JsonNode value = node.path(field);
            return value.isMissingNode() || value.isNull() ? null : value.asText();
        }

        /** Drive sends int64 values as strings. */
        private static Long number(JsonNode node, String field) {
            String value = text(node, field);
            if (value == null) {
                return null;
            }
            try {
                return Long.parseLong(value);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }

    /** A classified Drive error answer, with the reasons it carried. */
    static final class DriveApiException extends SourceException {

        private final int status;
        private final transient Set<String> reasons;

        DriveApiException(SourceError error, int status, Set<String> reasons) {
            super(error);
            this.status = status;
            this.reasons = Set.copyOf(reasons);
        }

        int status() {
            return status;
        }

        boolean hasReason(String reason) {
            return reasons.contains(reason);
        }
    }
}
