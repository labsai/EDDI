/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.security.spaces;

import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.vertx.core.http.HttpServerRequest;
import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The {@code X-EDDI-Space} header files a new resource straight into a team
 * space. The one thing it must never do is let a caller plant a resource in a
 * space they are not in.
 */
@DisplayName("X-EDDI-Space")
class SpaceHeaderTest {

    private static SecurityIdentity alice() {
        SecurityIdentity identity = mock(SecurityIdentity.class);
        JsonWebToken jwt = mock(JsonWebToken.class);
        lenient().when(jwt.getName()).thenReturn("alice");
        lenient().when(jwt.<Object>getClaim("groups")).thenReturn(List.of("/engineering"));
        lenient().when(identity.getPrincipal()).thenReturn(jwt);
        return identity;
    }

    private static CurrentVertxRequest requestWithHeader(String value) {
        CurrentVertxRequest current = mock(CurrentVertxRequest.class);
        RoutingContext routingContext = mock(RoutingContext.class);
        HttpServerRequest request = mock(HttpServerRequest.class);
        when(current.getCurrent()).thenReturn(routingContext);
        when(routingContext.request()).thenReturn(request);
        when(request.getHeader(SpaceContext.SPACE_HEADER)).thenReturn(value);
        return current;
    }

    private static WorkspaceSettings settings(Optional<String> defaultTeam) {
        return new WorkspaceSettings(true, true, "groups", WorkspaceSettings.LEGACY_SHARED, defaultTeam);
    }

    @Nested
    @DisplayName("where a new resource lands")
    class WriteSpace {

        @Test
        @DisplayName("a space the caller belongs to wins over the deployment default")
        void headerWins() {
            var ctx = new SpaceContext(alice(), settings(Optional.of("finance")), requestWithHeader(" team:engineering "));

            assertEquals(Subjects.teamSpace("engineering"), ctx.defaultWriteSpace());
        }

        @Test
        @DisplayName("a space the caller is not in is ignored, never honoured")
        void foreignSpaceIgnored() {
            var ctx = new SpaceContext(alice(), settings(Optional.empty()), requestWithHeader(Subjects.teamSpace("finance")));

            assertEquals(Subjects.personalSpace("alice"), ctx.defaultWriteSpace());
        }

        @Test
        @DisplayName("without the header, the default team, then the personal space")
        void fallbacks() {
            assertEquals(Subjects.teamSpace("engineering"),
                    new SpaceContext(alice(), settings(Optional.of("engineering")), requestWithHeader(null)).defaultWriteSpace());
            assertEquals(Subjects.personalSpace("alice"),
                    new SpaceContext(alice(), settings(Optional.empty()), requestWithHeader(null)).defaultWriteSpace());
        }
    }

    @Nested
    @DisplayName("the request filter")
    class Filter {

        private ContainerRequestContext request(String method, String header) {
            var request = mock(ContainerRequestContext.class);
            when(request.getMethod()).thenReturn(method);
            lenient().when(request.getHeaderString(SpaceContext.SPACE_HEADER)).thenReturn(header);
            return request;
        }

        private SpaceHeaderFilter filter() {
            var s = settings(Optional.empty());
            return new SpaceHeaderFilter(new SpaceContext(alice(), s), s);
        }

        @Test
        @DisplayName("refuses a create into a foreign space before anything is written")
        void refusesForeignSpace() {
            var request = request("POST", Subjects.teamSpace("finance"));

            filter().filter(request);

            var response = ArgumentCaptor.forClass(Response.class);
            verify(request).abortWith(response.capture());
            assertEquals(403, response.getValue().getStatus());
        }

        /**
         * Security review 2026-11, B10: the header is matched exactly and
         * case-sensitively — group names are case-sensitive — and that stays. The
         * refusal used to call the caller a non-member of their own team when the id
         * merely differed in case or carried a trailing slash.
         */
        @Test
        @DisplayName("a near miss of the caller's own space is still refused, with a message that says why instead of 'not a member'")
        void nearMissIsRefusedWithAnExplanation() {
            for (String nearMiss : List.of("TEAM:engineering", "team:Engineering", "team:engineering/", "team:/engineering")) {
                var request = request("POST", nearMiss);

                filter().filter(request);

                var response = ArgumentCaptor.forClass(Response.class);
                verify(request).abortWith(response.capture());
                assertEquals(403, response.getValue().getStatus(), nearMiss);
                String message = errorOf(response.getValue());
                assertTrue(message.contains("None of your spaces has the id '" + nearMiss + "'"), message);
                assertTrue(message.contains("case-sensitively"), message);
                assertTrue(message.contains("GET /workspaces"), message);
                assertFalse(message.contains("not a member"), message);
                assertFalse(message.contains(Subjects.teamSpace("engineering") + "'"), "the caller's spaces are not listed: " + message);
            }
        }

        @Test
        @DisplayName("the echoed header value is sanitised")
        void echoedHeaderIsSanitised() {
            var request = request("POST", "team:x" + (char) 7 + "bell");

            filter().filter(request);

            var response = ArgumentCaptor.forClass(Response.class);
            verify(request).abortWith(response.capture());
            assertTrue(errorOf(response.getValue()).contains("'team:xbell'"), errorOf(response.getValue()));
        }

        @SuppressWarnings("unchecked")
        private static String errorOf(Response response) {
            return ((Map<String, String>) response.getEntity()).get("error");
        }

        @Test
        @DisplayName("lets a create into the caller's own team through")
        void allowsOwnSpace() {
            var request = request("POST", Subjects.teamSpace("engineering"));

            filter().filter(request);

            verify(request, never()).abortWith(any());
        }

        @Test
        @DisplayName("ignores updates and reads, which never stamp a space")
        void ignoresNonCreates() {
            var put = request("PUT", Subjects.teamSpace("finance"));
            var get = request("GET", Subjects.teamSpace("finance"));

            filter().filter(put);
            filter().filter(get);

            verify(put, never()).abortWith(any());
            verify(get, never()).abortWith(any());
        }

        @Test
        @DisplayName("means nothing while authentication is off")
        void noOpWithoutAuth() {
            var off = new WorkspaceSettings(false, false, "groups", WorkspaceSettings.LEGACY_SHARED, Optional.empty());
            var request = request("POST", Subjects.teamSpace("finance"));

            new SpaceHeaderFilter(new SpaceContext(alice(), off), off).filter(request);

            verify(request, never()).abortWith(any());
        }
    }
}
