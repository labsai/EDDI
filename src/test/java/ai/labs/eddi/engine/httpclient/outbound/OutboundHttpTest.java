/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.httpclient.outbound;

import ai.labs.eddi.engine.httpclient.outbound.OutboundEngine.Hop;
import ai.labs.eddi.engine.httpclient.outbound.OutboundEngine.Received;
import ai.labs.eddi.modules.llm.tools.UrlValidationUtils;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.Vertx;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OutboundHttp}'s policies against a scripted engine: every hop the
 * facade sends is recorded, every response is canned, so redirect, header, SSRF
 * and body rules are pinned without a socket. The real JDK engine is exercised
 * in {@code JdkOutboundEngineTest} and, end to end, in
 * {@code OutboundHttpServerTest}.
 */
class OutboundHttpTest {

    private static final URI ORIGIN = URI.create("https://api.example.com/start?key=s3cret");

    /** Replays canned responses and records the hops it was asked to send. */
    static final class ScriptedEngine implements OutboundEngine {
        final List<Hop> hops = new ArrayList<>();
        private final Deque<Received> responses = new ArrayDeque<>();

        ScriptedEngine respond(int status, Map<String, List<String>> headers, InputStream body) {
            responses.add(new Received(status, null, OutboundRequest.caseInsensitiveCopy(headers), body));
            return this;
        }

        ScriptedEngine respond(int status, Map<String, List<String>> headers, String body) {
            return respond(status, headers, new TrackingStream(body.getBytes(StandardCharsets.UTF_8)));
        }

        ScriptedEngine redirect(int status, String location) {
            return respond(status, Map.of("Location", List.of(location)), "moved");
        }

        @Override
        public Received send(Hop hop) {
            hops.add(hop);
            if (responses.isEmpty()) {
                throw new AssertionError("unexpected hop to " + hop.uri());
            }
            return responses.poll();
        }
    }

    /** A body that remembers how much of it was read and whether it was closed. */
    static final class TrackingStream extends ByteArrayInputStream {
        int bytesRead;
        boolean closed;

        TrackingStream(byte[] bytes) {
            super(bytes);
        }

        @Override
        public synchronized int read(byte[] b, int off, int len) {
            int read = super.read(b, off, len);
            if (read > 0) {
                bytesRead += read;
            }
            return read;
        }

        @Override
        public void close() throws IOException {
            closed = true;
            super.close();
        }
    }

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private OutboundHttp facade(ScriptedEngine engine) {
        return new OutboundHttp(OutboundSettings.defaults(Duration.ofSeconds(10)), engine, false, meters);
    }

    private static OutboundRequest.Builder get(URI uri) {
        return OutboundRequest.get(Profile.DEFAULT, uri).ssrf(SsrfPolicy.NONE);
    }

    private static BodyMode<String> text() {
        return BodyMode.string(1024, BodyMode.OnOverflow.FAIL);
    }

    @Nested
    @DisplayName("time")
    class Time {

        @Test
        @DisplayName("a request with no timeout reaches the engine with the 15s default — never unbounded")
        void defaultHopTimeout() throws Exception {
            ScriptedEngine engine = new ScriptedEngine().respond(200, Map.of(), "ok");
            facade(engine).send(get(ORIGIN).build(), text());

            assertEquals(Duration.ofSeconds(15), engine.hops.getFirst().hopTimeout());
        }

        @Test
        @DisplayName("a caller's own timeout is never overridden")
        void callerTimeoutWins() throws Exception {
            ScriptedEngine engine = new ScriptedEngine().respond(200, Map.of(), "ok");
            facade(engine).send(get(ORIGIN).timeout(Duration.ofSeconds(3)).build(), text());

            assertEquals(Duration.ofSeconds(3), engine.hops.getFirst().hopTimeout());
        }

        @Test
        @DisplayName("the budget is three connect timeouts, or the hop timeout when longer")
        void totalBudget() {
            OutboundHttp facade = facade(new ScriptedEngine());

            assertEquals(Duration.ofSeconds(30), facade.totalBudget(Profile.DEFAULT, Duration.ofSeconds(15)));
            assertEquals(Duration.ofSeconds(30), facade.totalBudget(Profile.DEFAULT, Duration.ofSeconds(5)));
            // An A2A peer configured for 120s must not be cut short by the default.
            assertEquals(Duration.ofSeconds(120), facade.totalBudget(Profile.DEFAULT, Duration.ofSeconds(120)));
        }

        @Test
        @DisplayName("every hop of a redirect chain shares one deadline")
        void oneDeadlineForTheChain() throws Exception {
            ScriptedEngine engine = new ScriptedEngine()
                    .redirect(302, "https://api.example.com/next")
                    .respond(200, Map.of(), "ok");
            facade(engine).send(get(ORIGIN).build(), text());

            assertEquals(engine.hops.get(0).deadlineNanos(), engine.hops.get(1).deadlineNanos());
        }
    }

    @Nested
    @DisplayName("redirects")
    class Redirects {

        @Test
        @DisplayName("a followed redirect reports the final URI and the chain")
        void finalUriAndChain() throws Exception {
            ScriptedEngine engine = new ScriptedEngine()
                    .redirect(301, "/moved")
                    .respond(200, Map.of(), "ok");
            OutboundResponse<String> response = facade(engine).send(get(ORIGIN).build(), text());

            assertEquals("ok", response.body());
            assertEquals(URI.create("https://api.example.com/moved"), response.finalUri());
            assertEquals(List.of(ORIGIN), response.redirectChain());
        }

        @Test
        @DisplayName("JDK_SAFE: PUT across a 302 keeps its method and body")
        void putSurvives302() throws Exception {
            ScriptedEngine engine = new ScriptedEngine()
                    .redirect(302, "https://api.example.com/other")
                    .respond(204, Map.of(), "");
            facade(engine).send(OutboundRequest.builder(Profile.DEFAULT, ORIGIN).ssrf(SsrfPolicy.NONE)
                    .method("PUT", RequestBody.ofString("data")).build(), BodyMode.discard());

            Hop second = engine.hops.get(1);
            assertEquals("PUT", second.method());
            assertArrayEquals("data".getBytes(StandardCharsets.UTF_8), second.body());
        }

        @Test
        @DisplayName("VERTX_COMPAT: POST + 302 returns the 3xx instead of following it")
        void vertxCompatPostIsNotFollowed() throws Exception {
            ScriptedEngine engine = new ScriptedEngine().redirect(302, "https://api.example.com/other");
            OutboundResponse<String> response = facade(engine).send(OutboundRequest.builder(Profile.APICALLS, ORIGIN).ssrf(SsrfPolicy.NONE)
                    .method("POST", RequestBody.ofString("{}")).build(), text());

            assertEquals(302, response.status());
            assertEquals("moved", response.body(), "the 3xx is the response, body and all");
            assertEquals(1, engine.hops.size());
        }

        @Test
        @DisplayName("VERTX_COMPAT: GET + 302 is followed")
        void vertxCompatGetIsFollowed() throws Exception {
            ScriptedEngine engine = new ScriptedEngine()
                    .redirect(302, "https://api.example.com/other")
                    .respond(200, Map.of(), "ok");
            OutboundResponse<String> response = facade(engine).send(
                    OutboundRequest.get(Profile.APICALLS, ORIGIN).ssrf(SsrfPolicy.NONE).build(), text());

            assertEquals(200, response.status());
            assertEquals(2, engine.hops.size());
        }

        @Test
        @DisplayName("VERTX_COMPAT: POST + 303 becomes a GET without body or Content-Type")
        void vertxCompatSeeOther() throws Exception {
            ScriptedEngine engine = new ScriptedEngine()
                    .redirect(303, "https://api.example.com/result")
                    .respond(200, Map.of(), "ok");
            facade(engine).send(OutboundRequest.builder(Profile.APICALLS, ORIGIN).ssrf(SsrfPolicy.NONE)
                    .method("POST", RequestBody.ofString("{}"))
                    .header("Content-Type", "application/json").build(), text());

            Hop second = engine.hops.get(1);
            assertEquals("GET", second.method());
            assertEquals(0, second.body().length);
            assertFalse(second.headers().containsKey("content-type"));
        }

        @Test
        @DisplayName("APICALLS follows no redirect at all once SSRF protection is on")
        void apicallsStrictNeverFollows() throws Exception {
            ScriptedEngine engine = new ScriptedEngine().redirect(302, "https://api.example.com/other");
            OutboundHttp strict = new OutboundHttp(OutboundSettings.defaults(Duration.ofSeconds(10)), engine, true, meters);
            OutboundResponse<String> response = strict.send(
                    OutboundRequest.get(Profile.APICALLS, URI.create("https://93.184.215.14/x")).build(), text());

            assertEquals(302, response.status());
            assertEquals(1, engine.hops.size());
        }

        @Test
        @DisplayName("more hops than allowed fail with an IOException, counted as blocked")
        void tooManyRedirects() {
            ScriptedEngine engine = new ScriptedEngine();
            for (int i = 0; i < 3; i++) {
                engine.redirect(302, "https://api.example.com/loop" + i);
            }
            IOException e = assertThrows(IOException.class, () -> facade(engine).send(
                    get(ORIGIN).redirects(RedirectPolicy.follow(2, MethodRule.JDK_SAFE)).build(), text()));

            assertTrue(e.getMessage().startsWith("Too many redirects"), e.getMessage());
            assertEquals(1.0, meters.get("eddi.http.outbound.blocked").tag("reason", "redirect").counter().count());
        }

        @Test
        @DisplayName("a 3xx without Location fails")
        void redirectWithoutLocation() {
            ScriptedEngine engine = new ScriptedEngine().respond(302, Map.of(), "no location");
            assertThrows(IOException.class, () -> facade(engine).send(get(ORIGIN).build(), text()));
        }

        @Test
        @DisplayName("an unparsable Location is a broken redirect (IOException), not an SSRF refusal")
        void unparsableLocation() {
            ScriptedEngine engine = new ScriptedEngine().redirect(302, "https://api.example.com/a b|{c}?token=leaked");
            IOException e = assertThrows(IOException.class, () -> facade(engine).send(get(ORIGIN).build(), text()));

            assertTrue(e.getMessage().contains("unparsable Location"), e.getMessage());
            // Nowhere in the chain: the origin's query, or the raw header the server chose.
            for (Throwable t = e; t != null; t = t.getCause()) {
                assertFalse(String.valueOf(t.getMessage()).contains("s3cret"), "the origin's query leaked: " + t);
                assertFalse(String.valueOf(t.getMessage()).contains("leaked"), "the raw Location leaked: " + t);
            }
            assertEquals(1, engine.hops.size());
        }

        @Test
        @DisplayName("NEVER hands the 3xx back with its body")
        void neverFollows() throws Exception {
            ScriptedEngine engine = new ScriptedEngine().redirect(307, "https://elsewhere.example/");
            OutboundResponse<String> response = facade(engine).send(get(ORIGIN).redirects(RedirectPolicy.NEVER).build(), text());

            assertEquals(307, response.status());
            assertEquals("moved", response.body());
        }
    }

    @Nested
    @DisplayName("redirect bodies")
    class RedirectBodies {

        @Test
        @DisplayName("a small redirect body is drained and closed, never handed to the caller")
        void smallBodyDrained() throws Exception {
            TrackingStream redirectBody = new TrackingStream("courtesy page".getBytes(StandardCharsets.UTF_8));
            ScriptedEngine engine = new ScriptedEngine()
                    .respond(302, Map.of("Location", List.of("/next")), redirectBody)
                    .respond(200, Map.of(), "ok");
            OutboundResponse<String> response = facade(engine).send(get(ORIGIN).build(), text());

            assertEquals("ok", response.body());
            assertTrue(redirectBody.closed);
        }

        @Test
        @DisplayName("a declared Content-Length over 64 KiB is closed without reading a byte")
        void oversizedDeclaredLengthNotRead() throws Exception {
            TrackingStream redirectBody = new TrackingStream(new byte[100_000]);
            ScriptedEngine engine = new ScriptedEngine()
                    .respond(302, Map.of("Location", List.of("/next"), "Content-Length", List.of("100000")), redirectBody)
                    .respond(200, Map.of(), "ok");
            facade(engine).send(get(ORIGIN).build(), text());

            assertEquals(0, redirectBody.bytesRead);
            assertTrue(redirectBody.closed);
        }

        @Test
        @DisplayName("an undeclared oversized redirect body is cut off just past 64 KiB")
        void undeclaredOversizedCutOff() throws Exception {
            TrackingStream redirectBody = new TrackingStream(new byte[1_000_000]);
            ScriptedEngine engine = new ScriptedEngine()
                    .respond(302, Map.of("Location", List.of("/next")), redirectBody)
                    .respond(200, Map.of(), "ok");
            facade(engine).send(get(ORIGIN).build(), text());

            assertTrue(redirectBody.bytesRead <= OutboundHttp.MAX_REDIRECT_BODY_BYTES + 8192, "read " + redirectBody.bytesRead);
            assertTrue(redirectBody.closed);
        }
    }

    @Nested
    @DisplayName("credentials on redirects")
    class Credentials {

        private Hop secondHopTo(String location, OutboundRequest.Builder request) throws Exception {
            ScriptedEngine engine = new ScriptedEngine().redirect(302, location).respond(200, Map.of(), "ok");
            facade(engine).send(request.build(), text());
            return engine.hops.get(1);
        }

        @Test
        @DisplayName("a caller-derived custom header is stripped cross-origin")
        void callerDerivedStrippedCrossOrigin() throws Exception {
            Hop hop = secondHopTo("https://evil.example/", get(ORIGIN)
                    .callerDerivedHeader("X-Upstream-Token", "user-token")
                    .header("X-Trace", "keep"));

            assertFalse(hop.headers().containsKey("X-Upstream-Token"), "a ${caller:token} must never reach another origin");
            assertEquals(List.of("keep"), hop.headers().get("x-trace"));
        }

        @Test
        @DisplayName("a caller-derived header survives a same-origin redirect")
        void callerDerivedKeptSameOrigin() throws Exception {
            Hop hop = secondHopTo("/elsewhere", get(ORIGIN).callerDerivedHeader("X-Upstream-Token", "user-token"));

            assertEquals(List.of("user-token"), hop.headers().get("x-upstream-token"));
        }

        @Test
        @DisplayName("the fixed sensitive set is stripped cross-origin, whatever its case")
        void sensitiveStrippedCrossOrigin() throws Exception {
            Hop hop = secondHopTo("http://api.example.com/downgraded", get(ORIGIN)
                    .header("AUTHORIZATION", "Bearer x")
                    .header("X-Api-Key", "k"));

            assertFalse(hop.headers().containsKey("authorization"), "a scheme change is a different origin");
            assertFalse(hop.headers().containsKey("x-api-key"));
            assertEquals(List.of("EDDI-Agent/1.0"), hop.headers().get("user-agent"));
        }
    }

    @Nested
    @DisplayName("SSRF")
    class Ssrf {

        @Test
        @DisplayName("STRICT refuses a loopback initial URL before anything is sent")
        void strictRefusesLoopback() {
            ScriptedEngine engine = new ScriptedEngine();
            OutboundBlockedException e = assertThrows(OutboundBlockedException.class, () -> facade(engine).send(
                    OutboundRequest.get(Profile.DEFAULT, URI.create("http://127.0.0.1:8080/")).build(), text()));

            assertInstanceOf(IllegalArgumentException.class, e, "existing callers catch IllegalArgumentException");
            assertTrue(engine.hops.isEmpty());
            assertEquals(1.0, meters.get("eddi.http.outbound.blocked").tag("reason", "ssrf").counter().count());
        }

        @Test
        @DisplayName("METADATA_ONLY refuses the cloud metadata service")
        void metadataOnlyRefusesMetadata() {
            ScriptedEngine engine = new ScriptedEngine();
            assertThrows(OutboundBlockedException.class, () -> facade(engine).send(OutboundRequest
                    .get(Profile.DEFAULT, URI.create("http://169.254.169.254/latest/meta-data/")).ssrf(SsrfPolicy.METADATA_ONLY)
                    .build(), text()));
            assertTrue(engine.hops.isEmpty());
        }

        @Test
        @DisplayName("HOPS_ONLY trusts the initial URL but refuses a redirect to loopback")
        void hopsOnlyRefusesRedirectToLoopback() {
            ScriptedEngine engine = new ScriptedEngine().redirect(302, "http://127.0.0.1/admin");
            assertThrows(OutboundBlockedException.class, () -> facade(engine).send(
                    OutboundRequest.get(Profile.DEFAULT, URI.create("http://10.0.0.5/config")).ssrf(SsrfPolicy.HOPS_ONLY).build(),
                    text()));
            assertEquals(1, engine.hops.size(), "the refused hop is never sent");
        }

        @Test
        @DisplayName("a hop validator replaces the policy's check of redirect hops")
        void hopValidatorOverride() {
            ScriptedEngine engine = new ScriptedEngine().redirect(302, "https://other.example/");
            OutboundBlockedException e = assertThrows(OutboundBlockedException.class, () -> facade(engine).send(get(ORIGIN)
                    .hopValidator(url -> {
                        throw new IllegalArgumentException("not on the allowlist: " + url);
                    }).build(), text()));

            assertEquals("not on the allowlist: https://other.example/", e.getMessage());
            assertEquals(1, engine.hops.size());
        }
    }

    @Nested
    @DisplayName("DNS rebinding — the hop's check runs again after the exchange")
    class Rebinding {

        private static final URI REBINDING = URI.create("https://rebind.example/data");

        /**
         * Answers {@code first} to the first lookup and {@code later} to every one
         * after it.
         */
        private UrlValidationUtils.HostResolver rebinding(String first, String later, int[] lookups) {
            return host -> {
                lookups[0]++;
                return new InetAddress[]{InetAddress.getByName(lookups[0] == 1 ? first : later)};
            };
        }

        private OutboundHttp facade(ScriptedEngine engine, UrlValidationUtils.HostResolver resolver) {
            return new OutboundHttp(OutboundSettings.defaults(Duration.ofSeconds(10)), engine, false, meters, resolver);
        }

        @Test
        @DisplayName("STRICT: a name that passes as public and connects to loopback gets no response back")
        void strictDiscardsResponseOfRebindingTarget() {
            TrackingStream body = new TrackingStream("internal secrets".getBytes(StandardCharsets.UTF_8));
            ScriptedEngine engine = new ScriptedEngine().respond(200, Map.of(), body);
            int[] lookups = {0};

            OutboundBlockedException e = assertThrows(OutboundBlockedException.class, () -> facade(engine, rebinding("93.184.216.34",
                    "127.0.0.1", lookups)).send(OutboundRequest.get(Profile.DEFAULT, REBINDING).ssrf(SsrfPolicy.STRICT).build(), text()));

            assertTrue(e.getMessage().startsWith("Response discarded"), e.getMessage());
            assertEquals(1, engine.hops.size(), "the pre-check passed, so the hop was sent");
            assertEquals(2, lookups[0], "checked before the hop and again after it");
            assertTrue(body.closed, "the discarded body releases its connection");
            assertEquals(0, body.bytesRead, "not a byte of the internal response is read");
            assertEquals(1.0, meters.get("eddi.http.outbound.blocked").tag("reason", "ssrf").counter().count());
        }

        @Test
        @DisplayName("METADATA_ONLY: a name that rebinds onto the metadata service is refused after the exchange too")
        void metadataOnlyDiscardsResponseOfRebindingTarget() {
            TrackingStream body = new TrackingStream("{\"AccessKeyId\":\"x\"}".getBytes(StandardCharsets.UTF_8));
            ScriptedEngine engine = new ScriptedEngine().respond(200, Map.of(), body);

            assertThrows(OutboundBlockedException.class, () -> facade(engine, rebinding("93.184.216.34", "169.254.169.254", new int[]{0}))
                    .send(OutboundRequest.get(Profile.DEFAULT, REBINDING).ssrf(SsrfPolicy.METADATA_ONLY).build(), text()));
            assertTrue(body.closed);
        }

        @Test
        @DisplayName("a redirect from a target that rebound is not followed")
        void redirectFromRebindingTargetNotFollowed() {
            ScriptedEngine engine = new ScriptedEngine().redirect(302, "https://elsewhere.example/");

            assertThrows(OutboundBlockedException.class, () -> facade(engine, rebinding("93.184.216.34", "10.0.0.5", new int[]{0}))
                    .send(OutboundRequest.get(Profile.DEFAULT, REBINDING).ssrf(SsrfPolicy.STRICT).build(), text()));
            assertEquals(1, engine.hops.size());
        }

        @Test
        @DisplayName("a stable public answer passes both checks")
        void stableAnswerPasses() throws Exception {
            ScriptedEngine engine = new ScriptedEngine().respond(200, Map.of(), "ok");
            int[] lookups = {0};

            OutboundResponse<String> response = facade(engine, rebinding("93.184.216.34", "93.184.216.34", lookups))
                    .send(OutboundRequest.get(Profile.DEFAULT, REBINDING).ssrf(SsrfPolicy.STRICT).build(), text());

            assertEquals("ok", response.body());
            assertEquals(2, lookups[0]);
        }

        @Test
        @DisplayName("an unchecked hop (NONE, HOPS_ONLY's initial URL) is not looked up afterwards either")
        void uncheckedHopNotRechecked() throws Exception {
            ScriptedEngine engine = new ScriptedEngine().respond(200, Map.of(), "ok");
            int[] lookups = {0};

            facade(engine, rebinding("10.0.0.5", "10.0.0.5", lookups))
                    .send(OutboundRequest.get(Profile.DEFAULT, REBINDING).ssrf(SsrfPolicy.HOPS_ONLY).build(), text());

            assertEquals(0, lookups[0]);
        }

        @Test
        @DisplayName("the startup warning fires only when the JVM's positive DNS cache is off")
        void dnsCacheDisabledDetection() {
            assertFalse(OutboundHttp.positiveDnsCacheDisabled(null, null), "unset is the 30s default");
            assertFalse(OutboundHttp.positiveDnsCacheDisabled("30", null));
            assertFalse(OutboundHttp.positiveDnsCacheDisabled("-1", null), "negative caches forever");
            assertTrue(OutboundHttp.positiveDnsCacheDisabled("0", null));
            assertTrue(OutboundHttp.positiveDnsCacheDisabled(null, "0"), "the system property applies when the security one is unset");
            assertFalse(OutboundHttp.positiveDnsCacheDisabled("30", "0"), "the security property wins");
            assertFalse(OutboundHttp.positiveDnsCacheDisabled("x", null));
        }
    }

    @Nested
    @DisplayName("body modes")
    class BodyModes {

        private <T> OutboundResponse<T> fetch(String body, Map<String, List<String>> headers, BodyMode<T> mode) throws Exception {
            return facade(new ScriptedEngine().respond(200, headers, body)).send(get(ORIGIN).build(), mode);
        }

        @Test
        @DisplayName("bytes: exactly the limit passes")
        void exactLimitPasses() throws Exception {
            OutboundResponse<byte[]> response = fetch("12345", Map.of(), BodyMode.bytes(5, BodyMode.OnOverflow.FAIL));
            assertEquals(5, response.body().length);
            assertFalse(response.truncated());
        }

        @Test
        @DisplayName("bytes: one byte over the limit fails with FAIL")
        void overLimitFails() {
            assertThrows(BodyTooLargeException.class, () -> fetch("123456", Map.of(), BodyMode.bytes(5, BodyMode.OnOverflow.FAIL)));
            assertEquals(1.0, meters.get("eddi.http.outbound.blocked").tag("reason", "cap").counter().count());
        }

        @Test
        @DisplayName("bytes: one byte over the limit is truncated with TRUNCATE")
        void overLimitTruncates() throws Exception {
            OutboundResponse<byte[]> response = fetch("123456", Map.of(), BodyMode.bytes(5, BodyMode.OnOverflow.TRUNCATE));
            assertEquals("12345", new String(response.body(), StandardCharsets.UTF_8));
            assertTrue(response.truncated());
        }

        @Test
        @DisplayName("a declared Content-Length over the cap fails before reading")
        void declaredLengthOverCap() {
            TrackingStream body = new TrackingStream(new byte[100]);
            ScriptedEngine engine = new ScriptedEngine().respond(200, Map.of("Content-Length", List.of("100")), body);
            assertThrows(BodyTooLargeException.class,
                    () -> facade(engine).send(get(ORIGIN).build(), BodyMode.bytes(10, BodyMode.OnOverflow.FAIL)));
            assertEquals(0, body.bytesRead);
            assertTrue(body.closed);
        }

        @Test
        @DisplayName("string: decoded with the Content-Type charset")
        void charsetHonoured() throws Exception {
            byte[] latin1 = "café".getBytes(StandardCharsets.ISO_8859_1);
            OutboundResponse<String> response = facade(new ScriptedEngine().respond(200,
                    Map.of("Content-Type", List.of("text/plain; charset=ISO-8859-1")), new TrackingStream(latin1)))
                    .send(get(ORIGIN).build(), text());
            assertEquals("café", response.body());
        }

        @Test
        @DisplayName("stream: reading past the cap throws mid-stream")
        void streamCap() throws Exception {
            OutboundResponse<InputStream> response = fetch("x".repeat(20_000), Map.of(), BodyMode.stream(10_000, null));
            try (InputStream in = response.body()) {
                assertThrows(BodyTooLargeException.class, in::readAllBytes);
            }
        }

        @Test
        @DisplayName("stream: a read budget closes a stalled stream")
        void streamBudget() throws Exception {
            InputStream stalled = new InputStream() {
                private volatile boolean closed;

                @Override
                public int read() throws IOException {
                    while (!closed) {
                        try {
                            Thread.sleep(10);
                        } catch (InterruptedException e) {
                            throw new IOException(e);
                        }
                    }
                    throw new IOException("closed");
                }

                @Override
                public void close() {
                    closed = true;
                }
            };
            OutboundResponse<InputStream> response = facade(new ScriptedEngine().respond(200, Map.of(), stalled))
                    .send(get(ORIGIN).build(), BodyMode.stream(0, Duration.ofMillis(100)));
            try (InputStream in = response.body()) {
                assertThrows(OutboundTimeoutException.class, in::read);
            }
        }

        @Test
        @DisplayName("file: over the cap fails and leaves no partial file")
        void fileOverCapDeleted(@TempDir Path dir) {
            Path target = dir.resolve("download.bin");
            assertThrows(BodyTooLargeException.class, () -> fetch("x".repeat(20_000), Map.of(), BodyMode.file(target, 10_000)));
            assertFalse(Files.exists(target));
        }

        @Test
        @DisplayName("file: within the cap is written whole")
        void fileWritten(@TempDir Path dir) throws Exception {
            Path target = dir.resolve("download.bin");
            OutboundResponse<Path> response = fetch("hello", Map.of(), BodyMode.file(target, 10));
            assertEquals("hello", Files.readString(response.body()));
        }

        @Test
        @DisplayName("discard: closes the body unread")
        void discard() throws Exception {
            TrackingStream body = new TrackingStream(new byte[10]);
            OutboundResponse<Void> response = facade(new ScriptedEngine().respond(200, Map.of(), body))
                    .send(get(ORIGIN).build(), BodyMode.discard());
            assertNull(response.body());
            assertTrue(body.closed);
            assertEquals(0, body.bytesRead);
        }
    }

    @Nested
    @DisplayName("request bodies")
    class RequestBodies {

        @Test
        @DisplayName("a form body implies its Content-Type unless the caller set one")
        void formContentType() throws Exception {
            ScriptedEngine engine = new ScriptedEngine().respond(200, Map.of(), "ok");
            facade(engine).send(OutboundRequest.builder(Profile.DEFAULT, ORIGIN).ssrf(SsrfPolicy.NONE)
                    .method("POST", new RequestBody.FormBody(List.of(Map.entry("grant_type", "client_credentials"), Map.entry("scope", "a b"))))
                    .build(), text());

            Hop hop = engine.hops.getFirst();
            assertEquals(List.of("application/x-www-form-urlencoded"), hop.headers().get("content-type"));
            assertEquals("grant_type=client_credentials&scope=a+b", new String(hop.body(), StandardCharsets.UTF_8));
        }

        @Test
        @DisplayName("a multipart body carries its boundary and escapes quotes in names")
        void multipart() {
            RequestBody.MultipartBody body = new RequestBody.MultipartBody(List.of(
                    RequestBody.MultipartBody.Part.field("model", "whisper"),
                    RequestBody.MultipartBody.Part.file("file", "a\"b.wav", "audio/wav", new byte[]{1, 2})), "BOUNDARY");
            String wire = new String(body.toBytes(), StandardCharsets.ISO_8859_1);

            assertEquals("multipart/form-data; boundary=BOUNDARY", body.contentType());
            assertTrue(wire.startsWith("--BOUNDARY\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\nwhisper\r\n"), wire);
            assertTrue(wire.contains("filename=\"a%22b.wav\"\r\nContent-Type: audio/wav\r\n\r\n"), wire);
            assertTrue(wire.endsWith("--BOUNDARY--\r\n"), wire);
        }

        @Test
        @DisplayName("a part content type with a line break is refused — it would inject part headers")
        void multipartContentTypeInjection() {
            assertThrows(IllegalArgumentException.class, () -> RequestBody.MultipartBody.Part.file("file", "a.wav",
                    "audio/wav\r\n\r\n--BOUNDARY\r\nContent-Disposition: form-data; name=\"forged\"", new byte[]{1}));
            assertThrows(IllegalArgumentException.class,
                    () -> RequestBody.MultipartBody.Part.file("file", "a.wav", "audio/wav\nX-Injected: 1", new byte[]{1}));
        }
    }

    @Test
    @DisplayName("a call on a Vert.x event-loop thread is refused")
    void eventLoopGuard() throws Exception {
        Vertx vertx = Vertx.vertx();
        try {
            CompletableFuture<Throwable> failure = new CompletableFuture<>();
            OutboundHttp facade = facade(new ScriptedEngine());
            vertx.runOnContext(ignored -> {
                try {
                    facade.send(get(ORIGIN).build(), text());
                    failure.complete(null);
                } catch (Throwable t) {
                    failure.complete(t);
                }
            });
            assertInstanceOf(IllegalStateException.class, failure.get(10, TimeUnit.SECONDS));
        } finally {
            vertx.close();
        }
    }

    @Test
    @DisplayName("a completed exchange is counted by profile, status class and outcome")
    void requestMetrics() throws Exception {
        facade(new ScriptedEngine().respond(201, Map.of(), "ok")).send(get(ORIGIN).build(), text());

        assertEquals(1.0, meters.get("eddi.http.outbound.requests")
                .tags("profile", "default", "method", "GET", "status", "2xx", "outcome", "success").counter().count());
        assertEquals(1, meters.get("eddi.http.outbound.duration").tag("outcome", "success").timer().count());
        assertEquals(2.0, meters.get("eddi.http.outbound.bytes").counter().count());
    }
}
