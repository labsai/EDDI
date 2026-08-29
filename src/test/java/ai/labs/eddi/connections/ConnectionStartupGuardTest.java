/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.connections;

import ai.labs.eddi.configs.connections.IConnectionStore;
import ai.labs.eddi.configs.connections.model.AuthType;
import ai.labs.eddi.configs.connections.model.Binding;
import ai.labs.eddi.configs.connections.model.ConnectionConfiguration;
import ai.labs.eddi.configs.connections.mongo.ConnectionStore;
import ai.labs.eddi.configs.descriptors.IDocumentDescriptorStore;
import ai.labs.eddi.configs.descriptors.model.DocumentDescriptor;
import ai.labs.eddi.connections.oauth.CredentialEndpointAllowlist;
import ai.labs.eddi.datastore.IResourceStore.ResourceNotFoundException;
import ai.labs.eddi.datastore.IResourceStore.ResourceStoreException;
import ai.labs.eddi.datastore.serialization.IDescriptorStore;
import ai.labs.eddi.engine.model.Deployment.Environment;
import ai.labs.eddi.integrations.openai.OpenAiCompatConfig;
import ai.labs.eddi.secrets.ISecretProvider;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The guard is split between refusals and reports, and the split is the whole
 * design: a bad {@code public-base-url} can only ever produce a broken OAuth
 * flow, so it fails the boot, while a questionable stored connection already
 * fails closed per request and would take a whole cluster down if it failed the
 * boot too. So each test asserts which of the two happened, and which setting
 * was named — "it threw" or "it logged something" would still pass while the
 * operator is left with nothing to act on.
 * <p>
 * A plain unit test rather than a {@code @QuarkusTest}: the observer is invoked
 * directly on a constructed instance, and {@link LaunchMode} is driven around
 * each test so the production-only checks can run without a container.
 */
class ConnectionStartupGuardTest {

    private static final String PRODUCTION_BASE_URL = "https://eddi.example.com";
    private static final String APPROVED_ENDPOINT = "https://auth.example.com";
    private static final String CONNECTION_URI = "eddi://ai.labs.connection/connectionstore/connections/";

    /** Fragments unique to one report each, so a match cannot come from another. */
    private static final String V1_DELEGATION_REPORT = "eddi.openai-compat.trust-user-headers=true";
    private static final String UNVERIFIED_IDENTITY_REPORT = "authorization.enabled=false";
    private static final String INACTIVE_VAULT_REPORT = "EDDI_VAULT_MASTER_KEY";
    private static final String ANY_PER_USER_REPORT = "PER_USER connection is stored";

    private IConnectionStore connectionStore;
    private IDocumentDescriptorStore descriptorStore;
    private ISecretProvider secretProvider;

    private final List<String> logRecords = new ArrayList<>();
    private Logger guardLogger;
    private Handler logHandler;
    private Level previousLevel;
    private boolean previousUseParentHandlers;
    private LaunchMode previousLaunchMode;

    @BeforeEach
    void setUp() {
        connectionStore = mock(IConnectionStore.class);
        descriptorStore = mock(IDocumentDescriptorStore.class);
        secretProvider = mock(ISecretProvider.class);
        when(secretProvider.isAvailable()).thenReturn(true);

        previousLaunchMode = LaunchMode.current();
        LaunchMode.set(LaunchMode.NORMAL);

        logHandler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                // The printf-style calls keep their arguments out of the message, so both
                // halves are kept — otherwise an assertion on a logged value silently
                // matches nothing.
                logRecords.add(record.getMessage() + " " + Arrays.toString(record.getParameters()));
            }

            @Override
            public void flush() {
                // nothing is buffered
            }

            @Override
            public void close() {
                // nothing to release
            }
        };
        // src/test/resources/logging.properties silences the whole ai.labs.eddi
        // namespace, so without raising the level the records never reach a handler.
        // Detaching the parent handlers keeps the deliberately alarming ERROR lines
        // out of the surefire output.
        guardLogger = Logger.getLogger(ConnectionStartupGuard.class.getName());
        previousLevel = guardLogger.getLevel();
        previousUseParentHandlers = guardLogger.getUseParentHandlers();
        guardLogger.setLevel(Level.ALL);
        guardLogger.setUseParentHandlers(false);
        guardLogger.addHandler(logHandler);
    }

    @AfterEach
    void tearDown() {
        guardLogger.removeHandler(logHandler);
        guardLogger.setLevel(previousLevel);
        guardLogger.setUseParentHandlers(previousUseParentHandlers);
        // LaunchMode is process-wide static state; a leaked one would change what the
        // next test class in this fork considers production.
        LaunchMode.set(previousLaunchMode);
    }

    // --- Feature switch -----------------------------------------------------

    @Test
    @DisplayName("a disabled connections feature is not inspected at all")
    void disabledFeatureIsNotInspected() {
        var everythingWrong = guard(new ConnectionsConfig(false, ""), noApprovedEndpoints(),
                openAiCompat(true, OpenAiCompatConfig.POLICY_PERMIT, true), false);

        assertDoesNotThrow(() -> start(everythingWrong));

        verifyNoInteractions(descriptorStore, connectionStore);
        verify(secretProvider, never()).isAvailable();
        assertTrue(logRecords.isEmpty(), "a feature that is off has nothing to report; saw: " + logRecords);
    }

    // --- The public base URL, which becomes the redirect_uri ----------------

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    @DisplayName("enabling connections without a public base URL refuses the boot and names the property")
    void blankPublicBaseUrlRefusesStartup(String publicBaseUrl) {
        var guard = guardWithBaseUrl(publicBaseUrl);

        var failure = assertThrows(IllegalStateException.class, () -> start(guard));

        assertTrue(failure.getMessage().contains("eddi.connections.public-base-url"), failure.getMessage());
    }

    @Test
    @DisplayName("a public base URL that will not parse refuses the boot and quotes the value")
    void unparseablePublicBaseUrlRefusesStartup() {
        var guard = guardWithBaseUrl("https://eddi example.com");

        var failure = assertThrows(IllegalStateException.class, () -> start(guard));

        assertTrue(failure.getMessage().contains("not a valid URL"), failure.getMessage());
        assertTrue(failure.getMessage().contains("https://eddi example.com"), "the operator has to be told which value to fix");
        assertNotNull(failure.getCause(), "the parse failure is the only evidence of what is wrong with the value");
    }

    @ParameterizedTest
    @EnumSource(value = LaunchMode.class, names = {"DEVELOPMENT", "TEST"})
    @DisplayName("plain http on localhost is accepted while developing and while testing")
    void devAndTestModesAcceptLocalhost(LaunchMode launchMode) {
        LaunchMode.set(launchMode);

        assertDoesNotThrow(() -> start(guardWithBaseUrl("http://localhost:7070")));
    }

    @Test
    @DisplayName("plain http is refused once the deployment is running for real")
    void productionRefusesTheLocalhostShape() {
        var guard = guardWithBaseUrl("http://localhost:7070");

        var failure = assertThrows(IllegalStateException.class, () -> start(guard));

        assertTrue(failure.getMessage().contains("bare https origin"), failure.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://eddi.example.com", "https://ops@eddi.example.com", "https://eddi.example.com?tenant=acme",
            "https://eddi.example.com#callback", "https://eddi.example.com/eddi", "https:eddi.example.com"})
    @DisplayName("in production every shape a provider would not match exactly is refused")
    void productionRefusesAnythingButABareHttpsOrigin(String publicBaseUrl) {
        var guard = guardWithBaseUrl(publicBaseUrl);

        var failure = assertThrows(IllegalStateException.class, () -> start(guard));

        assertTrue(failure.getMessage().contains("bare https origin"), failure.getMessage());
        assertTrue(failure.getMessage().contains(publicBaseUrl), "the operator has to be told which value to fix");
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://eddi.example.com", "https://eddi.example.com/", "https://eddi.example.com:8443"})
    @DisplayName("a bare https origin passes, with or without a trailing slash or an explicit port")
    void productionAcceptsABareHttpsOrigin(String publicBaseUrl) {
        assertDoesNotThrow(() -> start(guardWithBaseUrl(publicBaseUrl)));
    }

    // --- The credential endpoint allowlist ----------------------------------

    @Test
    @DisplayName("an empty credential endpoint allowlist warns instead of refusing, because static connections still work")
    void emptyCredentialEndpointAllowlistOnlyWarns() {
        var guard = guard(new ConnectionsConfig(true, PRODUCTION_BASE_URL), noApprovedEndpoints(), openAiCompatOff(), true);

        assertDoesNotThrow(() -> start(guard));

        assertTrue(logged("eddi.connections.credential-endpoint-allowlist is empty"), logRecords.toString());
    }

    @Test
    @DisplayName("a configured allowlist is echoed at boot so the approved origins are visible without reading the config")
    void configuredCredentialEndpointAllowlistIsEchoed() {
        assertDoesNotThrow(() -> start(enabledGuard()));

        assertTrue(logged("Credential endpoints allowed"), logRecords.toString());
        assertTrue(logged(APPROVED_ENDPOINT), "the line is only useful if it names the origins; saw: " + logRecords);
        assertFalse(logged("is empty"), "an allowlist with an entry in it is not empty; saw: " + logRecords);
    }

    // --- Stored connections: reported, never refused ------------------------

    @Test
    @DisplayName("a stored PER_USER connection is reported when /v1 believes caller-supplied user ids")
    void perUserWithTrustedUserHeadersIsReported() throws Exception {
        storedConnections(perUserConnection());

        var openAiCompat = openAiCompat(true, OpenAiCompatConfig.POLICY_PERMIT, true);
        assertDoesNotThrow(() -> start(enabledGuard(openAiCompat, true)));

        assertTrue(logged(V1_DELEGATION_REPORT), logRecords.toString());
    }

    @Test
    @DisplayName("the /v1 report is withheld when the adapter has OIDC authenticate its callers")
    void perUserReportIsWithheldInOidcMode() throws Exception {
        storedConnections(perUserConnection());

        var openAiCompat = openAiCompat(true, OpenAiCompatConfig.POLICY_AUTHENTICATED, true);
        assertDoesNotThrow(() -> start(enabledGuard(openAiCompat, true)));

        assertFalse(logged(V1_DELEGATION_REPORT), logRecords.toString());
    }

    @Test
    @DisplayName("the /v1 report is withheld when caller-supplied user ids are already distrusted")
    void perUserReportIsWithheldWhenUserHeadersAreDistrusted() throws Exception {
        storedConnections(perUserConnection());

        var openAiCompat = openAiCompat(true, OpenAiCompatConfig.POLICY_PERMIT, false);
        assertDoesNotThrow(() -> start(enabledGuard(openAiCompat, true)));

        assertFalse(logged(V1_DELEGATION_REPORT), logRecords.toString());
    }

    @Test
    @DisplayName("the /v1 report is withheld when the OpenAI-compatible surface is switched off")
    void perUserReportIsWithheldWhenAdapterIsDisabled() throws Exception {
        storedConnections(perUserConnection());

        var openAiCompat = openAiCompat(false, OpenAiCompatConfig.POLICY_PERMIT, true);
        assertDoesNotThrow(() -> start(enabledGuard(openAiCompat, true)));

        assertFalse(logged(V1_DELEGATION_REPORT), logRecords.toString());
    }

    @Test
    @DisplayName("a stored PER_USER connection is reported when nothing verifies who the caller is")
    void perUserWithoutAuthorizationIsReported() throws Exception {
        storedConnections(perUserConnection());

        assertDoesNotThrow(() -> start(enabledGuard(openAiCompatOff(), false)));

        assertTrue(logged(UNVERIFIED_IDENTITY_REPORT), logRecords.toString());
    }

    @Test
    @DisplayName("neither PER_USER report is made when every stored connection is service-bound")
    void serviceBoundConnectionsAreNotReported() throws Exception {
        storedConnections(staticConnection(), serviceOAuthConnection());

        var openAiCompat = openAiCompat(true, OpenAiCompatConfig.POLICY_PERMIT, true);
        assertDoesNotThrow(() -> start(enabledGuard(openAiCompat, false)));

        assertFalse(logged(ANY_PER_USER_REPORT), logRecords.toString());
    }

    @Test
    @DisplayName("a stored OAuth connection is reported when the secrets vault is inactive")
    void oauthWithInactiveVaultIsReported() throws Exception {
        storedConnections(serviceOAuthConnection());
        when(secretProvider.isAvailable()).thenReturn(false);

        assertDoesNotThrow(() -> start(enabledGuard()));

        assertTrue(logged(INACTIVE_VAULT_REPORT), logRecords.toString());
    }

    @Test
    @DisplayName("an OAuth connection backed by an active vault is not reported")
    void oauthWithActiveVaultIsNotReported() throws Exception {
        storedConnections(serviceOAuthConnection());

        assertDoesNotThrow(() -> start(enabledGuard()));

        verify(secretProvider).isAvailable();
        assertFalse(logged(INACTIVE_VAULT_REPORT), logRecords.toString());
    }

    @Test
    @DisplayName("a deployment with no OAuth connection never asks the vault whether it is active")
    void staticOnlyDeploymentNeverConsultsTheVault() throws Exception {
        storedConnections(staticConnection());
        when(secretProvider.isAvailable()).thenReturn(false);

        assertDoesNotThrow(() -> start(enabledGuard()));

        verify(secretProvider, never()).isAvailable();
        assertFalse(logged(INACTIVE_VAULT_REPORT), logRecords.toString());
    }

    @Test
    @DisplayName("a half-written connection with no authType is not mistaken for an OAuth one")
    void connectionWithoutAuthTypeIsNotTreatedAsOAuth() throws Exception {
        storedConnections(connection("half-written", null, Binding.SERVICE));
        when(secretProvider.isAvailable()).thenReturn(false);

        assertDoesNotThrow(() -> start(enabledGuard()));

        verify(secretProvider, never()).isAvailable();
        assertFalse(logged(INACTIVE_VAULT_REPORT), logRecords.toString());
    }

    @Test
    @DisplayName("every applicable report is made in one pass rather than only the first")
    void allApplicableReportsAccumulate() throws Exception {
        storedConnections(perUserConnection());
        when(secretProvider.isAvailable()).thenReturn(false);

        var openAiCompat = openAiCompat(true, OpenAiCompatConfig.POLICY_PERMIT, true);
        assertDoesNotThrow(() -> start(enabledGuard(openAiCompat, false)));

        assertTrue(logged(V1_DELEGATION_REPORT), logRecords.toString());
        assertTrue(logged(UNVERIFIED_IDENTITY_REPORT), logRecords.toString());
        assertTrue(logged(INACTIVE_VAULT_REPORT), logRecords.toString());
    }

    @Test
    @DisplayName("a correctly configured deployment boots with nothing reported but the approved origins")
    void cleanConfigurationReportsNothing() throws Exception {
        storedConnections(perUserConnection());

        var openAiCompat = openAiCompat(true, OpenAiCompatConfig.POLICY_AUTHENTICATED, false);
        assertDoesNotThrow(() -> start(enabledGuard(openAiCompat, true)));

        assertFalse(logged(ANY_PER_USER_REPORT), logRecords.toString());
        assertFalse(logged(INACTIVE_VAULT_REPORT), logRecords.toString());
        assertTrue(logged("Credential endpoints allowed"), "the one line a healthy boot does print; saw: " + logRecords);
    }

    // --- Enumerating the store ----------------------------------------------

    @Test
    @DisplayName("the whole connection collection is enumerated unpaged, with deleted documents excluded")
    void enumeratesEveryStoredConnection() throws Exception {
        storedConnections(staticConnection());

        assertDoesNotThrow(() -> start(enabledGuard()));

        verify(descriptorStore).readDescriptors(ConnectionStore.RESOURCE_TYPE, "", 0, IDescriptorStore.NO_LIMIT, false);
    }

    @Test
    @DisplayName("a store that cannot be read at boot is a warning naming the failure, never a failed boot")
    void unreachableStoreDoesNotBlockStartup() throws Exception {
        doThrow(new ResourceStoreException("connection refused")).when(descriptorStore)
                .readDescriptors(anyString(), any(), any(), any(), anyBoolean());

        var openAiCompat = openAiCompat(true, OpenAiCompatConfig.POLICY_PERMIT, true);
        assertDoesNotThrow(() -> start(enabledGuard(openAiCompat, false)));

        assertTrue(logged("Could not enumerate stored connections"), logRecords.toString());
        assertTrue(logged("ResourceStoreException"), "the warning is only actionable if it says what failed; saw: " + logRecords);
        assertFalse(logged(ANY_PER_USER_REPORT), "nothing may be concluded about connections that were never read");
    }

    @Test
    @DisplayName("a store that returns no descriptor list at all is treated as an empty one")
    void nullDescriptorListIsTolerated() throws Exception {
        doReturn(null).when(descriptorStore).readDescriptors(anyString(), any(), any(), any(), anyBoolean());

        var openAiCompat = openAiCompat(true, OpenAiCompatConfig.POLICY_PERMIT, true);
        assertDoesNotThrow(() -> start(enabledGuard(openAiCompat, false)));

        verifyNoInteractions(connectionStore);
        assertFalse(logged(ANY_PER_USER_REPORT), logRecords.toString());
        assertFalse(logged("Could not enumerate stored connections"),
                "an empty collection is a normal deployment, not a store that failed to answer");
    }

    @Test
    @DisplayName("a descriptor with no resource URI is skipped rather than ending the scan")
    void descriptorWithoutResourceIsSkipped() throws Exception {
        storedDescriptors(descriptorOf(null));

        assertDoesNotThrow(() -> start(enabledGuard()));

        verifyNoInteractions(connectionStore);
    }

    @Test
    @DisplayName("a descriptor URI with no path carries no connection id and is skipped")
    void descriptorUriWithoutPathIsSkipped() throws Exception {
        storedDescriptors(descriptorOf("eddi:ai.labs.connection"));

        assertDoesNotThrow(() -> start(enabledGuard()));

        verifyNoInteractions(connectionStore);
    }

    @Test
    @DisplayName("the connection is read at the version its descriptor URI names")
    void connectionIsReadAtTheVersionNamedByItsDescriptor() throws Exception {
        String id = connectionId(0);
        storedDescriptors(descriptorOf(CONNECTION_URI + id + "?version=3"));
        doReturn(staticConnection()).when(connectionStore).read(id, 3);

        assertDoesNotThrow(() -> start(enabledGuard()));

        verify(connectionStore).read(id, 3);
    }

    @Test
    @DisplayName("the version is found even when another query parameter precedes it")
    void versionIsFoundAfterOtherQueryParameters() throws Exception {
        String id = connectionId(0);
        storedDescriptors(descriptorOf(CONNECTION_URI + id + "?tenant=acme&version=7"));
        doReturn(staticConnection()).when(connectionStore).read(id, 7);

        assertDoesNotThrow(() -> start(enabledGuard()));

        verify(connectionStore).read(id, 7);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "?version=", "?version=latest", "?tenant=acme"})
    @DisplayName("a descriptor URI naming no usable version falls back to the first one")
    void unusableVersionFallsBackToVersionOne(String query) throws Exception {
        String id = connectionId(0);
        storedDescriptors(descriptorOf(CONNECTION_URI + id + query));
        doReturn(staticConnection()).when(connectionStore).read(id, 1);

        assertDoesNotThrow(() -> start(enabledGuard()));

        verify(connectionStore).read(id, 1);
    }

    @Test
    @DisplayName("a descriptor whose connection no longer exists contributes nothing to the verdict")
    void descriptorWithoutAStoredConnectionContributesNothing() throws Exception {
        String id = connectionId(0);
        storedDescriptors(descriptorOf(CONNECTION_URI + id + "?version=1"));
        doReturn(null).when(connectionStore).read(id, 1);

        var openAiCompat = openAiCompat(true, OpenAiCompatConfig.POLICY_PERMIT, true);
        assertDoesNotThrow(() -> start(enabledGuard(openAiCompat, false)));

        verify(connectionStore).read(id, 1);
        assertFalse(logged(ANY_PER_USER_REPORT), logRecords.toString());
    }

    @Test
    @DisplayName("one unreadable connection does not hide the ones stored after it")
    void unreadableConnectionIsSkippedWithoutAbandoningTheScan() throws Exception {
        String unreadableId = connectionId(0);
        String perUserId = connectionId(1);
        storedDescriptors(descriptorOf(CONNECTION_URI + unreadableId + "?version=1"), descriptorOf(CONNECTION_URI + perUserId + "?version=1"));
        doThrow(new ResourceNotFoundException("deleted mid-scan")).when(connectionStore).read(unreadableId, 1);
        doReturn(perUserConnection()).when(connectionStore).read(perUserId, 1);

        assertDoesNotThrow(() -> start(enabledGuard(openAiCompatOff(), false)));

        assertTrue(logged(UNVERIFIED_IDENTITY_REPORT),
                "the connection after the unreadable one must still be inspected; saw: " + logRecords);
    }

    // --- Fixtures ------------------------------------------------------------

    private static void start(ConnectionStartupGuard guard) {
        guard.onStart(new StartupEvent());
    }

    private ConnectionStartupGuard guard(ConnectionsConfig connectionsConfig, CredentialEndpointAllowlist allowlist,
                                         OpenAiCompatConfig openAiCompatConfig, boolean authorizationEnabled) {
        return new ConnectionStartupGuard(connectionsConfig, allowlist, connectionStore, descriptorStore, secretProvider, openAiCompatConfig,
                authorizationEnabled);
    }

    /** Enabled, production-shaped and otherwise beyond reproach. */
    private ConnectionStartupGuard enabledGuard() {
        return enabledGuard(openAiCompatOff(), true);
    }

    private ConnectionStartupGuard enabledGuard(OpenAiCompatConfig openAiCompatConfig, boolean authorizationEnabled) {
        return guard(new ConnectionsConfig(true, PRODUCTION_BASE_URL), approvedEndpoints(), openAiCompatConfig, authorizationEnabled);
    }

    private ConnectionStartupGuard guardWithBaseUrl(String publicBaseUrl) {
        return guard(new ConnectionsConfig(true, publicBaseUrl), approvedEndpoints(), openAiCompatOff(), true);
    }

    private static CredentialEndpointAllowlist approvedEndpoints() {
        return new CredentialEndpointAllowlist(Optional.of(APPROVED_ENDPOINT));
    }

    private static CredentialEndpointAllowlist noApprovedEndpoints() {
        return new CredentialEndpointAllowlist(Optional.empty());
    }

    private static OpenAiCompatConfig openAiCompat(boolean enabled, String httpPolicy, boolean trustUserHeaders) {
        return new OpenAiCompatConfig(enabled, Optional.of("sk-eddi-test"), httpPolicy, trustUserHeaders, false, "openai-anonymous",
                Environment.production, 120, 64, 30, true);
    }

    private static OpenAiCompatConfig openAiCompatOff() {
        return openAiCompat(false, OpenAiCompatConfig.POLICY_PERMIT, true);
    }

    private static ConnectionConfiguration connection(String name, AuthType authType, Binding binding) {
        var connection = new ConnectionConfiguration();
        connection.setName(name);
        connection.setAuthType(authType);
        connection.setBinding(binding);
        connection.setBaseUrlAllowlist(List.of("https://api.example.com"));
        return connection;
    }

    private static ConnectionConfiguration perUserConnection() {
        return connection("google-drive", AuthType.OAUTH2_AUTHORIZATION_CODE, Binding.PER_USER);
    }

    private static ConnectionConfiguration serviceOAuthConnection() {
        return connection("amplitude", AuthType.OAUTH2_CLIENT_CREDENTIALS, Binding.SERVICE);
    }

    private static ConnectionConfiguration staticConnection() {
        return connection("jira", AuthType.STATIC, Binding.SERVICE);
    }

    private static DocumentDescriptor descriptorOf(String resourceUri) {
        var descriptor = new DocumentDescriptor();
        descriptor.setResource(resourceUri == null ? null : URI.create(resourceUri));
        return descriptor;
    }

    /**
     * Hex and 24 characters, because EDDI's id validation rejects anything else — a
     * shorter or non-hex id yields a null id and makes assertions pass for the
     * wrong reason.
     */
    private static String connectionId(int index) {
        return "507f1f77bcf86cd7994390" + (10 + index);
    }

    private void storedDescriptors(DocumentDescriptor... descriptors) throws Exception {
        doReturn(List.of(descriptors)).when(descriptorStore).readDescriptors(anyString(), any(), any(), any(), anyBoolean());
    }

    private void storedConnections(ConnectionConfiguration... connections) throws Exception {
        var descriptors = new DocumentDescriptor[connections.length];
        for (int index = 0; index < connections.length; index++) {
            String id = connectionId(index);
            descriptors[index] = descriptorOf(CONNECTION_URI + id + "?version=1");
            doReturn(connections[index]).when(connectionStore).read(id, 1);
        }
        storedDescriptors(descriptors);
    }

    private boolean logged(String fragment) {
        return logRecords.stream().anyMatch(record -> record.contains(fragment));
    }
}
