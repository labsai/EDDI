/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient.outbound;

import ai.labs.eddi.engine.httpclient.outbound.MethodRule.Action;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The redirect method-rewrite rules, pinned per method/status pair.
 * <p>
 * Deliberately socket-free: a rule has 5 status codes × 6 methods worth of
 * corners, and the one that mattered — {@code PUT} across a 302 — is the kind
 * of combination nobody stands a loopback server up for.
 */
class MethodRuleTest {

    @Nested
    @DisplayName("JDK_SAFE (RFC 9110)")
    class JdkSafe {

        /**
         * RFC 9110 §15.4.2/§15.4.3 permit the historical POST→GET rewrite on 301/302
         * and nothing more. Rewriting every method there turns a redirected write into
         * a read: the caller gets 200 from the GET and believes the PUT landed, while
         * nothing was written — and a DELETE that "succeeded" leaves the resource in
         * place.
         */
        @Test
        @DisplayName("301/302 preserve every method except POST")
        void movedAndFoundPreserveWritesButRewritePost() {
            for (int statusCode : new int[]{301, 302}) {
                assertEquals(Action.FOLLOW_PRESERVING, MethodRule.JDK_SAFE.onRedirect("PUT", statusCode));
                assertEquals(Action.FOLLOW_PRESERVING, MethodRule.JDK_SAFE.onRedirect("PATCH", statusCode));
                assertEquals(Action.FOLLOW_PRESERVING, MethodRule.JDK_SAFE.onRedirect("DELETE", statusCode));
                assertEquals(Action.FOLLOW_AS_GET, MethodRule.JDK_SAFE.onRedirect("POST", statusCode),
                        "POST is the one method the historical rewrite covers on " + statusCode);
            }
        }

        @Test
        @DisplayName("303 rewrites every body-carrying method to GET")
        void seeOtherAlwaysRewritesToGet() {
            for (String method : new String[]{"PUT", "PATCH", "DELETE", "POST"}) {
                assertEquals(Action.FOLLOW_AS_GET, MethodRule.JDK_SAFE.onRedirect(method, 303), method);
            }
        }

        @Test
        @DisplayName("307/308 preserve the method")
        void temporaryAndPermanentRedirectPreserveTheMethod() {
            for (int statusCode : new int[]{307, 308}) {
                for (String method : new String[]{"POST", "PUT", "DELETE"}) {
                    assertEquals(Action.FOLLOW_PRESERVING, MethodRule.JDK_SAFE.onRedirect(method, statusCode), method);
                }
            }
        }

        /** HEAD stays HEAD on every code: a size probe must not download the body. */
        @Test
        @DisplayName("GET stays GET and HEAD stays HEAD on every code")
        void bodylessMethodsKeepTheirMethod() {
            for (int statusCode : new int[]{301, 302, 303, 307, 308}) {
                assertEquals(Action.FOLLOW_AS_GET, MethodRule.JDK_SAFE.onRedirect("GET", statusCode));
                assertEquals(Action.FOLLOW_AS_HEAD, MethodRule.JDK_SAFE.onRedirect("HEAD", statusCode));
            }
        }
    }

    @Nested
    @DisplayName("VERTX_COMPAT (httpcalls)")
    class VertxCompat {

        @Test
        @DisplayName("a write on 301/302/307/308 gets the 3xx back instead of being followed")
        void writesAreNotFollowed() {
            for (int statusCode : new int[]{301, 302, 307, 308}) {
                for (String method : new String[]{"POST", "PUT", "PATCH", "DELETE"}) {
                    assertEquals(Action.RETURN, MethodRule.VERTX_COMPAT.onRedirect(method, statusCode), method + " " + statusCode);
                }
            }
        }

        @Test
        @DisplayName("303 becomes GET for every method, HEAD included")
        void seeOtherIsGet() {
            for (String method : new String[]{"GET", "HEAD", "POST", "PUT", "DELETE"}) {
                assertEquals(Action.FOLLOW_AS_GET, MethodRule.VERTX_COMPAT.onRedirect(method, 303), method);
            }
        }

        @Test
        @DisplayName("GET and HEAD are followed on 301/302/307/308")
        void readsAreFollowed() {
            for (int statusCode : new int[]{301, 302, 307, 308}) {
                assertEquals(Action.FOLLOW_AS_GET, MethodRule.VERTX_COMPAT.onRedirect("GET", statusCode));
                assertEquals(Action.FOLLOW_AS_HEAD, MethodRule.VERTX_COMPAT.onRedirect("HEAD", statusCode));
            }
        }
    }
}
