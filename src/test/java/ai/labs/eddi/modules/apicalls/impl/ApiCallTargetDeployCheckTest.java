/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.apicalls.impl;

import ai.labs.eddi.configs.apicalls.model.ApiCall;
import ai.labs.eddi.configs.apicalls.model.ApiCallsConfiguration;
import ai.labs.eddi.configs.apicalls.model.Request;
import ai.labs.eddi.configs.variables.GlobalVariableResolver;
import ai.labs.eddi.datastore.serialization.IJsonSerialization;
import ai.labs.eddi.engine.httpclient.IHttpClient;
import ai.labs.eddi.engine.lifecycle.exceptions.WorkflowConfigurationException;
import ai.labs.eddi.engine.memory.IMemoryItemConverter;
import ai.labs.eddi.engine.runtime.IRuntime;
import ai.labs.eddi.engine.runtime.client.configuration.IResourceClientLibrary;
import ai.labs.eddi.engine.security.CallerIdentityContext;
import ai.labs.eddi.engine.security.CallerIdentityResolver;
import ai.labs.eddi.modules.llm.tools.UrlValidationUtils;
import ai.labs.eddi.secrets.SecretResolver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Security review finding B1: with SSRF protection on, an httpcall whose
 * literal target the execution-time check refuses
 * ({@code http://127.0.0.1:7070/...}) used to save and deploy, and only failed
 * when the first conversation started — as a bare 500. It now fails at deploy,
 * with the same decision the request path applies
 * ({@link ApiCallExecutor#checkOutboundTarget}).
 * <p>
 * The deploy tests drive a real {@link ApiCallsTask} over a real
 * {@link ApiCallExecutor} with literal IP targets only, which the JDK resolver
 * parses without DNS. The decision tests inject a resolver.
 */
@DisplayName("httpcall targets refused by the SSRF check fail at deploy")
class ApiCallTargetDeployCheckTest {

    private static ApiCallExecutor executor(boolean ssrfProtection, String allowedHosts) {
        CallerIdentityResolver callerIdentityResolver = mock(CallerIdentityResolver.class);
        return new ApiCallExecutor(mock(IHttpClient.class), mock(IJsonSerialization.class), mock(IRuntime.class), mock(PrePostUtils.class),
                mock(GlobalVariableResolver.class), mock(SecretResolver.class), callerIdentityResolver, mock(CallerIdentityContext.class),
                new RequestRedactor(callerIdentityResolver), null, ssrfProtection, Optional.ofNullable(allowedHosts), 30_000L, 2_000_000);
    }

    @Nested
    @DisplayName("deploy (ApiCallsTask.configure)")
    class Deploy {

        private Object deploy(boolean ssrfProtection, String allowedHosts, String targetServerUrl, String... paths) throws Exception {
            var config = new ApiCallsConfiguration();
            config.setTargetServerUrl(targetServerUrl);
            var calls = new ArrayList<ApiCall>();
            for (String path : paths) {
                var request = new Request();
                request.setPath(path);
                var call = new ApiCall();
                call.setName("call");
                call.setRequest(request);
                calls.add(call);
            }
            config.setHttpCalls(calls);
            var resources = mock(IResourceClientLibrary.class);
            when(resources.getResource(any(URI.class), eq(ApiCallsConfiguration.class))).thenReturn(config);
            var task = new ApiCallsTask(resources, mock(IMemoryItemConverter.class), executor(ssrfProtection, allowedHosts));
            return task.configure(Map.of("uri", "eddi://config/123"), Map.of());
        }

        @Test
        @DisplayName("protection on: a loopback targetServerUrl is refused, naming the host and the allow-list entry that admits it")
        void loopbackRefused() {
            var refused = assertThrows(WorkflowConfigurationException.class, () -> deploy(true, null, "http://127.0.0.1:7070", "/agents"));
            String message = refused.getMessage();
            assertTrue(message.contains("'127.0.0.1'"), message);
            assertTrue(message.contains("eddi.security.ssrf-protection.allowed-hosts"), message);
            assertTrue(message.contains("'127.0.0.1:7070'"), message);
            assertTrue(message.contains("public address"), message);
            assertTrue(message.contains("uri:eddi://"), message);
        }

        @Test
        @DisplayName("protection on: a private literal in an absolute request.path is refused too")
        void privateAbsolutePathRefused() {
            var refused = assertThrows(WorkflowConfigurationException.class,
                    () -> deploy(true, null, "https://93.184.216.34", "/ok", " http://10.0.0.5:9200/_search"));
            assertTrue(refused.getMessage().contains("'10.0.0.5'"), refused.getMessage());
            assertTrue(refused.getMessage().contains("'10.0.0.5:9200'"), refused.getMessage());
        }

        @Test
        @DisplayName("protection on: localhost is refused without a lookup")
        void localhostRefused() {
            var refused = assertThrows(WorkflowConfigurationException.class, () -> deploy(true, null, "http://localhost:7070"));
            assertTrue(refused.getMessage().contains("'localhost:7070'"), refused.getMessage());
        }

        @Test
        @DisplayName("protection on: an allow-listed host deploys — host:port or a bare host")
        void allowListedDeploys() {
            assertDoesNotThrow(() -> deploy(true, "127.0.0.1:7070", "http://127.0.0.1:7070", "/agents"));
            assertDoesNotThrow(() -> deploy(true, "10.0.0.5", "https://93.184.216.34", "http://10.0.0.5:9200/_search"));
        }

        @Test
        @DisplayName("protection on: an allow-list entry for another port does not admit the target")
        void otherPortNotAdmitted() {
            assertThrows(WorkflowConfigurationException.class, () -> deploy(true, "127.0.0.1:8080", "http://127.0.0.1:7070"));
        }

        @Test
        @DisplayName("protection on: a public literal deploys")
        void publicDeploys() {
            assertDoesNotThrow(() -> deploy(true, null, "https://93.184.216.34/api/", "/v1/{properties.id}"));
        }

        @Test
        @DisplayName("protection off: loopback and private targets deploy, as before")
        void protectionOffDeploys() {
            assertDoesNotThrow(() -> deploy(false, null, "http://127.0.0.1:7070", "http://10.0.0.5:9200/_search"));
            assertDoesNotThrow(() -> deploy(false, null, "http://localhost:7070"));
        }

        @ParameterizedTest(name = "protection {0}")
        @ValueSource(booleans = {true, false})
        @DisplayName("a cloud-metadata target is refused whatever the flag — and the allow-list does not admit it")
        void metadataRefusedAlways(boolean ssrfProtection) {
            var refused = assertThrows(WorkflowConfigurationException.class,
                    () -> deploy(ssrfProtection, "169.254.169.254", "http://169.254.169.254", "/latest/meta-data/"));
            assertTrue(refused.getMessage().contains("never allowed"), refused.getMessage());
            var inPath = assertThrows(WorkflowConfigurationException.class,
                    () -> deploy(ssrfProtection, null, "https://93.184.216.34", "http://[fd00:ec2::254]/latest"));
            assertTrue(inPath.getMessage().contains("never allowed"), inPath.getMessage());
        }

        @Test
        @DisplayName("templated hosts keep their own handling: refused with protection off, judged per call with it on")
        void templatedHostUnchanged() {
            var refused = assertThrows(WorkflowConfigurationException.class, () -> deploy(false, null, "https://{properties.apiHost}"));
            assertTrue(refused.getMessage().contains("is a template"), refused.getMessage());
            assertDoesNotThrow(() -> deploy(true, null, "https://{properties.apiHost}", "http://{properties.other}/x"));
        }

        @Test
        @DisplayName("a ${vars:...} host is resolved per call, so it is not judged at deploy")
        void varsHostSkipped() {
            assertDoesNotThrow(() -> deploy(true, null, "${vars:apiBase}", "https://${vars:apiHost}/x"));
        }
    }

    @Nested
    @DisplayName("the shared decision (resolver injected)")
    class Decision {

        private final UrlValidationUtils.HostResolver resolver = host -> switch (host) {
            case "billing.corp" -> new InetAddress[]{InetAddress.getByAddress(host, new byte[]{10, 1, 2, 3})};
            case "api.example.com" -> new InetAddress[]{InetAddress.getByAddress(host, new byte[]{93, (byte) 184, (byte) 216, 34})};
            case "creds.example.com" -> new InetAddress[]{InetAddress.getByAddress(host, new byte[]{(byte) 169, (byte) 254, (byte) 169, (byte) 254})};
            default -> new InetAddress[]{literal(host)};
        };

        /**
         * An IP literal parses without DNS, as with the JDK resolver; any other name
         * does not resolve.
         */
        private static InetAddress literal(String host) throws UnknownHostException {
            String bare = host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
            try {
                return InetAddress.ofLiteral(bare);
            } catch (IllegalArgumentException e) {
                throw new UnknownHostException(host);
            }
        }

        @Test
        @DisplayName("protection on: a name that resolves to a private address is refused at deploy")
        void privateNameRefused() {
            var refused = assertThrows(IllegalArgumentException.class,
                    () -> executor(true, null).rejectRefusedLiteralTarget("https://billing.corp/v1", resolver));
            assertTrue(refused.getMessage().contains("'billing.corp'"), refused.getMessage());
            assertDoesNotThrow(() -> executor(true, "billing.corp").rejectRefusedLiteralTarget("https://billing.corp/v1", resolver));
            assertDoesNotThrow(() -> executor(true, null).rejectRefusedLiteralTarget("https://api.example.com/v1", resolver));
        }

        @Test
        @DisplayName("a name that resolves to the metadata service is refused at deploy, protection off included")
        void metadataNameRefused() {
            assertThrows(IllegalArgumentException.class,
                    () -> executor(false, null).rejectRefusedLiteralTarget("http://creds.example.com", resolver));
        }

        @Test
        @DisplayName("an unresolvable host is left to the execution check, which still refuses it")
        void unresolvableDeferred() {
            var executor = executor(true, null);
            assertDoesNotThrow(() -> executor.rejectRefusedLiteralTarget("https://not-yet-in-dns.example/v1", resolver));
            var atExecution = assertThrows(IllegalArgumentException.class,
                    () -> executor.checkOutboundTarget(URI.create("https://not-yet-in-dns.example/v1"), resolver));
            assertTrue(atExecution.getMessage().contains("Cannot resolve hostname"), atExecution.getMessage());
        }

        @Test
        @DisplayName("an unresolvable host on a blocked suffix is still refused: the name alone decides")
        void blockedSuffixRefusedWithoutLookup() {
            assertThrows(IllegalArgumentException.class,
                    () -> executor(true, null).rejectRefusedLiteralTarget("http://billing.internal:8080", resolver));
        }

        @Test
        @DisplayName("deploy refuses exactly what execution refuses, for every literal target")
        void deployAgreesWithExecution() {
            List<String> targets = List.of("http://127.0.0.1:7070", "http://10.0.0.5:9200", "http://localhost", "http://[::1]:7070",
                    "https://billing.corp", "https://api.example.com", "https://93.184.216.34", "http://169.254.169.254",
                    "http://creds.example.com", "http://billing.internal");
            for (boolean protection : new boolean[]{true, false}) {
                for (String allowed : new String[]{null, "127.0.0.1:7070,billing.corp,169.254.169.254"}) {
                    var executor = executor(protection, allowed);
                    for (String target : targets) {
                        boolean refusedAtExecution = refuses(() -> executor.checkOutboundTarget(URI.create(target), resolver));
                        boolean refusedAtDeploy = refuses(() -> executor.rejectRefusedLiteralTarget(target, resolver));
                        assertEquals(refusedAtExecution, refusedAtDeploy,
                                target + " (protection " + protection + ", allowed-hosts " + allowed + ")");
                    }
                }
            }
        }

        @Test
        @DisplayName("targets without a literal http(s) authority are not judged at deploy")
        void nonLiteralSkipped() {
            var executor = executor(true, null);
            assertDoesNotThrow(() -> executor.rejectRefusedLiteralTarget(null, resolver));
            assertDoesNotThrow(() -> executor.rejectRefusedLiteralTarget("/relative/path", resolver));
            assertDoesNotThrow(() -> executor.rejectRefusedLiteralTarget("http://{properties.host}:7070", resolver));
            assertDoesNotThrow(() -> executor.rejectRefusedLiteralTarget("http://127.0.0.1:{properties.port}", resolver));
        }

        private static boolean refuses(Runnable check) {
            try {
                check.run();
                return false;
            } catch (IllegalArgumentException e) {
                return true;
            }
        }
    }
}
