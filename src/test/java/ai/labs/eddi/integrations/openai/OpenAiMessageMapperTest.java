/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.integrations.openai;

import ai.labs.eddi.engine.model.Context;
import ai.labs.eddi.engine.model.InputData;
import ai.labs.eddi.integrations.openai.model.ChatCompletionRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link OpenAiMessageMapper}.
 * <p>
 * Requests are built by parsing real OpenAI JSON rather than by constructing
 * records, so the polymorphic {@code content} and {@code user} fields are
 * exercised through the same deserialization path a live request takes.
 */
class OpenAiMessageMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final OpenAiMessageMapper mapper = new OpenAiMessageMapper(objectMapper, 5);

    private ChatCompletionRequest parse(String json) throws Exception {
        return objectMapper.readValue(json, ChatCompletionRequest.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> attachment(InputData input, int index) {
        Context context = input.getContext().get("attachment_" + index);
        assertNotNull(context, "expected attachment_" + index + " in " + input.getContext().keySet());
        assertEquals(Context.ContextType.object, context.getType());
        return (Map<String, Object>) context.getValue();
    }

    // ─── text ───

    @Test
    void plainStringContent_becomesTheInput() throws Exception {
        var request = parse("""
                {"model":"m","messages":[{"role":"user","content":"Hello there"}]}""");

        assertEquals("Hello there", mapper.toInputData(request).getInput());
    }

    @Test
    void onlyTheLastUserMessageIsCarried() throws Exception {
        // The client resends history every turn; EDDI has its own memory, so
        // replaying it would double every turn.
        var request = parse("""
                {"model":"m","messages":[
                  {"role":"user","content":"first"},
                  {"role":"assistant","content":"reply"},
                  {"role":"user","content":"second"}]}""");

        assertEquals("second", mapper.toInputData(request).getInput());
    }

    @Test
    void noUserMessage_throws() throws Exception {
        var request = parse("""
                {"model":"m","messages":[{"role":"system","content":"be nice"}]}""");

        assertThrows(OpenAiMessageMapper.NoUserMessageException.class, () -> mapper.toInputData(request));
    }

    @Test
    void emptyOrMissingMessages_throws() throws Exception {
        assertThrows(OpenAiMessageMapper.NoUserMessageException.class,
                () -> mapper.toInputData(parse("{\"model\":\"m\",\"messages\":[]}")));
        assertThrows(OpenAiMessageMapper.NoUserMessageException.class,
                () -> mapper.toInputData(parse("{\"model\":\"m\"}")));
    }

    // ─── system message ───

    @Test
    void lastSystemMessage_becomesContext_notTheInput() throws Exception {
        var request = parse("""
                {"model":"m","messages":[
                  {"role":"system","content":"ignored"},
                  {"role":"system","content":"You are terse."},
                  {"role":"user","content":"Hi"}]}""");

        InputData input = mapper.toInputData(request);

        assertEquals("Hi", input.getInput());
        assertEquals("You are terse.",
                input.getContext().get(OpenAiMessageMapper.CONTEXT_SYSTEM_MESSAGE).getValue());
    }

    @Test
    void noSystemMessage_leavesContextAbsent() throws Exception {
        var request = parse("""
                {"model":"m","messages":[{"role":"user","content":"Hi"}]}""");

        assertFalse(mapper.toInputData(request).getContext()
                .containsKey(OpenAiMessageMapper.CONTEXT_SYSTEM_MESSAGE));
    }

    @Test
    void blankSystemMessage_isNotStored() throws Exception {
        var request = parse("""
                {"model":"m","messages":[
                  {"role":"system","content":"   "},
                  {"role":"user","content":"Hi"}]}""");

        assertFalse(mapper.toInputData(request).getContext()
                .containsKey(OpenAiMessageMapper.CONTEXT_SYSTEM_MESSAGE));
    }

    @Test
    void channelContextIsAlwaysPresent() throws Exception {
        var request = parse("""
                {"model":"m","messages":[{"role":"user","content":"Hi"}]}""");

        assertEquals(OpenAiMessageMapper.CHANNEL_NAME,
                mapper.toInputData(request).getContext().get(OpenAiMessageMapper.CONTEXT_CHANNEL).getValue());
    }

    // ─── multimodal ───

    @Test
    void arrayContent_extractsTextAndImage() throws Exception {
        var request = parse("""
                {"model":"m","messages":[{"role":"user","content":[
                  {"type":"text","text":"What is this?"},
                  {"type":"image_url","image_url":{"url":"data:image/png;base64,iVBORw0KGgo="}}]}]}""");

        InputData input = mapper.toInputData(request);

        assertEquals("What is this?", input.getInput());
        Map<String, Object> attachment = attachment(input, 0);
        assertEquals("image/png", attachment.get("mimeType"));
        assertEquals("iVBORw0KGgo=", attachment.get("data"));
        assertNull(attachment.get("url"));
    }

    @Test
    void dataUriWithoutBase64Marker_doesNotThrow() throws Exception {
        // "data:image/png,<payload>" is legal and has no ';'. Scanning to ';' to
        // find the MIME would throw StringIndexOutOfBounds on this input.
        var request = parse("""
                {"model":"m","messages":[{"role":"user","content":[
                  {"type":"image_url","image_url":{"url":"data:image/png,rawpayload"}}]}]}""");

        Map<String, Object> attachment = attachment(mapper.toInputData(request), 0);

        assertEquals("image/png", attachment.get("mimeType"));
        assertEquals("rawpayload", attachment.get("data"));
    }

    @Test
    void malformedDataUriWithoutComma_isSkippedNotThrown() throws Exception {
        var request = parse("""
                {"model":"m","messages":[{"role":"user","content":[
                  {"type":"text","text":"look"},
                  {"type":"image_url","image_url":{"url":"data:image/png;base64"}}]}]}""");

        InputData input = mapper.toInputData(request);

        assertEquals("look", input.getInput());
        assertFalse(input.getContext().containsKey("attachment_0"),
                "an unparseable image must not fail the whole turn");
    }

    @Test
    void dataUriWithEmptyMime_fallsBackToConcreteType() throws Exception {
        var request = parse("""
                {"model":"m","messages":[{"role":"user","content":[
                  {"type":"image_url","image_url":{"url":"data:;base64,abc"}}]}]}""");

        assertEquals("image/jpeg", attachment(mapper.toInputData(request), 0).get("mimeType"));
    }

    @Test
    void remoteUrl_getsConcreteMime_neverWildcard() throws Exception {
        // ImageContent.from(base64, mimeType) receives this verbatim; providers
        // reject "image/*".
        var request = parse("""
                {"model":"m","messages":[{"role":"user","content":[
                  {"type":"image_url","image_url":{"url":"https://example.com/photo.PNG?v=2"}}]}]}""");

        Map<String, Object> attachment = attachment(mapper.toInputData(request), 0);

        assertEquals("image/png", attachment.get("mimeType"));
        assertEquals("https://example.com/photo.PNG?v=2", attachment.get("url"));
        assertNull(attachment.get("data"));
    }

    @Test
    void mimeFromUrl_coversExtensionsQueriesAndFragments() {
        assertEquals("image/png", OpenAiMessageMapper.mimeFromUrl("http://x/a.png"));
        assertEquals("image/jpeg", OpenAiMessageMapper.mimeFromUrl("http://x/a.jpg?w=1"));
        assertEquals("image/webp", OpenAiMessageMapper.mimeFromUrl("http://x/a.webp#frag"));
        assertEquals("image/jpeg", OpenAiMessageMapper.mimeFromUrl("http://x/noextension"));
        assertEquals("image/jpeg", OpenAiMessageMapper.mimeFromUrl("http://x/trailing."));
        assertEquals("image/jpeg", OpenAiMessageMapper.mimeFromUrl(null));
    }

    @Test
    void multipleImages_getSequentialAttachmentKeys() throws Exception {
        var request = parse("""
                {"model":"m","messages":[{"role":"user","content":[
                  {"type":"image_url","image_url":{"url":"data:image/png;base64,a"}},
                  {"type":"image_url","image_url":{"url":"data:image/gif;base64,b"}}]}]}""");

        InputData input = mapper.toInputData(request);

        assertEquals("image/png", attachment(input, 0).get("mimeType"));
        assertEquals("image/gif", attachment(input, 1).get("mimeType"));
    }

    @Test
    void attachmentsBeyondCap_areDropped_notFailed() throws Exception {
        var cappedMapper = new OpenAiMessageMapper(objectMapper, 2);
        var request = parse("""
                {"model":"m","messages":[{"role":"user","content":[
                  {"type":"text","text":"three images"},
                  {"type":"image_url","image_url":{"url":"data:image/png;base64,a"}},
                  {"type":"image_url","image_url":{"url":"data:image/png;base64,b"}},
                  {"type":"image_url","image_url":{"url":"data:image/png;base64,c"}}]}]}""");

        InputData input = cappedMapper.toInputData(request);

        assertTrue(input.getContext().containsKey("attachment_0"));
        assertTrue(input.getContext().containsKey("attachment_1"));
        assertFalse(input.getContext().containsKey("attachment_2"));
        assertEquals("three images", input.getInput());
    }

    @Test
    void multipleTextParts_areJoinedWithNewlines() throws Exception {
        var request = parse("""
                {"model":"m","messages":[{"role":"user","content":[
                  {"type":"text","text":"line one"},
                  {"type":"text","text":"line two"}]}]}""");

        assertEquals("line one\nline two", mapper.toInputData(request).getInput());
    }

    @Test
    void unknownContentPartTypes_areIgnored() throws Exception {
        var request = parse("""
                {"model":"m","messages":[{"role":"user","content":[
                  {"type":"text","text":"hi"},
                  {"type":"video_url","video_url":{"url":"http://x/v.mp4"}}]}]}""");

        InputData input = mapper.toInputData(request);

        assertEquals("hi", input.getInput());
        assertFalse(input.getContext().containsKey("attachment_0"));
    }

    // ─── file parts (PDFs and documents) ───

    @Test
    void filePart_becomesAnAttachmentWithItsDeclaredNameAndType() throws Exception {
        var request = parse("""
                {"model":"m","messages":[{"role":"user","content":[
                  {"type":"text","text":"summarise this"},
                  {"type":"file","file":{"filename":"report.pdf",
                   "file_data":"data:application/pdf;base64,JVBERi0x"}}]}]}""");

        InputData input = mapper.toInputData(request);
        Map<String, Object> attachment = attachment(input, 0);

        assertEquals("summarise this", input.getInput());
        assertEquals("application/pdf", attachment.get("mimeType"));
        assertEquals("JVBERi0x", attachment.get("data"));
        assertEquals("report.pdf", attachment.get("fileName"),
                "the declared filename is what the agent and any text extractor see");
    }

    @Test
    void filePart_acceptsFileNameAlias() throws Exception {
        // "file_name" appears in the wild alongside the spec's "filename".
        var request = parse("""
                {"model":"m","messages":[{"role":"user","content":[
                  {"type":"file","file":{"file_name":"notes.txt",
                   "file_data":"data:text/plain;base64,aGk="}}]}]}""");

        assertEquals("notes.txt", attachment(mapper.toInputData(request), 0).get("fileName"));
    }

    @Test
    void filePart_derivesMimeFromFilename_whenDataUriIsGeneric() throws Exception {
        // Clients that base64 a file without knowing its type send
        // application/octet-stream; the filename is the better hint.
        var request = parse("""
                {"model":"m","messages":[{"role":"user","content":[
                  {"type":"file","file":{"filename":"contract.pdf",
                   "file_data":"data:application/octet-stream;base64,JVBERi0x"}}]}]}""");

        assertEquals("application/pdf", attachment(mapper.toInputData(request), 0).get("mimeType"));
    }

    @Test
    void filePart_withOnlyFileId_isSkipped() throws Exception {
        // file_id refers to the OpenAI Files API, which EDDI does not implement.
        // An empty attachment would be worse than none.
        var request = parse("""
                {"model":"m","messages":[{"role":"user","content":[
                  {"type":"text","text":"read it"},
                  {"type":"file","file":{"file_id":"file-abc123"}}]}]}""");

        InputData input = mapper.toInputData(request);

        assertEquals("read it", input.getInput());
        assertFalse(input.getContext().containsKey("attachment_0"));
    }

    @Test
    void filePart_withoutDataOrId_isSkipped() throws Exception {
        var request = parse("""
                {"model":"m","messages":[{"role":"user","content":[
                  {"type":"file","file":{"filename":"empty.pdf"}}]}]}""");

        assertFalse(mapper.toInputData(request).getContext().containsKey("attachment_0"));
    }

    @Test
    void filePart_unnamed_stillGetsAnAttachment() throws Exception {
        var request = parse("""
                {"model":"m","messages":[{"role":"user","content":[
                  {"type":"file","file":{"file_data":"data:application/pdf;base64,JVBERi0x"}}]}]}""");

        Map<String, Object> attachment = attachment(mapper.toInputData(request), 0);

        assertEquals("application/pdf", attachment.get("mimeType"));
        assertEquals("openai-attachment-0", attachment.get("fileName"));
    }

    // ─── audio parts ───

    @Test
    void inputAudio_becomesAnAudioAttachment() throws Exception {
        // Note the asymmetry: input_audio.data is RAW base64, with no data: URI
        // wrapper, unlike every other binary payload in this protocol.
        var request = parse("""
                {"model":"m","messages":[{"role":"user","content":[
                  {"type":"text","text":"transcribe"},
                  {"type":"input_audio","input_audio":{"data":"UklGRg==","format":"wav"}}]}]}""");

        InputData input = mapper.toInputData(request);
        Map<String, Object> attachment = attachment(input, 0);

        assertEquals("transcribe", input.getInput());
        assertEquals("audio/wav", attachment.get("mimeType"));
        assertEquals("UklGRg==", attachment.get("data"));
    }

    @Test
    void inputAudio_mp3MapsToAudioMpeg_notAudioMp3() throws Exception {
        // Naive "audio/" + format would produce audio/mp3, which is not a real
        // media type and which providers reject.
        var request = parse("""
                {"model":"m","messages":[{"role":"user","content":[
                  {"type":"input_audio","input_audio":{"data":"SUQz","format":"mp3"}}]}]}""");

        assertEquals("audio/mpeg", attachment(mapper.toInputData(request), 0).get("mimeType"));
    }

    @Test
    void inputAudio_unknownFormat_stillForwardedAsAudio() throws Exception {
        var request = parse("""
                {"model":"m","messages":[{"role":"user","content":[
                  {"type":"input_audio","input_audio":{"data":"AAAA","format":"opus"}}]}]}""");

        assertEquals("audio/opus", attachment(mapper.toInputData(request), 0).get("mimeType"));
    }

    @Test
    void inputAudio_withoutData_isSkipped() throws Exception {
        var request = parse("""
                {"model":"m","messages":[{"role":"user","content":[
                  {"type":"input_audio","input_audio":{"format":"wav"}}]}]}""");

        assertFalse(mapper.toInputData(request).getContext().containsKey("attachment_0"));
    }

    @Test
    void mixedAttachmentTypes_shareOneIndexSequence() throws Exception {
        var request = parse("""
                {"model":"m","messages":[{"role":"user","content":[
                  {"type":"image_url","image_url":{"url":"data:image/png;base64,a"}},
                  {"type":"file","file":{"filename":"d.pdf","file_data":"data:application/pdf;base64,b"}},
                  {"type":"input_audio","input_audio":{"data":"c","format":"wav"}}]}]}""");

        InputData input = mapper.toInputData(request);

        assertEquals("image/png", attachment(input, 0).get("mimeType"));
        assertEquals("application/pdf", attachment(input, 1).get("mimeType"));
        assertEquals("audio/wav", attachment(input, 2).get("mimeType"));
    }

    @Test
    void perTurnCapCountsAllAttachmentTypes() throws Exception {
        var cappedMapper = new OpenAiMessageMapper(objectMapper, 2);
        var request = parse("""
                {"model":"m","messages":[{"role":"user","content":[
                  {"type":"image_url","image_url":{"url":"data:image/png;base64,a"}},
                  {"type":"file","file":{"filename":"d.pdf","file_data":"data:application/pdf;base64,b"}},
                  {"type":"input_audio","input_audio":{"data":"c","format":"wav"}}]}]}""");

        InputData input = cappedMapper.toInputData(request);

        assertTrue(input.getContext().containsKey("attachment_1"));
        assertFalse(input.getContext().containsKey("attachment_2"));
    }

    @Test
    void mimeFromFileName_fallsBackWhenUnrecognised() {
        assertEquals("application/pdf", OpenAiMessageMapper.mimeFromFileName("a.PDF", "x/y"));
        assertEquals("text/csv", OpenAiMessageMapper.mimeFromFileName("data.csv", "x/y"));
        assertEquals("x/y", OpenAiMessageMapper.mimeFromFileName("archive.xyz", "x/y"));
        assertEquals("x/y", OpenAiMessageMapper.mimeFromFileName("noextension", "x/y"));
        assertEquals("x/y", OpenAiMessageMapper.mimeFromFileName(null, "x/y"));
    }

    // ─── request-level wire tolerance ───

    @Test
    void unknownRequestFields_doNotFailBinding() throws Exception {
        // Open WebUI and the SDKs always send these; rejecting them would break
        // every client.
        var request = parse("""
                {"model":"m","messages":[{"role":"user","content":"Hi"}],
                 "temperature":0.7,"max_tokens":100,"top_p":1,
                 "stream_options":{"include_usage":true},
                 "tools":[{"type":"function","function":{"name":"f"}}],
                 "tool_choice":"auto","metadata":{"a":"b"},"files":[]}""");

        assertEquals("Hi", mapper.toInputData(request).getInput());
    }

    @Test
    void userField_acceptsPlainString() throws Exception {
        var request = parse("""
                {"model":"m","messages":[{"role":"user","content":"Hi"}],"user":"chat-42"}""");

        assertEquals("chat-42", request.userAsString());
    }

    @Test
    void userField_acceptsOpenWebUiObjectShape() throws Exception {
        // Open WebUI sends an object here for pipeline models; a String binding
        // would fail the whole request.
        var request = parse("""
                {"model":"m","messages":[{"role":"user","content":"Hi"}],
                 "user":{"name":"Alice","id":"u_812","email":"a@x.io","role":"user"}}""");

        assertEquals("u_812", request.userAsString());
    }

    @Test
    void userField_absentOrUnusable_isNull() throws Exception {
        assertNull(parse("""
                {"model":"m","messages":[{"role":"user","content":"Hi"}]}""").userAsString());
        assertNull(parse("""
                {"model":"m","messages":[{"role":"user","content":"Hi"}],"user":"  "}""").userAsString());
        assertNull(parse("""
                {"model":"m","messages":[{"role":"user","content":"Hi"}],"user":{"name":"Alice"}}""")
                .userAsString());
    }

    @Test
    void streamFlag_defaultsToFalseWhenAbsent() throws Exception {
        assertFalse(parse("""
                {"model":"m","messages":[{"role":"user","content":"Hi"}]}""").isStreaming());
        assertTrue(parse("""
                {"model":"m","messages":[{"role":"user","content":"Hi"}],"stream":true}""").isStreaming());
    }

    @Test
    void inputDataContextIsMutableMap() throws Exception {
        // The bridge adds channelIntent after mapping; an immutable map would throw.
        var request = parse("""
                {"model":"m","messages":[{"role":"user","content":"Hi"}]}""");

        InputData input = mapper.toInputData(request);
        assertInstanceOf(Map.class, input.getContext());
        input.getContext().put("extra", new Context(Context.ContextType.string, "v"));
    }
}
