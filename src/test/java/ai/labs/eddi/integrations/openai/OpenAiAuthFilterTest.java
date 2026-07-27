/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.openai;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.security.Principal;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OpenAiAuthFilter}.
 * <p>
 * The consequential cases are the refusals: serving a caller whose identity
 * cannot be established would merge every such caller into one shared
 * conversation — and therefore one shared memory.
 */
class OpenAiAuthFilterTest {

    private static final String KEY = "sk-eddi-secret";

    private ContainerRequestContext requestContext;
    private SecurityIdentity identity;
    private final Map<String, String> headers = new HashMap<>();
    private final Map<String, Object> properties = new HashMap<>();

    @BeforeEach
    void setUp() {
        headers.clear();
        properties.clear();

        requestContext = mock(ContainerRequestContext.class);
        identity = mock(SecurityIdentity.class);
        when(identity.isAnonymous()).thenReturn(true);

        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getPath()).thenReturn("v1/chat/completions");
        when(requestContext.getUriInfo()).thenReturn(uriInfo);
        when(requestContext.getHeaderString(any())).thenAnswer(inv -> headers.get(inv.getArgument(0)));
        // Capture published properties so the resolved userId can be asserted.
        org.mockito.Mockito.doAnswer(inv -> {
            properties.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(requestContext).setProperty(any(), any());
    }

    private void path(String path) {
        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getPath()).thenReturn(path);
        when(requestContext.getUriInfo()).thenReturn(uriInfo);
    }

    private void run(OpenAiCompatConfig config) {
        new OpenAiAuthFilter(config, identity).filter(requestContext);
    }

    private Response abortedResponse() {
        ArgumentCaptor<Response> captor = ArgumentCaptor.forClass(Response.class);
        verify(requestContext).abortWith(captor.capture());
        return captor.getValue();
    }

    private String resolvedUserId() {
        return (String) properties.get(OpenAiAuthFilter.PROP_USER_ID);
    }

    // ─── scope ───

    @Test
    void ignoresPathsOutsideV1() {
        path("agents/some-id/start");
        headers.put(OpenAiAuthFilter.HEADER_USER_ID, "alice");

        run(OpenAiTestFixtures.config(b -> b.apiKey = KEY));

        verify(requestContext, never()).abortWith(any());
        assertNull(resolvedUserId(), "a non-/v1 request must not be touched at all");
    }

    @Test
    void doesNotAuthenticate_whenAdapterDisabled() {
        // The resource answers with an explicit "disabled" error; an auth failure
        // here would misreport the reason.
        run(OpenAiTestFixtures.config(b -> {
            b.enabled = false;
            b.apiKey = KEY;
        }));

        verify(requestContext, never()).abortWith(any());
    }

    // ─── api-key mode ───

    @Test
    void rejectsMissingApiKey() {
        run(OpenAiTestFixtures.config(b -> b.apiKey = KEY));

        assertEquals(401, abortedResponse().getStatus());
    }

    @Test
    void rejectsWrongApiKey() {
        headers.put(HttpHeaders.AUTHORIZATION, "Bearer sk-wrong");

        run(OpenAiTestFixtures.config(b -> b.apiKey = KEY));

        assertEquals(401, abortedResponse().getStatus());
    }

    @Test
    void rejectsKeyWithoutBearerPrefix() {
        headers.put(HttpHeaders.AUTHORIZATION, KEY);

        run(OpenAiTestFixtures.config(b -> b.apiKey = KEY));

        assertEquals(401, abortedResponse().getStatus());
    }

    @Test
    void acceptsCorrectApiKey_caseInsensitiveScheme() {
        headers.put(HttpHeaders.AUTHORIZATION, "bearer " + KEY);
        headers.put(OpenAiAuthFilter.HEADER_USER_ID, "alice");

        run(OpenAiTestFixtures.config(b -> b.apiKey = KEY));

        verify(requestContext, never()).abortWith(any());
        assertEquals("alice", resolvedUserId());
    }

    @Test
    void skipsKeyCheck_whenNoKeyConfigured() {
        headers.put(OpenAiAuthFilter.HEADER_USER_ID, "alice");

        run(OpenAiTestFixtures.enabledConfig());

        verify(requestContext, never()).abortWith(any());
        assertEquals("alice", resolvedUserId());
    }

    // ─── identity resolution ───

    @Test
    void refusesWhenIdentityUnresolvable_ratherThanSharingAConversation() {
        headers.put(HttpHeaders.AUTHORIZATION, "Bearer " + KEY);
        // No X-OpenWebUI-User-Id, and anonymity not allowed.

        run(OpenAiTestFixtures.config(b -> b.apiKey = KEY));

        assertEquals(401, abortedResponse().getStatus());
        assertNull(resolvedUserId());
    }

    @Test
    void fallsBackToDefaultUser_onlyWhenAnonymityAllowed() {
        headers.put(HttpHeaders.AUTHORIZATION, "Bearer " + KEY);

        run(OpenAiTestFixtures.config(b -> {
            b.apiKey = KEY;
            b.allowAnonymous = true;
            b.defaultUser = "shared-user";
        }));

        verify(requestContext, never()).abortWith(any());
        assertEquals("shared-user", resolvedUserId());
    }

    @Test
    void ignoresUserHeader_whenNotTrusted() {
        headers.put(HttpHeaders.AUTHORIZATION, "Bearer " + KEY);
        headers.put(OpenAiAuthFilter.HEADER_USER_ID, "alice");

        run(OpenAiTestFixtures.config(b -> {
            b.apiKey = KEY;
            b.trustUserHeaders = false;
        }));

        assertEquals(401, abortedResponse().getStatus());
    }

    @Test
    void ignoresBlankUserHeader() {
        headers.put(HttpHeaders.AUTHORIZATION, "Bearer " + KEY);
        headers.put(OpenAiAuthFilter.HEADER_USER_ID, "   ");

        run(OpenAiTestFixtures.config(b -> b.apiKey = KEY));

        assertEquals(401, abortedResponse().getStatus());
    }

    // ─── OIDC mode ───

    @Test
    void oidcMode_usesPrincipal_andIgnoresApiKeyAndUserHeader() {
        Principal principal = mock(Principal.class);
        when(principal.getName()).thenReturn("oidc-subject");
        when(identity.isAnonymous()).thenReturn(false);
        when(identity.getPrincipal()).thenReturn(principal);
        headers.put(OpenAiAuthFilter.HEADER_USER_ID, "spoofed");
        // Deliberately no Authorization header: Quarkus already authenticated.

        run(OpenAiTestFixtures.config(b -> {
            b.httpPolicy = OpenAiCompatConfig.POLICY_AUTHENTICATED;
            b.apiKey = KEY;
        }));

        verify(requestContext, never()).abortWith(any());
        assertEquals("oidc-subject", resolvedUserId(),
                "the OIDC principal must win over a client-supplied user header");
    }

    @Test
    void oidcMode_refusesAnonymousIdentity() {
        when(identity.isAnonymous()).thenReturn(true);

        run(OpenAiTestFixtures.config(b -> b.httpPolicy = OpenAiCompatConfig.POLICY_AUTHENTICATED));

        assertEquals(401, abortedResponse().getStatus());
    }

    @Test
    void abortsOnce_whenApiKeyIsWrong() {
        headers.put(HttpHeaders.AUTHORIZATION, "Bearer sk-wrong");

        run(OpenAiTestFixtures.config(b -> b.apiKey = KEY));

        // A second abort (from identity resolution) would overwrite the 401 reason.
        verify(requestContext, times(1)).abortWith(any());
    }
}
