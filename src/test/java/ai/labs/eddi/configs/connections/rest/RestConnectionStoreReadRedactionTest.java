/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.configs.connections.rest;

import ai.labs.eddi.configs.connections.IConnectionStore;
import ai.labs.eddi.configs.connections.model.AuthType;
import ai.labs.eddi.configs.connections.model.Binding;
import ai.labs.eddi.configs.connections.model.ConnectionConfiguration;
import ai.labs.eddi.configs.connections.model.OAuthConfig;
import ai.labs.eddi.configs.connections.model.StaticAuth;
import ai.labs.eddi.configs.connections.names.IConnectionNameClaimStore;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.schema.IJsonSchemaCreator;
import ai.labs.eddi.connections.ConnectionRegistry;
import ai.labs.eddi.connections.grants.IConnectionGrantStore;
import ai.labs.eddi.engine.security.spaces.ResourceAccessGuard;
import ai.labs.eddi.secrets.ISecretProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An editor may read a connection document; a legacy literal in it must not
 * come along.
 * <p>
 * Each legacy shape is paired with the valid one it resembles, because a
 * redactor tested only on literals passes just as well when it redacts
 * everything — and a picker that shows every connection's header template as
 * "redacted" is broken for every connection saved correctly.
 */
@DisplayName("RestConnectionStore — reading a connection as a non-admin")
class RestConnectionStoreReadRedactionTest {

    private static final String ID = "68a1b2c3d4e5f60718293a4b";

    private IConnectionStore connectionStore;
    private ResourceAccessGuard accessGuard;
    private RestConnectionStore rest;

    @BeforeEach
    void setUp() {
        connectionStore = mock(IConnectionStore.class);
        accessGuard = mock(ResourceAccessGuard.class);
        rest = new RestConnectionStore(connectionStore, mock(IDocumentDescriptorStore.class), mock(IJsonSchemaCreator.class),
                mock(ConnectionRegistry.class), mock(IConnectionGrantStore.class), mock(ISecretProvider.class), true, accessGuard,
                mock(IConnectionNameClaimStore.class));
    }

    private ConnectionConfiguration read(ConnectionConfiguration stored, boolean admin) throws Exception {
        when(connectionStore.read(ID, 1)).thenReturn(stored);
        when(accessGuard.isAdmin()).thenReturn(admin);
        return rest.readConnection(ID, 1);
    }

    private static ConnectionConfiguration oauth(String clientSecret, Map<String, String> extraAuthParams) {
        var connection = new ConnectionConfiguration();
        connection.setName("google-drive");
        connection.setAuthType(AuthType.OAUTH2_AUTHORIZATION_CODE);
        connection.setBinding(Binding.PER_USER);
        connection.setBaseUrlAllowlist(List.of("https://www.googleapis.com"));
        var oauth = new OAuthConfig();
        oauth.setTokenUrl("https://oauth2.googleapis.com/token");
        oauth.setAuthorizationUrl("https://accounts.google.com/o/oauth2/v2/auth");
        oauth.setClientId("client-id");
        oauth.setClientSecret(clientSecret);
        oauth.setExtraAuthParams(extraAuthParams);
        connection.setOauth(oauth);
        return connection;
    }

    private static ConnectionConfiguration staticAuth(AuthType authType, String valueTemplate, String passwordRef) {
        var connection = new ConnectionConfiguration();
        connection.setName("jira");
        connection.setAuthType(authType);
        connection.setBinding(Binding.SERVICE);
        connection.setBaseUrlAllowlist(List.of("https://api.atlassian.com"));
        var auth = new StaticAuth();
        auth.setHeaderName("Authorization");
        auth.setValueTemplate(valueTemplate);
        auth.setUsername(passwordRef == null ? null : "svc@example.com");
        auth.setPasswordRef(passwordRef);
        connection.setStaticAuth(auth);
        return connection;
    }

    @Test
    @DisplayName("an administrator reads the document as stored, legacy literal included, so it can be found and fixed")
    void adminReadsTheRawDocument() throws Exception {
        var stored = oauth("GOCSPX-literal-client-secret", Map.of("prompt", "consent"));

        var returned = read(stored, true);

        assertSame(stored, returned);
        assertEquals("GOCSPX-literal-client-secret", returned.getOauth().getClientSecret());
    }

    @Test
    @DisplayName("an editor reads a literal oauth.clientSecret redacted, and the stored document is untouched")
    void editorReadsALiteralClientSecretRedacted() throws Exception {
        var stored = oauth("GOCSPX-literal-client-secret", null);

        var returned = read(stored, false);

        assertEquals(ConnectionReadRedactor.REDACTED, returned.getOauth().getClientSecret());
        assertEquals("GOCSPX-literal-client-secret", stored.getOauth().getClientSecret(), "the stored or cached object must never be mutated");
        assertEquals("google-drive", returned.getName());
        assertEquals(AuthType.OAUTH2_AUTHORIZATION_CODE, returned.getAuthType());
        assertEquals(Binding.PER_USER, returned.getBinding());
        assertEquals("client-id", returned.getOauth().getClientId());
        assertEquals(List.of("https://www.googleapis.com"), returned.getBaseUrlAllowlist());
    }

    @Test
    @DisplayName("an editor reads a literal staticAuth.passwordRef redacted")
    void editorReadsALiteralPasswordRedacted() throws Exception {
        var returned = read(staticAuth(AuthType.BASIC, null, "hunter2-atlassian-api-token"), false);

        assertEquals(ConnectionReadRedactor.REDACTED, returned.getStaticAuth().getPasswordRef());
        assertEquals("svc@example.com", returned.getStaticAuth().getUsername());
        assertEquals("Authorization", returned.getStaticAuth().getHeaderName());
    }

    @Test
    @DisplayName("an editor reads a staticAuth.valueTemplate carrying a literal key redacted")
    void editorReadsALiteralValueTemplateRedacted() throws Exception {
        var returned = read(staticAuth(AuthType.STATIC, "Bearer sk-live-abcdefghijklmnop", null), false);

        assertEquals(ConnectionReadRedactor.REDACTED, returned.getStaticAuth().getValueTemplate());
        assertEquals("Authorization", returned.getStaticAuth().getHeaderName(), "the picker needs the header name");
    }

    @Test
    @DisplayName("an editor reads each extraAuthParams entry failing its key or value rule redacted, and the rest as stored")
    void editorReadsLiteralExtraAuthParamsRedacted() throws Exception {
        var params = new LinkedHashMap<String, String>();
        params.put("prompt", "consent");
        params.put("api_key", "abc123");
        params.put("login_hint", "sk-live-pasted-into-the-wrong-field");

        var returned = read(oauth("${vault:google-client-secret}", params), false);

        Map<String, String> readBack = returned.getOauth().getExtraAuthParams();
        assertEquals("consent", readBack.get("prompt"));
        assertEquals(ConnectionReadRedactor.REDACTED, readBack.get("api_key"), "a credential-shaped key");
        assertEquals(ConnectionReadRedactor.REDACTED, readBack.get("login_hint"), "a credential-shaped value");
    }

    @Test
    @DisplayName("an editor reads a document saved under today's rules unchanged")
    void editorReadsValidReferencesUnchanged() throws Exception {
        var params = new LinkedHashMap<String, String>();
        params.put("access_type", "offline");
        params.put("prompt", "consent");
        var oauthReturned = read(oauth("${vault:google-client-secret}", params), false);

        assertEquals("${vault:google-client-secret}", oauthReturned.getOauth().getClientSecret());
        assertEquals(params, oauthReturned.getOauth().getExtraAuthParams());

        var templateReturned = read(staticAuth(AuthType.STATIC, "Bearer ${vault:jira-token}", null), false);
        assertEquals("Bearer ${vault:jira-token}", templateReturned.getStaticAuth().getValueTemplate());

        var basicReturned = read(staticAuth(AuthType.BASIC, null, "${vars:jira-password}"), false);
        assertEquals("${vars:jira-password}", basicReturned.getStaticAuth().getPasswordRef());
    }
}
