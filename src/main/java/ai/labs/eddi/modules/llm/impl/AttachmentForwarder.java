/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.attachments.AttachmentSelector;
import ai.labs.eddi.engine.attachments.ConversationListing;
import ai.labs.eddi.engine.attachments.Derivation;
import ai.labs.eddi.engine.attachments.IAttachmentStore;
import ai.labs.eddi.engine.documents.EffectiveDocumentsConfig;
import ai.labs.eddi.engine.documents.SelectorException;
import ai.labs.eddi.engine.documents.SelectorResolver;
import ai.labs.eddi.engine.httpclient.outbound.BodyMode;
import ai.labs.eddi.engine.httpclient.outbound.OutboundHttp;
import ai.labs.eddi.engine.httpclient.outbound.OutboundRequest;
import ai.labs.eddi.engine.httpclient.outbound.OutboundResponse;
import ai.labs.eddi.engine.httpclient.outbound.Profile;
import ai.labs.eddi.engine.httpclient.outbound.SsrfPolicy;
import ai.labs.eddi.engine.memory.AttachmentContextExtractor;
import ai.labs.eddi.engine.memory.IConversationMemory;
import ai.labs.eddi.engine.memory.IConversationMemory.IWritableConversationStep;
import ai.labs.eddi.engine.memory.IData;
import ai.labs.eddi.engine.memory.model.Attachment;
import ai.labs.eddi.engine.memory.model.Data;
import ai.labs.eddi.modules.ingestion.extract.PdfSliceBuilder;
import ai.labs.eddi.modules.ingestion.extract.PdfSliceBuilder.SliceTooLargeException;
import ai.labs.eddi.modules.ingestion.extract.UnreadableDocumentException;
import ai.labs.eddi.modules.llm.capability.ModelCapabilityService;
import ai.labs.eddi.modules.llm.impl.DocumentForwardPlanner.Form;
import ai.labs.eddi.modules.llm.impl.DocumentForwardPlanner.Sizes;
import ai.labs.eddi.modules.llm.tools.impl.AttachmentTextExtractor;
import ai.labs.eddi.modules.llm.tools.impl.AttachmentTextExtractor.AttachmentExtractionException;
import ai.labs.eddi.utils.LogSanitizer;
import dev.langchain4j.data.message.AudioContent;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.PdfFileContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static ai.labs.eddi.engine.memory.MemoryKeys.ATTACHMENTS;
import static ai.labs.eddi.engine.memory.MemoryKeys.ATTACHMENT_ERRORS;
import static ai.labs.eddi.engine.memory.MemoryKeys.ATTACHMENT_EXTRACTS;
import static ai.labs.eddi.modules.llm.tools.UrlValidationUtils.validateUrl;

/**
 * The single place attachments become langchain4j {@link Content} on the
 * outgoing user message. Replaces the image-only
 * {@code MultimodalMessageEnhancer} and the divergent handling in
 * {@code convertMessage}.
 * <p>
 * Per attachment it resolves bytes from any source (stored blob, URL, inline
 * base64) under uniform per-file and aggregate byte caps, gates on
 * {@link ModelCapabilityService}, and emits the right content:
 * <ul>
 * <li>{@code image/*} → {@link ImageContent} when the model has vision (URL
 * kept as-is when the provider fetches URLs, otherwise downloaded and inlined),
 * else a metadata note.</li>
 * <li>{@code application/pdf} → native {@link PdfFileContent} when the model
 * supports documents, else PDFBox text extraction inlined as
 * {@link TextContent}. A stored PDF that names a page range (an
 * {@link AttachmentSelector} part) sends only those pages: a PDF of them built
 * by {@link PdfSliceBuilder} (cached as a {@code PDF_SLICE} derived blob),
 * preceded by a header naming the file and pages, or — for a model without
 * document support, or a slice too large to send — those pages' text under the
 * same header. {@link DocumentForwardPlanner} decides which.</li>
 * <li>text-like ({@code text/*}, JSON, XML, CSV, YAML) → decoded and inlined
 * (no capability required).</li>
 * <li>{@code audio/*} → {@link AudioContent} when supported, else a note.</li>
 * <li>anything else → a metadata note pointing at the {@code readAttachment}
 * tool.</li>
 * </ul>
 * Extracted text is persisted to {@code attachments:extracts} (for history
 * stitching) and every drop/skip/gate is appended to {@code attachments:errors}
 * — never silent.
 *
 * @since 6.1.0
 */
@ApplicationScoped
public class AttachmentForwarder {

    private static final Logger LOGGER = Logger.getLogger(AttachmentForwarder.class);

    private final IAttachmentStore attachmentStore;
    private final ModelCapabilityService capabilityService;
    private final AttachmentTextExtractor textExtractor;
    private final OutboundHttp http;
    private final PdfSliceBuilder sliceBuilder;
    private final Path workDir;
    private final long maxForwardBytes;
    private final long maxAggregateBytes;
    private final Counter forwardedCounter;
    private final Counter reinlinedCounter;
    private final Counter errorsCounter;

    @Inject
    public AttachmentForwarder(IAttachmentStore attachmentStore,
            ModelCapabilityService capabilityService,
            AttachmentTextExtractor textExtractor,
            OutboundHttp http,
            PdfSliceBuilder sliceBuilder,
            MeterRegistry meterRegistry,
            @ConfigProperty(name = "eddi.attachments.max-forward-bytes",
                            defaultValue = "10485760") long maxForwardBytes,
            @ConfigProperty(name = "eddi.attachments.max-forward-aggregate-bytes",
                            defaultValue = "20971520") long maxAggregateBytes) {
        this(attachmentStore, capabilityService, textExtractor, http, sliceBuilder, defaultWorkDir(), meterRegistry,
                maxForwardBytes, maxAggregateBytes);
    }

    /** With a default slice builder. */
    public AttachmentForwarder(IAttachmentStore attachmentStore,
            ModelCapabilityService capabilityService,
            AttachmentTextExtractor textExtractor,
            OutboundHttp http,
            MeterRegistry meterRegistry,
            long maxForwardBytes,
            long maxAggregateBytes) {
        this(attachmentStore, capabilityService, textExtractor, http, new PdfSliceBuilder(), defaultWorkDir(), meterRegistry,
                maxForwardBytes, maxAggregateBytes);
    }

    /** For tests: slices are built in {@code workDir}. */
    AttachmentForwarder(IAttachmentStore attachmentStore,
            ModelCapabilityService capabilityService,
            AttachmentTextExtractor textExtractor,
            OutboundHttp http,
            PdfSliceBuilder sliceBuilder,
            Path workDir,
            MeterRegistry meterRegistry,
            long maxForwardBytes,
            long maxAggregateBytes) {
        this.attachmentStore = attachmentStore;
        this.capabilityService = capabilityService;
        this.textExtractor = textExtractor;
        this.http = http;
        this.sliceBuilder = sliceBuilder != null ? sliceBuilder : new PdfSliceBuilder();
        this.workDir = workDir != null ? workDir : defaultWorkDir();
        this.forwardedCounter = meterRegistry != null ? meterRegistry.counter("eddi.attachment.forwarded") : null;
        // Separate from `forwarded`: a re-inline repeats every attachment-free
        // turn, so folding it into the first-time counter would quietly turn one
        // screenshot in a 20-turn conversation into ~20 "forwarded" attachments
        // for anyone alerting on that meter.
        this.reinlinedCounter = meterRegistry != null ? meterRegistry.counter("eddi.attachment.reinlined") : null;
        this.errorsCounter = meterRegistry != null ? meterRegistry.counter("eddi.attachment.errors") : null;
        this.maxForwardBytes = maxForwardBytes;
        this.maxAggregateBytes = maxAggregateBytes;
    }

    /**
     * Where slices are written while they are built: the JVM's temporary directory.
     * A configurable work directory arrives with the file-backed processing of
     * large documents.
     */
    private static Path defaultWorkDir() {
        return Path.of(System.getProperty("java.io.tmpdir", "."));
    }

    /**
     * Enhance the last {@link UserMessage} in {@code messages} (modified in place)
     * with content for the current step's attachments, gated on the model's
     * capabilities. Extracted text and error notes are persisted to step data.
     *
     * @param messages
     *            the outgoing chat messages (modified in place)
     * @param memory
     *            conversation memory for the current turn
     * @param provider
     *            the resolved LLM provider (e.g. {@code openai})
     * @param model
     *            the resolved model name
     */
    public void forward(List<ChatMessage> messages, IConversationMemory memory, String provider, String model) {
        forward(messages, memory, provider, model,
                ModelCapabilityService.Support.AUTO,
                ModelCapabilityService.Support.AUTO,
                ModelCapabilityService.Support.AUTO);
    }

    /**
     * Overload honoring per-task multimodal overrides (from
     * {@code LlmConfiguration.Task.multimodal}). {@code AUTO} defers to the
     * capability service's deployment/built-in defaults.
     */
    public void forward(List<ChatMessage> messages, IConversationMemory memory, String provider, String model,
                        ModelCapabilityService.Support visionOverride,
                        ModelCapabilityService.Support documentsOverride,
                        ModelCapabilityService.Support audioOverride) {
        forward(messages, memory, provider, model, visionOverride, documentsOverride, audioOverride, true);
    }

    /**
     * As above; {@code record=false} builds the content without touching the step
     * data or the meters. A cascade step on a different endpoint re-forwards the
     * same attachments for its own prompt, and the turn's errors and counts were
     * already recorded once, by the task-level pass.
     */
    public void forward(List<ChatMessage> messages, IConversationMemory memory, String provider, String model,
                        ModelCapabilityService.Support visionOverride,
                        ModelCapabilityService.Support documentsOverride,
                        ModelCapabilityService.Support audioOverride, boolean record) {
        forward(messages, memory, provider, model, visionOverride, documentsOverride, audioOverride, record, null);
    }

    /**
     * As above, with the task's effective {@code documents} configuration for
     * {@link DocumentForwardPlanner} ({@code null}: the defaults). No key of it
     * changes how a PDF is sent yet.
     */
    public void forward(List<ChatMessage> messages, IConversationMemory memory, String provider, String model,
                        ModelCapabilityService.Support visionOverride,
                        ModelCapabilityService.Support documentsOverride,
                        ModelCapabilityService.Support audioOverride, boolean record,
                        EffectiveDocumentsConfig documents) {
        if (messages == null || messages.isEmpty()) {
            return;
        }
        List<Attachment> attachments = readAttachments(memory);
        if (attachments.isEmpty()) {
            noteAttachmentsFromEarlierTurns(messages, memory, provider, model, visionOverride, record);
            return;
        }
        int lastUserIdx = lastUserMessageIndex(messages);
        if (lastUserIdx < 0) {
            return;
        }

        UserMessage original = (UserMessage) messages.get(lastUserIdx);
        List<Content> contents = new ArrayList<>(original.contents());

        List<String> extracts = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        long[] aggregate = {0L};
        int added = 0;
        // Read only when a part is present, so a turn without one does exactly what
        // it did before parts existed.
        Set<String> withheld = attachments.stream().map(AttachmentSelector::fromAttachment).anyMatch(DocumentForwardPlanner::isPart)
                ? AttachmentContextExtractor.withheldStorageRefs(memory)
                : Set.of();

        for (Attachment att : attachments) {
            List<Content> produced = process(att, memory.getConversationId(), provider, model, aggregate, extracts, errors,
                    visionOverride, documentsOverride, audioOverride, withheld, documents);
            if (!produced.isEmpty()) {
                contents.addAll(produced);
                added++;
            }
        }

        if (added > 0) {
            messages.set(lastUserIdx, UserMessage.from(contents));
            LOGGER.debugf("Forwarded %d attachment(s) to the LLM", added);
        }
        if (record) {
            recordMetrics(added, errors.size());
            persist(memory.getCurrentStep(), extracts, errors);
        }
    }

    /**
     * Largest number of earlier-turn files named in the reminder note. Beyond this
     * the note says how many more there are and leaves finding them to
     * {@code listAttachments}.
     */
    private static final int MAX_EARLIER_ATTACHMENTS_NOTED = 10;

    /**
     * How many earlier-turn IMAGES are re-inlined for a vision model on a later
     * turn. Text documents have {@code readAttachment}; an image has no tool-shaped
     * fallback — there is no OCR — so without re-inlining, "attach a screenshot,
     * then ask about it" dead-ends in "no readable content". Most-recent-first, so
     * the cap keeps the flow (the screenshot just discussed) while an old
     * image-heavy conversation does not re-pay its whole gallery every turn.
     */
    private static final int MAX_EARLIER_IMAGES_REINLINED = 3;

    /**
     * On a turn that carries no attachments of its own, re-inline the most recent
     * earlier-turn images when the model has vision, and remind the model of the
     * remaining files with a pointer at the {@code readAttachment} tool.
     * <p>
     * A document is inlined only on the turn it arrives — re-sending it every turn
     * would burn the context window, and its text stays reachable through
     * {@code readAttachment}. Images are the exception (see
     * {@link #MAX_EARLIER_IMAGES_REINLINED}): a vision model gets the recent ones
     * again as real image content, because no tool can substitute for seeing them.
     * Everything else gets the one-line note: the content stays out of the prompt,
     * the knowledge that it exists does not.
     */
    private void noteAttachmentsFromEarlierTurns(List<ChatMessage> messages, IConversationMemory memory,
                                                 String provider, String model,
                                                 ModelCapabilityService.Support visionOverride, boolean record) {
        List<Attachment> earlier = AttachmentContextExtractor.attachmentsFromPreviousTurns(memory);
        if (earlier.isEmpty()) {
            return;
        }
        int lastUserIdx = lastUserMessageIndex(messages);
        if (lastUserIdx < 0) {
            return;
        }

        boolean vision = capabilityService.supportsVision(provider, model, visionOverride);
        List<Attachment> reinline = new ArrayList<>();
        List<Attachment> noteOnly = new ArrayList<>();
        for (Attachment att : earlier) {
            String mime = att.getMimeType() == null ? "" : att.getMimeType().toLowerCase(Locale.ROOT);
            if (vision && mime.startsWith("image/") && reinline.size() < MAX_EARLIER_IMAGES_REINLINED) {
                reinline.add(att);
            } else {
                noteOnly.add(att);
            }
        }

        UserMessage original = (UserMessage) messages.get(lastUserIdx);
        List<Content> contents = new ArrayList<>(original.contents());

        List<String> errors = new ArrayList<>();
        long[] aggregate = {0L};
        int inlined = 0;
        for (Attachment att : reinline) {
            // Same caps, byte resolution and vision gating as a current-turn
            // image; a resolve failure degrades to the skip note, never a throw.
            List<Content> produced = process(att, memory.getConversationId(), provider, model, aggregate,
                    new ArrayList<>(), errors, visionOverride,
                    ModelCapabilityService.Support.AUTO, ModelCapabilityService.Support.AUTO, Set.of(), null);
            contents.addAll(produced);
            // Only an actual image counts as re-inlined: a failed store load
            // or cap skip comes back as a TextContent note — the model is
            // told, but counting it would claim "1 image re-inlined" (and
            // increment the counter) on every remaining turn of a
            // conversation whose blob is permanently gone.
            if (produced.stream().anyMatch(ImageContent.class::isInstance)) {
                inlined++;
            }
        }

        if (!noteOnly.isEmpty()) {
            StringBuilder note = new StringBuilder(
                    "[Files shared earlier in this conversation and still available: ");
            int named = Math.min(noteOnly.size(), MAX_EARLIER_ATTACHMENTS_NOTED);
            for (int i = 0; i < named; i++) {
                Attachment att = noteOnly.get(i);
                if (i > 0) {
                    note.append(", ");
                }
                note.append(att.getFileName() != null ? att.getFileName() : "unnamed");
                if (att.getMimeType() != null) {
                    note.append(" (").append(att.getMimeType()).append(')');
                }
            }
            if (noteOnly.size() > named) {
                note.append(", and ").append(noteOnly.size() - named).append(" more");
            }
            note.append(". Their content is not included in this message — use the readAttachment tool ")
                    .append("to read one, or listAttachments to see them all. Do not claim no file was shared.]");
            contents.add(TextContent.from(note.toString()));
        }

        // The message is updated whenever ANYTHING was added — including a
        // failure note for an image that could not be re-inlined.
        if (contents.size() > original.contents().size()) {
            messages.set(lastUserIdx, UserMessage.from(contents));
        }
        if (record) {
            if (reinlinedCounter != null && inlined > 0) {
                reinlinedCounter.increment(inlined);
            }
            if (errorsCounter != null && !errors.isEmpty()) {
                errorsCounter.increment(errors.size());
            }
            persist(memory.getCurrentStep(), List.of(), errors);
        }
        LOGGER.debugf("Earlier-turn attachments for conversation='%s': %d image(s) re-inlined, %d noted",
                LogSanitizer.sanitize(memory.getConversationId()), inlined, noteOnly.size());
    }

    private void recordMetrics(int forwarded, int errored) {
        if (forwardedCounter != null && forwarded > 0) {
            forwardedCounter.increment(forwarded);
        }
        if (errorsCounter != null && errored > 0) {
            errorsCounter.increment(errored);
        }
    }

    /**
     * The content one attachment becomes: usually one item, two for a page range
     * sent as a PDF (header, then the slice), none when it is dropped.
     */
    private List<Content> process(Attachment att, String conversationId, String provider, String model,
                                  long[] aggregate, List<String> extracts, List<String> errors,
                                  ModelCapabilityService.Support visionOverride,
                                  ModelCapabilityService.Support documentsOverride,
                                  ModelCapabilityService.Support audioOverride,
                                  Set<String> withheld, EffectiveDocumentsConfig documents) {
        String mime = att.getMimeType() == null ? "" : att.getMimeType().toLowerCase(Locale.ROOT);
        String name = att.getFileName() != null ? att.getFileName() : "unnamed";

        if (att.getContentSource() == Attachment.ContentSource.NONE) {
            errors.add("Attachment '" + name + "' has no content and was not forwarded.");
            return List.of();
        }

        // Fast path: an image the provider can fetch by URL — no download needed.
        boolean isImage = mime.startsWith("image/");
        boolean isAudio = mime.startsWith("audio/");
        boolean isPdf = mime.startsWith("application/pdf");

        if (isImage && att.getContentSource() == Attachment.ContentSource.URL
                && capabilityService.supportsVision(provider, model, visionOverride)
                && capabilityService.supportsImageUrl(provider, model)) {
            try {
                return List.of(ImageContent.from(URI.create(att.getUrl())));
            } catch (Exception e) {
                errors.add("Image '" + name + "' URL could not be attached: " + e.getMessage());
                return List.of();
            }
        }

        // A part of a stored PDF is sent as that part, never as the whole file. Its
        // bytes are resolved and capped differently: the slice counts, not the file.
        AttachmentSelector selector = AttachmentSelector.fromAttachment(att);
        if (!isPdf && DocumentForwardPlanner.isPart(selector)) {
            // Only a PDF has pages to send. Intake refuses a part of anything else; a
            // part that arrives another way still never becomes the whole file.
            return List.of(partNote(errors, ConversationListing.partName(name, selector), "only a PDF can be sent in part"));
        }
        if (isPdf && DocumentForwardPlanner.isPart(selector)) {
            var capabilities = new DocumentForwardPlanner.Capabilities(
                    capabilityService.supportsDocuments(provider, model, documentsOverride));
            return processPart(att, selector, name, conversationId, capabilities, aggregate, extracts, errors, withheld,
                    documents);
        }

        // Everything else needs the bytes in hand. A skip (cap/load/fetch failure)
        // still leaves a note the LLM can relay, in addition to the error record.
        byte[] bytes;
        try {
            bytes = resolveBytes(att, conversationId, aggregate);
        } catch (ForwardSkipException e) {
            errors.add(e.getMessage());
            return List.of(TextContent.from(e.getMessage()));
        }

        if (isImage) {
            if (!capabilityService.supportsVision(provider, model, visionOverride)) {
                return List.of(note(errors, name, mime, att.getSizeBytes(),
                        "model does not support images"));
            }
            return List.of(ImageContent.from(Base64.getEncoder().encodeToString(bytes), att.getMimeType()));
        }

        if (isPdf) {
            var capabilities = new DocumentForwardPlanner.Capabilities(
                    capabilityService.supportsDocuments(provider, model, documentsOverride));
            Form form = DocumentForwardPlanner.plan(selector, capabilities, null, documents);
            if (form == Form.WHOLE_NATIVE) {
                return List.of(PdfFileContent.from(Base64.getEncoder().encodeToString(bytes), att.getMimeType()));
            }
            return List.of(extractInline(bytes, att.getMimeType(), name, extracts, errors,
                    "PDF text-extracted (model has no native document support)"));
        }

        if (textExtractor.canExtractText(att.getMimeType())) {
            return List.of(extractInline(bytes, att.getMimeType(), name, extracts, errors, "inlined as text"));
        }

        if (isAudio) {
            if (!capabilityService.supportsAudio(provider, model, audioOverride)) {
                return List.of(note(errors, name, mime, att.getSizeBytes(), "model does not support audio"));
            }
            return List.of(AudioContent.from(Base64.getEncoder().encodeToString(bytes), att.getMimeType()));
        }

        return List.of(note(errors, name, mime, att.getSizeBytes(), "unsupported type"));
    }

    /**
     * A page range of a stored PDF: a PDF of those pages for a document-capable
     * model, else those pages' text, each behind a header naming the file and the
     * pages. Every failure ends in the pages' text or a note; none sends the whole
     * file.
     */
    private List<Content> processPart(Attachment att, AttachmentSelector selector, String name, String conversationId,
                                      DocumentForwardPlanner.Capabilities capabilities, long[] aggregate,
                                      List<String> extracts, List<String> errors, Set<String> withheld,
                                      EffectiveDocumentsConfig documents) {
        String ref = att.getStorageRef();
        if (withheld.contains(ref) || withheld.contains(rootRefOf(att))) {
            // An input guardrail refused this file on an earlier turn of this
            // conversation; naming part of it again does not release it.
            errors.add("Attachment '" + name + "' was not sent: the file was withheld earlier in this conversation.");
            return List.of();
        }
        String part = ConversationListing.partName(name, selector);
        SelectorResolver.PageRange range;
        try {
            range = SelectorResolver.parsePages(selector.pages());
        } catch (SelectorException e) {
            return List.of(partNote(errors, part, "the page range is missing or invalid (" + e.getMessage() + ")"));
        }

        byte[] root;
        try {
            root = attachmentStore.load(ref, conversationId);
        } catch (IAttachmentStore.AttachmentStoreException e) {
            String msg = "Stored attachment '" + name + "' could not be loaded: " + e.getMessage();
            errors.add(msg);
            return List.of(TextContent.from(msg));
        }

        Sizes sizes = Sizes.beforeSlice(maxForwardBytes, maxAggregateBytes - aggregate[0]);
        Form form = DocumentForwardPlanner.plan(selector, capabilities, sizes, documents);
        String reason = capabilities.documents() ? null : "the model has no native document support";
        if (form == Form.SLICE_NATIVE) {
            try {
                byte[] slice = slice(root, ref, range, name, conversationId, sizes.sliceLimit());
                form = DocumentForwardPlanner.plan(selector, capabilities, sizes.withSlice(slice.length), documents);
                if (form == Form.SLICE_NATIVE) {
                    aggregate[0] += slice.length;
                    return List.of(
                            TextContent.from("[Attachment " + part + " (application/pdf) — only pages " + pagesText(range)
                                    + " of the file, sent as a PDF of those pages]"),
                            PdfFileContent.from(Base64.getEncoder().encodeToString(slice), "application/pdf"));
                }
                reason = sliceTooLarge(sizes.sliceLimit());
            } catch (SliceTooLargeException e) {
                reason = sliceTooLarge(e.maxBytes());
            } catch (UnreadableDocumentException e) {
                LOGGER.debugf("A PDF slice could not be built for conversation='%s': %s",
                        LogSanitizer.sanitize(conversationId), e.getMessage());
                reason = "a PDF of those pages could not be built";
            }
        }
        // A fallback from a PDF is recorded only when the text was actually sent;
        // a part that ends in a note records the note instead.
        boolean fellBack = capabilities.documents();
        return List.of(rangeText(root, ref, range, part, conversationId, extracts, errors, reason, fellBack));
    }

    private static String sliceTooLarge(long limit) {
        if (limit <= 0) {
            return "the attachment budget of this request is used up";
        }
        return "the PDF of those pages is larger than the " + limit + " bytes one file may take in this request";
    }

    /**
     * The slice: the cached {@code PDF_SLICE} when one exists and still fits, else
     * built now and cached. A slice the store does not keep (a per-source cap, any
     * store failure) is still sent.
     *
     * @throws SliceTooLargeException
     *             when it does not fit {@code limit}
     */
    private byte[] slice(byte[] root, String ref, SelectorResolver.PageRange range, String name, String conversationId,
                         long limit) {
        String unitId = "p" + range.text();
        Optional<IAttachmentStore.Attachment> cached = Optional.empty();
        try {
            cached = attachmentStore.findDerived(ref, Derivation.Kind.PDF_SLICE, unitId, conversationId);
        } catch (IAttachmentStore.AttachmentStoreException | RuntimeException e) {
            LOGGER.debugf("No cached PDF slice could be looked up: %s", e.getClass().getSimpleName());
        }
        if (cached != null && cached.isPresent() && cached.get().sizeBytes() > limit) {
            // The slice of these pages is deterministic: building it again cannot make
            // it fit.
            throw new SliceTooLargeException(limit);
        }
        if (cached != null && cached.isPresent()) {
            try {
                byte[] bytes = attachmentStore.load(cached.get().storageRef(), conversationId);
                if (bytes != null && bytes.length > 0 && bytes.length <= limit) {
                    return bytes;
                }
            } catch (IAttachmentStore.AttachmentStoreException | RuntimeException e) {
                LOGGER.debugf("A cached PDF slice could not be loaded; building it again: %s", e.getClass().getSimpleName());
            }
        }
        byte[] bytes = build(root, range, limit);
        try {
            attachmentStore.storeDerived(bytes, "application/pdf", sliceFileName(name, range),
                    Derivation.of(ref, Derivation.Kind.PDF_SLICE, unitId), conversationId);
        } catch (IAttachmentStore.AttachmentDerivedCapException e) {
            LOGGER.debugf("A PDF slice was sent without being cached: %s", e.getMessage());
        } catch (IAttachmentStore.AttachmentStoreException | RuntimeException e) {
            LOGGER.debugf("A PDF slice could not be cached: %s", e.getClass().getSimpleName());
        }
        return bytes;
    }

    private byte[] build(byte[] root, SelectorResolver.PageRange range, long limit) {
        if (limit <= 0) {
            throw new SliceTooLargeException(0);
        }
        Path file = sliceBuilder.slice(root, range.from(), range.to(), limit, workDir);
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new UnreadableDocumentException("The PDF slice could not be read back.", e);
        } finally {
            try {
                Files.deleteIfExists(file);
            } catch (IOException e) {
                LOGGER.warnv("A PDF slice work file could not be deleted: {0}", e.getClass().getSimpleName());
            }
        }
    }

    /**
     * The pages' text behind the part header. The range is checked against the
     * document first: the extractor widens an end page past the last page to the
     * whole rest of the document.
     */
    private Content rangeText(byte[] root, String ref, SelectorResolver.PageRange range, String part, String conversationId,
                              List<String> extracts, List<String> errors, String reason, boolean fellBack) {
        Integer pageCount = pageCount(root, ref, conversationId);
        if (pageCount == null) {
            return partNote(errors, part, "the PDF could not be read");
        }
        if (range.to() > pageCount) {
            return partNote(errors, part, "the file has only " + pageCount + " page(s)");
        }
        try {
            String text = textExtractor.extractPdfText(root, range.from(), range.to(), textExtractor.getDefaultMaxChars());
            if (text == null || text.isBlank()) {
                return partNote(errors, part, "those pages have no extractable text");
            }
            extracts.add(part + ": " + text);
            if (fellBack) {
                errors.add("Attachment '" + part + "' was sent as text: " + reason + ".");
            }
            return TextContent.from("[Attachment " + part + " (application/pdf) — text of pages " + pagesText(range)
                    + " only" + (reason != null ? "; " + reason : "") + "]\n" + text);
        } catch (AttachmentExtractionException e) {
            return partNote(errors, part, "text extraction failed: " + e.getMessage());
        }
    }

    private Integer pageCount(byte[] root, String ref, String conversationId) {
        try {
            Integer known = attachmentStore.pageCount(ref, conversationId);
            if (known != null && known > 0) {
                return known;
            }
        } catch (IAttachmentStore.AttachmentStoreException | RuntimeException e) {
            LOGGER.debugf("The page count of a stored PDF is not recorded: %s", e.getClass().getSimpleName());
        }
        try {
            return textExtractor.extractPdfInfo(root).numberOfPages();
        } catch (AttachmentExtractionException e) {
            return null;
        }
    }

    private static Content partNote(List<String> errors, String part, String reason) {
        String msg = "[Attachment: " + part + " — not forwarded: " + reason + ". No other part of the file was sent instead.]";
        errors.add(msg);
        return TextContent.from(msg);
    }

    private static String pagesText(SelectorResolver.PageRange range) {
        return range.from() == range.to() ? String.valueOf(range.from()) : range.from() + "–" + range.to();
    }

    private static String sliceFileName(String name, SelectorResolver.PageRange range) {
        String base = name.toLowerCase(Locale.ROOT).endsWith(".pdf") ? name.substring(0, name.length() - 4) : name;
        return base + " (p" + range.text() + ").pdf";
    }

    private static String rootRefOf(Attachment att) {
        Map<String, String> metadata = att.getMetadata();
        String root = metadata != null ? metadata.get(AttachmentSelector.META_ROOT) : null;
        return root != null && !root.isBlank() ? root : att.getStorageRef();
    }

    /**
     * Resolve the attachment bytes from its source, enforcing the per-file and
     * running aggregate caps.
     */
    private byte[] resolveBytes(Attachment att, String conversationId, long[] aggregate) throws ForwardSkipException {
        String name = att.getFileName() != null ? att.getFileName() : "unnamed";
        byte[] bytes;
        switch (att.getContentSource()) {
            case STORED -> {
                try {
                    bytes = attachmentStore.load(att.getStorageRef(), conversationId);
                } catch (IAttachmentStore.AttachmentStoreException e) {
                    throw new ForwardSkipException("Stored attachment '" + name + "' could not be loaded: " + e.getMessage());
                }
            }
            case URL -> bytes = download(att.getUrl(), name);
            case BASE64 -> {
                try {
                    bytes = Base64.getDecoder().decode(att.getBase64Data());
                } catch (IllegalArgumentException e) {
                    throw new ForwardSkipException("Attachment '" + name + "' has invalid base64 data.");
                }
            }
            default -> throw new ForwardSkipException("Attachment '" + name + "' has no content.");
        }

        if (bytes.length > maxForwardBytes) {
            throw new ForwardSkipException(("Attachment '%s' (%d bytes) exceeds the per-file forward limit of %d bytes "
                    + "and was not sent. Use the readAttachment tool to access it.")
                    .formatted(name, bytes.length, maxForwardBytes));
        }
        if (aggregate[0] + bytes.length > maxAggregateBytes) {
            throw new ForwardSkipException(("Attachment '%s' skipped: the per-request attachment budget of %d bytes "
                    + "was reached.").formatted(name, maxAggregateBytes));
        }
        aggregate[0] += bytes.length;
        return bytes;
    }

    private byte[] download(String url, String name) throws ForwardSkipException {
        try {
            validateUrl(url);
            OutboundRequest request = OutboundRequest.get(Profile.DEFAULT, URI.create(url)).ssrf(SsrfPolicy.STRICT).build();
            // Bounded at the per-file limit and read as it arrives, so an over-sized
            // or endlessly-streaming URL is aborted rather than buffered whole and
            // measured afterwards. A body that hits the cap comes back truncated,
            // which is the same outcome as exceeding the per-file limit.
            OutboundResponse<byte[]> response = http.send(request, BodyMode.bytes(maxForwardBytes, BodyMode.OnOverflow.TRUNCATE));
            if (response.status() != 200) {
                throw new ForwardSkipException("Attachment '" + name + "' download failed: HTTP " + response.status());
            }
            if (response.truncated()) {
                throw new ForwardSkipException(("Attachment '%s' exceeds the per-file forward limit of %d bytes and was not sent. "
                        + "Use the readAttachment tool to access it.").formatted(name, maxForwardBytes));
            }
            return response.body();
        } catch (ForwardSkipException e) {
            throw e;
        } catch (Exception e) {
            throw new ForwardSkipException("Attachment '" + name + "' could not be fetched: " + e.getMessage());
        }
    }

    private Content extractInline(byte[] bytes, String mime, String name,
                                  List<String> extracts, List<String> errors, String outcome) {
        try {
            String text = textExtractor.extractText(bytes, mime);
            if (text == null || text.isBlank()) {
                return note(errors, name, mime, bytes.length, "no extractable text");
            }
            extracts.add(name + ": " + text);
            return TextContent.from("[Attachment " + name + " (" + mime + ") — " + outcome + "]\n" + text);
        } catch (AttachmentExtractionException e) {
            return note(errors, name, mime, bytes.length, "text extraction failed: " + e.getMessage());
        }
    }

    private Content note(List<String> errors, String name, String mime, long sizeBytes, String reason) {
        String msg = "[Attachment: %s (%s, %d bytes) — not forwarded: %s. Use the readAttachment tool to read it.]"
                .formatted(name, mime, sizeBytes, reason);
        errors.add(msg);
        return TextContent.from(msg);
    }

    private List<Attachment> readAttachments(IConversationMemory memory) {
        // Exact-match read: getLatestData is a prefix scan, and ATTACHMENTS
        // ("attachments")
        // is a prefix of the attachments:extracts / attachments:errors keys this
        // forwarder
        // persists — a prefix read would return the wrong entry on a second forwarder
        // invocation in the same step.
        IData<List<?>> data = memory.getCurrentStep().getData(ATTACHMENTS);
        if (data == null || data.getResult() == null) {
            return List.of();
        }
        // Coerced, not cast: a HITL resume re-enters the same step of a conversation
        // reloaded from the store, where these are plain maps. Casting alone dropped
        // every attachment on a resumed turn.
        return AttachmentContextExtractor.attachmentsFrom(data.getResult());
    }

    private static int lastUserMessageIndex(List<ChatMessage> messages) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) instanceof UserMessage) {
                return i;
            }
        }
        return -1;
    }

    private void persist(IWritableConversationStep step, List<String> extracts, List<String> errors) {
        if (!extracts.isEmpty()) {
            Data<List<String>> data = new Data<>(ATTACHMENT_EXTRACTS.key(), extracts);
            data.setPublic(false);
            step.storeData(data);
        }
        if (!errors.isEmpty()) {
            List<String> merged = new ArrayList<>();
            IData<List<?>> existing = step.getLatestData(ATTACHMENT_ERRORS.key());
            if (existing != null && existing.getResult() != null) {
                existing.getResult().forEach(e -> merged.add(String.valueOf(e)));
            }
            merged.addAll(errors);
            Data<List<String>> data = new Data<>(ATTACHMENT_ERRORS.key(), merged);
            data.setPublic(false);
            step.storeData(data);
        }
    }

    /** Internal signal that a single attachment should be skipped with a note. */
    private static final class ForwardSkipException extends Exception {
        ForwardSkipException(String message) {
            super(message);
        }
    }
}
