/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.utils;

import ai.labs.eddi.datastore.IResourceStore;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.jaxrs.ResponseBuilderImpl;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

import static ai.labs.eddi.utils.RuntimeUtilities.isNullOrEmpty;

/**
 * @author ginccc
 */
public class RestUtilities {
    private static final String EDDI_SCHEME = "eddi://";

    private static final String versionQueryParam = "?version=";

    public static WebApplicationException createConflictException(String containerUri, IResourceStore.IResourceId currentId) {
        URI resourceUri = RestUtilities.createURI(containerUri, currentId.getId(), versionQueryParam, currentId.getVersion());

        Response.ResponseBuilder builder = new ResponseBuilderImpl();
        builder.status(Response.Status.CONFLICT);
        builder.entity(resourceUri.toString());
        builder.type(MediaType.TEXT_PLAIN);

        return new WebApplicationException(builder.build());
    }

    public static URI createURI(Object... uriParts) {
        StringBuilder sb = new StringBuilder();

        for (Object uriPart : uriParts) {
            sb.append(uriPart.toString());
        }

        return URI.create(sb.toString());
    }

    /**
     * Extracts the resource id and version from a resource URI, e.g.
     * {@code eddi://ai.labs.agent/agentstore/agents/{id}?version=1} or its relative
     * form {@code /agentstore/agents/{id}?version=1}.
     * <p>
     * Malformed input is reported through the return value, never through an
     * exception: {@code null} is returned for a null URI, and a URI that carries no
     * usable resource id — no path at all (e.g. {@code eddi://ai.labs.agent}), too
     * few path segments, or a last segment that is not a valid hex id — yields an
     * {@link IResourceStore.IResourceId} whose {@code getId()} is {@code null}. The
     * single exception is a {@code ?version=} query param that is present but not
     * an integer, which is rejected with an {@link IllegalArgumentException}.
     */
    public static IResourceStore.IResourceId extractResourceId(URI uri) {
        if (isNullOrEmpty(uri)) {
            return null;
        }

        String uriString = uri.toString();

        String relativeUriString;
        if (uriString.contains("://")) {
            uriString = uriString.substring(uriString.indexOf("://") + 3);
            int pathStartIndex = uriString.indexOf("/");
            if (pathStartIndex >= 0) {
                relativeUriString = uriString.substring(pathStartIndex);
            } else {
                // An authority-only URI such as "eddi://ai.labs.agent" has no path segment
                // to extract an id from — treat it as an empty path rather than blowing up.
                // The query has to survive that: "eddi://ai.labs.agent?version=1" carries no
                // id but does carry a version, and discarding the whole remainder reported
                // version 0 — which is the value callers already use to mean "unspecified",
                // so an explicit version was silently indistinguishable from none.
                int queryStartIndex = uriString.indexOf('?');
                relativeUriString = queryStartIndex >= 0 ? uriString.substring(queryStartIndex) : "";
            }
        } else {
            relativeUriString = uriString;
        }

        if (relativeUriString.startsWith("/")) {
            relativeUriString = relativeUriString.substring(1);
        }

        if (relativeUriString.endsWith("/")) {
            relativeUriString = relativeUriString.substring(0, relativeUriString.length() - 1);
        }

        String[] split = relativeUriString.split("/");
        String lastPartOfUri = split.length > 2 ? split[split.length - 1].split("\\?")[0] : null;
        final String id = isValidId(lastPartOfUri) ? lastPartOfUri : null;

        int queryParamVersion = 0;
        if (relativeUriString.contains(versionQueryParam)) {
            String queryParamsString = relativeUriString.split("\\?")[1];
            Map<String, String> queryMap = getQueryMap(queryParamsString);
            String versionString = queryMap.get("version");
            try {
                queryParamVersion = Integer.parseInt(versionString);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Query param \"version\" must be a non-negative integer.");
            }
        }

        final Integer version = queryParamVersion;
        return new IResourceStore.IResourceId() {
            @Override
            public String getId() {
                return id;
            }

            @Override
            public Integer getVersion() {
                return version;
            }
        };
    }

    private static boolean isValidId(String s) {
        if (s == null || s.length() < 18) {
            return false;
        }

        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);

            if (c >= '0' && c <= '9') {
                continue;
            }

            if (c >= 'a' && c <= 'f') {
                continue;
            }

            if (c >= 'A' && c <= 'F') {
                continue;
            }

            // Allow dashes for UUID format (e.g. "5262b802-dc6c-4008-b54c-7c0b58100f97")
            if (c == '-') {
                continue;
            }

            return false;
        }

        return true;
    }

    /**
     * The descriptor {@code type} that matches the resources a store emits, derived
     * from that store's own {@code resourceURI} constant.
     * <p>
     * {@link ai.labs.eddi.datastore.serialization.IDescriptorStore#readDescriptors}
     * filters descriptors by regex-matching the stored resource URI against
     * {@code "eddi://" + type + ".*"}, so {@code type} must be the URI's namespace
     * segment and nothing else. Hard-coding it at each call site is what let three
     * stores (rules, apicalls, dictionary) ship a legacy name —
     * {@code ai.labs.behavior} against {@code eddi://ai.labs.rules/…} — which can
     * never match, so their listings always returned an empty list while the
     * resources existed and read back fine individually. Derive it here instead of
     * restating it.
     *
     * @param resourceURI
     *            a store's resource URI, e.g.
     *            {@code eddi://ai.labs.rules/rulestore/rulesets/}
     * @return the namespace segment, e.g. {@code ai.labs.rules}
     */
    public static String extractDescriptorType(String resourceURI) {
        RuntimeUtilities.checkNotNull(resourceURI, "resourceURI");

        String remainder = resourceURI.startsWith(EDDI_SCHEME)
                ? resourceURI.substring(EDDI_SCHEME.length())
                : resourceURI;
        int pathStart = remainder.indexOf('/');
        String type = pathStart < 0 ? remainder : remainder.substring(0, pathStart);
        if (type.isBlank()) {
            throw new IllegalArgumentException(
                    "resourceURI '" + resourceURI + "' carries no namespace segment to derive a descriptor type from.");
        }
        return type;
    }

    /**
     * The URI with its query string removed — but only when that query actually
     * carries a usable {@code version}. Returns {@code null} when the URI has no
     * query, no {@code version} parameter, or a {@code version} that is not a
     * non-negative integer.
     * <p>
     * Exists for the {@code updateResourceUri} endpoints on the agent and workflow
     * stores, which match stored references by "everything before the query" and
     * then replace them with the supplied URI. Testing only for the presence of a
     * {@code ?} is not enough there: {@code .../workflows/{id}?other=2} would match
     * the stored {@code .../workflows/{id}?version=1} and replace it with a
     * <em>versionless</em> reference, quietly corrupting the pinned version an
     * agent resolves at runtime.
     */
    public static String pathWithoutVersionQuery(URI uri) {
        if (uri == null) {
            return null;
        }
        String uriString = uri.toString();
        int queryStart = uriString.lastIndexOf('?');
        if (queryStart < 0) {
            return null;
        }
        String version = getQueryMap(uriString.substring(queryStart + 1)).get("version");
        if (version == null || version.isBlank()) {
            return null;
        }
        try {
            if (Integer.parseInt(version.trim()) < 0) {
                return null;
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return uriString.substring(0, queryStart);
    }

    private static Map<String, String> getQueryMap(String query) {
        String[] params = query.split("&");
        Map<String, String> map = new HashMap<>();
        for (String param : params) {
            String[] keyValuePair = param.split("=");

            String name = null;
            if (keyValuePair.length > 0) {
                name = keyValuePair[0];
            }

            String value = null;
            if (keyValuePair.length > 1) {
                value = keyValuePair[1];
            }

            if (name != null && value != null) {
                map.put(name, value);
            }
        }
        return map;
    }
}
