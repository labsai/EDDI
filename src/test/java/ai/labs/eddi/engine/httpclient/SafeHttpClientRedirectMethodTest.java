/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The redirect method-rewrite rule of {@link SafeHttpClient}, pinned per
 * method/status pair.
 * <p>
 * Deliberately socket-free, unlike {@link SafeHttpClientTest}: the rule has 5
 * status codes × 6 methods worth of corners, and the one that mattered —
 * {@code PUT} across a 302 — is the kind of combination nobody stands a
 * loopback server up for.
 */
class SafeHttpClientRedirectMethodTest {

    /**
     * RFC 9110 §15.4.2/§15.4.3 permit the historical POST→GET rewrite on 301/302
     * and nothing more. Rewriting every method there turns a redirected write into
     * a read: the caller gets 200 from the GET and believes the PUT landed, while
     * nothing was written — and a DELETE that "succeeded" leaves the resource in
     * place. Object stores and API gateways redirect writes routinely, so this is
     * an ordinary path, not a corner.
     */
    @Test
    @DisplayName("301/302 preserve every method except POST")
    void movedAndFoundPreserveWritesButRewritePost() {
        for (int statusCode : new int[]{301, 302}) {
            assertTrue(SafeHttpClient.methodSurvivesRedirect("PUT", statusCode), "PUT must survive " + statusCode);
            assertTrue(SafeHttpClient.methodSurvivesRedirect("PATCH", statusCode), "PATCH must survive " + statusCode);
            assertTrue(SafeHttpClient.methodSurvivesRedirect("DELETE", statusCode), "DELETE must survive " + statusCode);
            assertFalse(SafeHttpClient.methodSurvivesRedirect("POST", statusCode),
                    "POST is the one method the historical rewrite covers on " + statusCode);
        }
    }

    /** 303 See Other means GET, for every method that carries a body. */
    @Test
    @DisplayName("303 rewrites every body-carrying method to GET")
    void seeOtherAlwaysRewritesToGet() {
        assertFalse(SafeHttpClient.methodSurvivesRedirect("PUT", 303));
        assertFalse(SafeHttpClient.methodSurvivesRedirect("PATCH", 303));
        assertFalse(SafeHttpClient.methodSurvivesRedirect("DELETE", 303));
        assertFalse(SafeHttpClient.methodSurvivesRedirect("POST", 303));
    }

    /** 307/308 exist precisely to preserve the method and body. */
    @Test
    @DisplayName("307/308 preserve the method")
    void temporaryAndPermanentRedirectPreserveTheMethod() {
        for (int statusCode : new int[]{307, 308}) {
            assertTrue(SafeHttpClient.methodSurvivesRedirect("POST", statusCode));
            assertTrue(SafeHttpClient.methodSurvivesRedirect("PUT", statusCode));
            assertTrue(SafeHttpClient.methodSurvivesRedirect("DELETE", statusCode));
        }
    }

    /**
     * GET and HEAD carry no body, and the caller rebuilds them explicitly — HEAD
     * stays HEAD on every code (a size probe must not download the body), GET is
     * already GET.
     */
    @Test
    @DisplayName("GET and HEAD are rebuilt by the caller, never 'preserved' with a body")
    void bodylessMethodsAreHandledByTheCaller() {
        for (int statusCode : new int[]{301, 302, 303, 307, 308}) {
            assertFalse(SafeHttpClient.methodSurvivesRedirect("GET", statusCode));
            assertFalse(SafeHttpClient.methodSurvivesRedirect("HEAD", statusCode));
        }
    }
}
