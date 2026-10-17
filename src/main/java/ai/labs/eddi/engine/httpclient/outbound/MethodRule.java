/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient.outbound;

/**
 * What happens to a request's method and body when it is redirected.
 */
public enum MethodRule {

    /**
     * RFC 9110, which sanctions exactly one method rewrite and it is narrower than
     * "everything becomes GET":
     * <ul>
     * <li>307/308: method and body preserved, always.</li>
     * <li>303 See Other: rewrite to GET — that is what the code means.</li>
     * <li>301/302: only the historical POST→GET rewrite. PUT, PATCH and DELETE keep
     * their method and body, or a redirected write silently becomes a read: the
     * caller is told the write succeeded (200 from the GET) while nothing was
     * written, and a DELETE that "worked" leaves the resource in place.</li>
     * <li>HEAD survives every one of them: an existence or size probe must not turn
     * into a full body download.</li>
     * </ul>
     * The rule {@code SafeHttpClient} has always applied.
     */
    JDK_SAFE,

    /**
     * What the Vert.x {@code WebClient} behind {@code httpcalls.json} does: 303
     * becomes GET whatever the method; 301/302/307/308 are followed for GET and
     * HEAD only; any other method gets the 3xx back unchanged, as its response.
     */
    VERTX_COMPAT;

    /** What a redirect does to the request that received it. */
    public enum Action {
        /** Same method, same body. */
        FOLLOW_PRESERVING,
        /** GET, no body. */
        FOLLOW_AS_GET,
        /** HEAD, no body. */
        FOLLOW_AS_HEAD,
        /** Not followed: the 3xx is the response. */
        RETURN
    }

    public Action onRedirect(String method, int statusCode) {
        return switch (this) {
            case JDK_SAFE -> jdkSafe(method, statusCode);
            case VERTX_COMPAT -> vertxCompat(method, statusCode);
        };
    }

    private static Action jdkSafe(String method, int statusCode) {
        if ("GET".equals(method)) {
            return Action.FOLLOW_AS_GET;
        }
        if ("HEAD".equals(method)) {
            return Action.FOLLOW_AS_HEAD;
        }
        if (statusCode == 307 || statusCode == 308) {
            return Action.FOLLOW_PRESERVING;
        }
        if (statusCode == 303) {
            return Action.FOLLOW_AS_GET;
        }
        // 301/302: POST is the only method the historical rewrite covers.
        return "POST".equals(method) ? Action.FOLLOW_AS_GET : Action.FOLLOW_PRESERVING;
    }

    private static Action vertxCompat(String method, int statusCode) {
        if (statusCode == 303) {
            return Action.FOLLOW_AS_GET;
        }
        if ("GET".equals(method)) {
            return Action.FOLLOW_AS_GET;
        }
        if ("HEAD".equals(method)) {
            return Action.FOLLOW_AS_HEAD;
        }
        return Action.RETURN;
    }
}
