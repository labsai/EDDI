/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.a2a;

import ai.labs.eddi.engine.a2a.A2AModels.FilePart;
import ai.labs.eddi.engine.a2a.A2AModels.InboundFileMode;
import ai.labs.eddi.engine.a2a.A2AModels.InvalidA2ARequestException;
import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.httpclient.outbound.OutboundHttp;
import ai.labs.eddi.engine.httpclient.outbound.ScriptedEngine;
import ai.labs.eddi.engine.httpclient.outbound.TestOutbound;
import ai.labs.eddi.engine.model.Context;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.enterprise.inject.Instance;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static ai.labs.eddi.engine.a2a.A2AModels.ERROR_CONTENT_TYPE_NOT_SUPPORTED;
import static ai.labs.eddi.engine.a2a.A2AModels.ERROR_INVALID_PARAMS;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link A2AInboundFiles}: inbound file parts stored through the
 * attachment store, owned by the task's conversation; URIs fetched only through
 * the real {@link OutboundHttp} facade under the strict SSRF policy (a scripted
 * engine stands in for the network, so the facade's own redirect and SSRF
 * handling run); refusals that never leak downstream detail.
 */
class A2AInboundFilesTest {

    private static final String CONVERSATION = "6a1f0c2b9d3e4f5a6b7c8d9e";
    private static final String OTHER_CONVERSATION = "7b2e1d3c0a4f5e6b7c8d9e0f";
    private static final String PEER = "peer-a";
    private static final long MAX_SIZE = 1024;

    /** A minimal PDF signature: enough for the MIME check by signature. */
    private static final byte[] PDF = "%PDF-1.7\n1 0 obj\n<<>>\nendobj\n%%EOF\n".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] PNG = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};

    private FakeAttachmentStore store;
    private ScriptedEngine engine;
    private SimpleMeterRegistry meters;

    @BeforeEach
    void setUp() {
        store = new FakeAttachmentStore(MAX_SIZE);
        engine = new ScriptedEngine();
        meters = new SimpleMeterRegistry();
    }

    private A2AInboundFiles files(InboundFileMode mode) {
        return files(mode, 5);
    }

    private A2AInboundFiles files(InboundFileMode mode, int maxPerMessage) {
        @SuppressWarnings("unchecked")
        Instance<IAttachmentStore> instance = mock(Instance.class);
        when(instance.isResolvable()).thenReturn(true);
        when(instance.get()).thenReturn(store);
        OutboundHttp http = TestOutbound.facade(engine);
        return new A2AInboundFiles(mode, maxPerMessage, MAX_SIZE, instance, http, meters);
    }

    private static FilePart inline(String name, String mimeType, byte[] bytes) {
        return new FilePart(name, mimeType, Base64.getEncoder().encodeToString(bytes), null);
    }

    private static FilePart byUri(String uri) {
        return new FilePart("report.pdf", "application/pdf", null, uri);
    }

    private static String storageRefOf(Context context) {
        return (String) ((Map<?, ?>) context.getValue()).get("storageRef");
    }

    private static InvalidA2ARequestException refused(Runnable call) {
        return assertThrows(InvalidA2ARequestException.class, call::run);
    }

    @Nested
    @DisplayName("the property")
    class Property {

        @Test
        @DisplayName("is off by default and reads all three values in any case")
        void readsTheModes() {
            assertEquals(InboundFileMode.OFF, InboundFileMode.fromProperty(null));
            assertEquals(InboundFileMode.OFF, InboundFileMode.fromProperty("off"));
            assertEquals(InboundFileMode.INLINE, InboundFileMode.fromProperty("Inline"));
            assertEquals(InboundFileMode.INLINE_AND_URI, InboundFileMode.fromProperty("inline-and-uri"));
            assertThrows(IllegalArgumentException.class, () -> InboundFileMode.fromProperty("uri"));
        }

        @Test
        @DisplayName("the per-message cap never exceeds eddi.attachments.max-per-turn")
        void capIsBoundedByTheTurnCap() {
            assertEquals(3, A2AInboundFiles.effectiveCap(5, 3));
            assertEquals(5, A2AInboundFiles.effectiveCap(5, 0), "a non-positive turn cap means unlimited");
            assertEquals(2, A2AInboundFiles.effectiveCap(2, 5));
        }

        @Test
        @DisplayName("a handler built without the bean accepts no files")
        void disabledAcceptsNothing() {
            assertFalse(A2AInboundFiles.disabled().acceptsFiles());
            InvalidA2ARequestException e = refused(() -> A2AInboundFiles.disabled().check(List.of(inline("a.pdf", "application/pdf", PDF))));
            assertEquals(ERROR_CONTENT_TYPE_NOT_SUPPORTED, e.getCode());
        }
    }

    @Nested
    @DisplayName("inline bytes")
    class Inline {

        @Test
        @DisplayName("are stored into the task's conversation, counted against the peer, and named as attachment_N")
        void storedIntoTheConversation() {
            Map<String, Context> contexts = files(InboundFileMode.INLINE).store(
                    List.of(inline("report.pdf", "application/pdf", PDF), inline(null, null, PNG)), CONVERSATION, PEER);

            assertEquals(List.of("attachment_0", "attachment_1"), List.copyOf(contexts.keySet()));
            FakeAttachmentStore.Blob pdf = store.blobs.get(storageRefOf(contexts.get("attachment_0")));
            assertEquals(CONVERSATION, pdf.conversationId(), "owned by the task's conversation");
            assertEquals(PEER, pdf.userId(), "counted against the calling peer's quota");
            assertArrayEquals(PDF, pdf.bytes());
            assertEquals("report.pdf", ((Map<?, ?>) contexts.get("attachment_0").getValue()).get("fileName"));
            assertEquals("image/png", store.blobs.get(storageRefOf(contexts.get("attachment_1"))).mimeType(),
                    "no declared type: the signature decides");
            assertFalse(((Map<?, ?>) contexts.get("attachment_1").getValue()).containsKey("fileName"));
            assertEquals(2.0, meters.counter(A2AInboundFiles.METER_FILES, "direction", "inbound").count());
        }

        @Test
        @DisplayName("files stored here go with their conversation: deleteByConversation erases them")
        void erasedWithTheConversation() {
            files(InboundFileMode.INLINE).store(List.of(inline("report.pdf", "application/pdf", PDF)), CONVERSATION, PEER);

            assertEquals(1, store.deleteByConversation(CONVERSATION));
            assertTrue(store.blobs.isEmpty());
        }

        @Test
        @DisplayName("a declared type the content does not match is refused, with no store detail")
        void mimeMismatchRefused() {
            InvalidA2ARequestException e = refused(
                    () -> files(InboundFileMode.INLINE).store(List.of(inline("x.pdf", "application/pdf", PNG)), CONVERSATION, PEER));
            assertEquals(A2AInboundFiles.REFUSED_NOT_STORED, e.getMessage());
            assertTrue(store.blobs.isEmpty());
        }

        @Test
        @DisplayName("bytes that are not base64 are refused")
        void invalidBase64Refused() {
            InvalidA2ARequestException e = refused(() -> files(InboundFileMode.INLINE)
                    .store(List.of(new FilePart("x", null, "not base64!", null)), CONVERSATION, PEER));
            assertEquals(ERROR_INVALID_PARAMS, e.getCode());
        }

        @Test
        @DisplayName("URL-safe base64 is read as protobuf JSON allows")
        void urlSafeBase64Accepted() {
            byte[] bytes = {(byte) 0xFB, (byte) 0xFF, (byte) 0xFE, 'a', 'b', 'c'};
            Map<String, Context> contexts = files(InboundFileMode.INLINE).store(
                    List.of(new FilePart("x.bin", "application/octet-stream", Base64.getUrlEncoder().encodeToString(bytes), null)),
                    CONVERSATION, PEER);
            assertArrayEquals(bytes, store.blobs.get(storageRefOf(contexts.get("attachment_0"))).bytes());
        }

        @Test
        @DisplayName("line-wrapped (MIME-style) base64 is read; whitespace does not count against the size limit")
        void lineWrappedBase64Accepted() {
            String wrapped = Base64.getMimeEncoder(8, "\r\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(PDF);
            assertTrue(wrapped.contains("\r\n"));
            Map<String, Context> contexts = files(InboundFileMode.INLINE)
                    .store(List.of(new FilePart("a.pdf", "application/pdf", wrapped, null)), CONVERSATION, PEER);
            assertArrayEquals(PDF, store.blobs.get(storageRefOf(contexts.get("attachment_0"))).bytes());
            assertEquals(3, A2AInboundFiles.decodedLengthUpperBound("QU\r\nJD"));
        }

        @Test
        @DisplayName("load checks the type against the signature without storing anything")
        void loadChecksTypeWithoutStoring() {
            InvalidA2ARequestException e = refused(() -> files(InboundFileMode.INLINE).load(List.of(inline("x.png", "image/png", PDF))));
            assertEquals(A2AInboundFiles.REFUSED_NOT_STORED, e.getMessage());
            assertTrue(store.blobs.isEmpty());
            assertEquals(1, files(InboundFileMode.INLINE).load(List.of(inline("x.pdf", "application/pdf", PDF))).size());
            assertTrue(store.blobs.isEmpty(), "load alone stores nothing");
        }

        @Test
        @DisplayName("bytes over eddi.attachments.max-size-bytes are refused before a conversation exists, naming the limit")
        void oversizeRefusedByCheck() {
            InvalidA2ARequestException e = refused(
                    () -> files(InboundFileMode.INLINE).check(List.of(inline("big.pdf", "application/pdf", new byte[(int) MAX_SIZE + 10]))));
            assertEquals("A file part is larger than the limit of " + MAX_SIZE + " bytes", e.getMessage());
        }

        @Test
        @DisplayName("a quota refusal says so, without the store's detail")
        void quotaRefused() {
            store.maxPerUser(1);
            InvalidA2ARequestException e = refused(() -> files(InboundFileMode.INLINE).store(
                    List.of(inline("a.pdf", "application/pdf", PDF), inline("b.pdf", "application/pdf", PDF)), CONVERSATION, PEER));
            assertEquals(A2AInboundFiles.REFUSED_QUOTA, e.getMessage());
            assertTrue(store.blobs.isEmpty(), "the file stored before the refusal is deleted again");
        }

        @Test
        @DisplayName("a store failure never hands the store's message to the peer, and undoes the files already stored")
        void storeFailureUndoesAndHidesDetail() {
            store.failOnStore(2);
            InvalidA2ARequestException e = refused(() -> files(InboundFileMode.INLINE).store(
                    List.of(inline("a.pdf", "application/pdf", PDF), inline("b.pdf", "application/pdf", PDF)), CONVERSATION, PEER));
            assertFalse(e.getMessage().contains("mongodb"), e.getMessage());
            assertTrue(store.blobs.isEmpty());
            assertEquals(0.0, meters.counter(A2AInboundFiles.METER_FILES, "direction", "inbound").count(), "nothing was taken in");
        }
    }

    @Nested
    @DisplayName("the per-message cap and the mode")
    class Caps {

        @Test
        @DisplayName("more file parts than eddi.a2a.files.max-per-message are refused, never silently dropped")
        void countCap() {
            List<FilePart> three = List.of(inline("a", null, PDF), inline("b", null, PDF), inline("c", null, PDF));
            InvalidA2ARequestException e = refused(() -> files(InboundFileMode.INLINE, 2).check(three));
            assertEquals(ERROR_INVALID_PARAMS, e.getCode());
            assertTrue(e.getMessage().contains("at most 2"), e.getMessage());

            files(InboundFileMode.INLINE, 3).check(three);
        }

        @Test
        @DisplayName("under 'inline' a URI part is refused with ContentTypeNotSupported, and nothing is fetched")
        void uriRefusedUnderInline() {
            InvalidA2ARequestException e = refused(
                    () -> files(InboundFileMode.INLINE).store(List.of(byUri("http://93.184.216.34/report.pdf")), CONVERSATION, PEER));
            assertEquals(ERROR_CONTENT_TYPE_NOT_SUPPORTED, e.getCode());
            assertEquals(A2AInboundFiles.REFUSED_URI_NOT_ACCEPTED, e.getMessage());
            assertTrue(engine.hops.isEmpty());
        }

        @Test
        @DisplayName("without an attachment store no file is accepted")
        void noStore() {
            @SuppressWarnings("unchecked")
            Instance<IAttachmentStore> absent = mock(Instance.class);
            when(absent.isResolvable()).thenReturn(false);
            var withoutStore = new A2AInboundFiles(InboundFileMode.INLINE, 5, MAX_SIZE, absent, TestOutbound.facade(engine), meters);
            InvalidA2ARequestException e = refused(() -> withoutStore.check(List.of(inline("a.pdf", null, PDF))));
            assertEquals(A2AInboundFiles.REFUSED_UNAVAILABLE, e.getMessage());
        }
    }

    @Nested
    @DisplayName("URI parts — fetched under the strict SSRF policy")
    class Uri {

        @Test
        @DisplayName("a public URI is fetched once and stored, its Content-Type used when the peer declared none")
        void fetchedAndStored() {
            engine.respond(200, Map.of("Content-Type", List.of("application/pdf")), PDF);

            Map<String, Context> contexts = files(InboundFileMode.INLINE_AND_URI)
                    .store(List.of(new FilePart(null, null, null, "http://93.184.216.34/report.pdf")), CONVERSATION, PEER);

            assertEquals(1, engine.hops.size());
            FakeAttachmentStore.Blob blob = store.blobs.get(storageRefOf(contexts.get("attachment_0")));
            assertArrayEquals(PDF, blob.bytes());
            assertEquals("application/pdf", blob.mimeType());
            assertEquals(CONVERSATION, blob.conversationId());
        }

        @ParameterizedTest
        @ValueSource(strings = {"http://127.0.0.1/report.pdf", "http://10.0.0.5/report.pdf", "http://169.254.169.254/latest/meta-data/",
                "http://[::1]/report.pdf", "http://192.168.1.10/report.pdf"})
        @DisplayName("a private, loopback or metadata address is refused before any request is sent")
        void privateAddressRefused(String uri) {
            InvalidA2ARequestException e = refused(() -> files(InboundFileMode.INLINE_AND_URI).store(List.of(byUri(uri)), CONVERSATION, PEER));
            assertEquals(A2AInboundFiles.REFUSED_NOT_FETCHED, e.getMessage(), "the peer is not told why");
            assertTrue(engine.hops.isEmpty(), "no request reaches the private address");
            assertTrue(store.blobs.isEmpty());
        }

        @Test
        @DisplayName("a redirect to a private address is refused, and the refused hop is never sent")
        void redirectToPrivateRefused() {
            engine.respond(302, Map.of("Location", List.of("http://127.0.0.1:8080/admin")), new byte[0]);

            InvalidA2ARequestException e = refused(() -> files(InboundFileMode.INLINE_AND_URI)
                    .store(List.of(byUri("http://93.184.216.34/report.pdf")), CONVERSATION, PEER));

            assertEquals(A2AInboundFiles.REFUSED_NOT_FETCHED, e.getMessage());
            assertEquals(1, engine.hops.size(), "only the public hop went out");
            assertTrue(store.blobs.isEmpty());
        }

        @Test
        @DisplayName("a body over eddi.attachments.max-size-bytes is refused, naming the limit")
        void oversizeBodyRefused() {
            engine.respond(200, Map.of("Content-Type", List.of("application/pdf")), new byte[(int) MAX_SIZE + 1]);

            InvalidA2ARequestException e = refused(() -> files(InboundFileMode.INLINE_AND_URI)
                    .store(List.of(byUri("http://93.184.216.34/big.pdf")), CONVERSATION, PEER));

            assertEquals("A file part is larger than the limit of " + MAX_SIZE + " bytes", e.getMessage());
            assertTrue(store.blobs.isEmpty());
        }

        @Test
        @DisplayName("a non-2xx answer is refused as not fetched")
        void non2xxRefused() {
            engine.respond(404, "not here");
            InvalidA2ARequestException e = refused(() -> files(InboundFileMode.INLINE_AND_URI)
                    .store(List.of(byUri("http://93.184.216.34/missing.pdf")), CONVERSATION, PEER));
            assertEquals(A2AInboundFiles.REFUSED_NOT_FETCHED, e.getMessage());
        }

        @ParameterizedTest
        @ValueSource(strings = {"file:///etc/passwd", "ftp://93.184.216.34/x", "relative/path.pdf", "http:///nohost",
                "http://user:pw@93.184.216.34/x",
                "http://exa mple.com/x"})
        @DisplayName("anything but an absolute http(s) URI without credentials is refused by the check, before a conversation exists")
        void unusableUriRefusedByCheck(String uri) {
            InvalidA2ARequestException e = refused(() -> files(InboundFileMode.INLINE_AND_URI).check(List.of(byUri(uri))));
            assertEquals(ERROR_INVALID_PARAMS, e.getCode());
            assertTrue(engine.hops.isEmpty());
        }

        @Test
        @DisplayName("the fetch sends no credentials of the peer's request")
        void noCredentialsSent() {
            engine.respond(200, Map.of(), PDF);
            files(InboundFileMode.INLINE_AND_URI).store(List.of(byUri("http://93.184.216.34/report.pdf")), CONVERSATION, PEER);
            Map<String, List<String>> headers = engine.lastHop().headers();
            assertNull(headers.get("Authorization"));
            assertNull(headers.get("authorization"));
        }
    }

    @Nested
    @DisplayName("rehome — a context that moved to a fresh conversation")
    class Rehome {

        @Test
        @DisplayName("copies the files into the fresh conversation and removes them from the ended one")
        void movesOwnership() throws Exception {
            A2AInboundFiles inbound = files(InboundFileMode.INLINE);
            Map<String, Context> original = inbound.store(List.of(inline("report.pdf", "application/pdf", PDF)), CONVERSATION, PEER);

            Map<String, Context> moved = inbound.rehome(original, CONVERSATION, OTHER_CONVERSATION, PEER);

            String ref = storageRefOf(moved.get("attachment_0"));
            assertEquals(OTHER_CONVERSATION, store.blobs.get(ref).conversationId());
            assertArrayEquals(PDF, store.load(ref, OTHER_CONVERSATION));
            assertNull(store.blobs.get(storageRefOf(original.get("attachment_0"))), "the ended conversation keeps no copy");
            assertEquals("report.pdf", ((Map<?, ?>) moved.get("attachment_0").getValue()).get("fileName"));
            assertEquals(1, store.blobs.size());
        }

        @Test
        @DisplayName("a peer at its exact quota can still move its files: the original goes before the copy is stored")
        void movesAtTheQuota() {
            A2AInboundFiles inbound = files(InboundFileMode.INLINE);
            Map<String, Context> original = inbound.store(List.of(inline("report.pdf", "application/pdf", PDF)), CONVERSATION, PEER);
            store.maxPerUser(1);

            Map<String, Context> moved = inbound.rehome(original, CONVERSATION, OTHER_CONVERSATION, PEER);

            assertEquals(OTHER_CONVERSATION, store.blobs.get(storageRefOf(moved.get("attachment_0"))).conversationId());
            assertEquals(1, store.blobs.size());
        }

        @Test
        @DisplayName("a reference the source conversation cannot read is refused")
        void unreadableRefused() {
            Map<String, Context> foreign = Map.of("attachment_0",
                    new Context(Context.ContextType.object, Map.of("storageRef", "0123456789abcdef01234567")));
            InvalidA2ARequestException e = refused(() -> files(InboundFileMode.INLINE).rehome(foreign, CONVERSATION, OTHER_CONVERSATION, PEER));
            assertInstanceOf(InvalidA2ARequestException.class, e);
            assertTrue(store.blobs.isEmpty());
        }
    }
}
