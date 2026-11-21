/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mcp;

import ai.labs.eddi.engine.api.IRestGroupConversation;
import ai.labs.eddi.engine.api.IRestGroupConversation.AttachmentRef;
import ai.labs.eddi.engine.internal.groups.GroupAttachmentLimits;
import ai.labs.eddi.engine.memory.AttachmentContextExtractor;
import ai.labs.eddi.engine.model.Context;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The {@code attachments} argument of the MCP tools {@code talk_to_agent},
 * {@code chat_with_agent} and {@code discuss_with_group}: parsed and checked
 * here, then handed to the same code the REST endpoints use — so an MCP client
 * gets no path to a file that REST does not give it.
 * <p>
 * The argument is a JSON array (every MCP argument in EDDI is a string). Each
 * entry has the shape of an {@code attachment_N} context value: either a
 * {@code storageRef} of an uploaded blob, a hosted {@code url} with a
 * {@code mimeType}, or inline base64 {@code data} with a {@code mimeType}; plus
 * an optional {@code fileName}.
 * <ul>
 * <li><b>One-to-one</b> ({@link #toContexts}): the entries become
 * {@code attachment_0 … attachment_N} context entries of the turn, so
 * {@code Conversation} resolves them exactly as for a REST {@code say}: a
 * {@code storageRef} must be one the <em>target</em> conversation can already
 * read (owner or grant), and at most {@code eddi.attachments.max-per-turn}
 * reach the turn. What it drops is reported in the turn's
 * {@code attachments:errors}.</li>
 * <li><b>Group</b> ({@link #toRefs}): a {@code storageRef} is a capability, and
 * the group binder admits one only from a trusted source conversation, which an
 * MCP caller never has. It is therefore refused here with a message, instead of
 * being dropped later with a log line only the operator sees. Inline and URL
 * entries become the REST request's {@link AttachmentRef}s and go through
 * {@link GroupAttachmentLimits} and the service's attachment overloads.</li>
 * </ul>
 * <p>
 * Bean Validation does not run on an in-process MCP call, so the field ceilings
 * REST's {@link AttachmentRef} declares are enforced here by hand, and every
 * inline file is held to {@code eddi.attachments.max-size-bytes}. For the
 * one-to-one tools this is stricter than a REST {@code say}, whose
 * {@code attachment_N} context carries no such constraints. Error texts are
 * built from field names and numbers only: a caller's value, a key it sent or
 * the parser's message never comes back.
 */
final class McpAttachmentArguments {

    static final String FIELD_STORAGE_REF = AttachmentContextExtractor.FIELD_STORAGE_REF;
    static final String FIELD_DATA = AttachmentContextExtractor.FIELD_DATA;
    static final String FIELD_URL = "url";
    static final String FIELD_MIME_TYPE = "mimeType";
    static final String FIELD_FILE_NAME = "fileName";

    private static final Set<String> FIELDS = Set.of(FIELD_STORAGE_REF, FIELD_DATA, FIELD_URL, FIELD_MIME_TYPE, FIELD_FILE_NAME);

    /** At most as many entries as a REST group discussion request may carry. */
    static final int MAX_ENTRIES = IRestGroupConversation.MAX_ATTACHMENTS_PER_REQUEST;

    /** The {@code @ToolArg} description of the one-to-one tools. */
    static final String ONE_TO_ONE_DESCRIPTION = "Files to send with the message (optional): a JSON array, at most "
            + IRestGroupConversation.MAX_ATTACHMENTS_PER_REQUEST + " entries. Each entry is ONE of "
            + "{\"storageRef\":\"...\"} (an uploaded file this conversation can already read; a new conversation can read none), "
            + "{\"url\":\"https://...\",\"mimeType\":\"image/png\"} or {\"data\":\"<base64>\",\"mimeType\":\"application/pdf\"}, "
            + "each with an optional \"fileName\". Only eddi.attachments.max-per-turn (default 5) reach the agent; "
            + "a reference the conversation cannot read is skipped, not an error. "
            + "Inline data is limited per file by eddi.attachments.max-size-bytes; this whole argument must stay under 20,000,000 "
            + "characters (about 15 MB of inline files); for a larger file upload it and pass its storageRef.";

    /** The {@code @ToolArg} description of {@code discuss_with_group}. */
    static final String GROUP_DESCRIPTION = "Files every member receives (optional): a JSON array, at most "
            + IRestGroupConversation.MAX_ATTACHMENTS_PER_REQUEST + " entries, each "
            + "{\"url\":\"https://...\",\"mimeType\":\"image/png\"} or {\"data\":\"<base64>\",\"mimeType\":\"application/pdf\"}, "
            + "with an optional \"fileName\". A storageRef is refused: a group may only receive a stored file from a conversation "
            + "the server trusts, and an MCP call has none, so send the file inline or by url. Inline files are stored for the "
            + "discussion and count against the attachment quotas; this whole argument must stay under 20,000,000 characters "
            + "(about 15 MB of inline files).";

    /*
     * The argument has already passed the MCP transport's own parser (Vert.x, with
     * Jackson's default 20,000,000-character string cap) and the request body
     * limit, so this parser lifts the string cap: an inline file the transport let
     * through is then judged by the size check below, with a size message, rather
     * than refused here as "not valid JSON".
     */
    private static final ObjectMapper MAPPER = new ObjectMapper(
            JsonFactory.builder().streamReadConstraints(StreamReadConstraints.builder().maxStringLength(Integer.MAX_VALUE).build()).build());

    /**
     * A parsed argument: the entries, each a map of the fields above, or the reason
     * it was refused.
     */
    record Parsed(List<Map<String, String>> entries, String error) {

        static Parsed refused(String error) {
            return new Parsed(List.of(), error);
        }

        boolean isRefused() {
            return error != null;
        }

        boolean isEmpty() {
            return entries.isEmpty();
        }
    }

    private McpAttachmentArguments() {
    }

    /**
     * @param json
     *            the raw argument; {@code null} or blank means no attachments
     * @param allowStorageRef
     *            whether an entry may name an uploaded blob
     * @param maxFileBytes
     *            {@code eddi.attachments.max-size-bytes}; non-positive disables the
     *            per-file check
     */
    static Parsed parse(String json, boolean allowStorageRef, long maxFileBytes) {
        if (json == null || json.isBlank()) {
            return new Parsed(List.of(), null);
        }
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (JsonProcessingException e) {
            // Never the parser's message: it quotes the caller's payload.
            return Parsed.refused("'attachments' is not valid JSON: expected a JSON array of objects.");
        }
        if (root == null || !root.isArray()) {
            return Parsed.refused("'attachments' must be a JSON array of objects.");
        }
        if (root.size() > MAX_ENTRIES) {
            return Parsed.refused("'attachments' must contain at most " + MAX_ENTRIES + " entries.");
        }
        List<Map<String, String>> entries = new ArrayList<>(root.size());
        for (int i = 0; i < root.size(); i++) {
            JsonNode node = root.get(i);
            String label = "attachments[" + i + "]";
            if (node == null || !node.isObject()) {
                return Parsed.refused(label + " must be a JSON object.");
            }
            Map<String, String> entry = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                if (!FIELDS.contains(field.getKey())) {
                    return Parsed.refused(label + " has a field that is not one of storageRef, url, data, mimeType, fileName.");
                }
                JsonNode value = field.getValue();
                if (value == null || value.isNull()) {
                    continue;
                }
                if (!value.isTextual()) {
                    return Parsed.refused(label + "." + field.getKey() + " must be a string.");
                }
                if (!value.asText().isBlank()) {
                    entry.put(field.getKey(), value.asText());
                }
            }
            String refusal = refusal(label, entry, allowStorageRef, maxFileBytes);
            if (refusal != null) {
                return Parsed.refused(refusal);
            }
            entries.add(entry);
        }
        return new Parsed(List.copyOf(entries), null);
    }

    private static String refusal(String label, Map<String, String> entry, boolean allowStorageRef, long maxFileBytes) {
        int sources = (entry.containsKey(FIELD_STORAGE_REF) ? 1 : 0) + (entry.containsKey(FIELD_URL) ? 1 : 0)
                + (entry.containsKey(FIELD_DATA) ? 1 : 0);
        if (entry.containsKey(FIELD_STORAGE_REF) && !allowStorageRef) {
            return label + ": a storageRef is not accepted here. A group may only receive a stored file from a conversation "
                    + "the server trusts, and an MCP call has none: send the file inline (data + mimeType) or by url.";
        }
        if (sources != 1) {
            return label + " must have exactly one of storageRef, url or data.";
        }
        if (!entry.containsKey(FIELD_STORAGE_REF) && !entry.containsKey(FIELD_MIME_TYPE)) {
            return label + " needs a mimeType with url or data.";
        }
        String tooLong = tooLong(label, entry, FIELD_MIME_TYPE, IRestGroupConversation.MAX_MIME_TYPE_CHARS);
        if (tooLong == null) {
            tooLong = tooLong(label, entry, FIELD_FILE_NAME, IRestGroupConversation.MAX_FILE_NAME_CHARS);
        }
        if (tooLong == null) {
            tooLong = tooLong(label, entry, FIELD_URL, IRestGroupConversation.MAX_URL_CHARS);
        }
        if (tooLong == null) {
            tooLong = tooLong(label, entry, FIELD_STORAGE_REF, IRestGroupConversation.MAX_IDENTIFIER_CHARS);
        }
        if (tooLong != null) {
            return tooLong;
        }
        String data = entry.get(FIELD_DATA);
        if (data != null && maxFileBytes > 0) {
            long bytes = GroupAttachmentLimits.decodedLength(data);
            if (bytes > maxFileBytes) {
                return label + " is too large: " + bytes + " bytes exceeds the limit of " + maxFileBytes
                        + " (eddi.attachments.max-size-bytes).";
            }
        }
        return null;
    }

    private static String tooLong(String label, Map<String, String> entry, String field, int max) {
        String value = entry.get(field);
        return value != null && value.length() > max ? label + "." + field + " must be at most " + max + " characters." : null;
    }

    /**
     * The entries as the turn's {@code attachment_N} context entries, in order —
     * the shape {@link AttachmentContextExtractor} reads.
     */
    static Map<String, Context> toContexts(List<Map<String, String>> entries) {
        Map<String, Context> contexts = new LinkedHashMap<>();
        for (int i = 0; i < entries.size(); i++) {
            contexts.put(AttachmentContextExtractor.ATTACHMENT_PREFIX + i,
                    new Context(Context.ContextType.object, new LinkedHashMap<String, Object>(entries.get(i))));
        }
        return contexts;
    }

    /**
     * The entries as a REST discussion request's refs. Call only on a result parsed
     * with {@code allowStorageRef=false}.
     */
    static List<AttachmentRef> toRefs(List<Map<String, String>> entries) {
        List<AttachmentRef> refs = new ArrayList<>(entries.size());
        for (Map<String, String> entry : entries) {
            refs.add(new AttachmentRef(entry.get(FIELD_MIME_TYPE), entry.get(FIELD_DATA), entry.get(FIELD_URL),
                    entry.get(FIELD_FILE_NAME)));
        }
        return refs;
    }
}
