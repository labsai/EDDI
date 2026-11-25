/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.impl;

import ai.labs.eddi.engine.attachments.AttachmentSelector;
import ai.labs.eddi.engine.documents.EffectiveDocumentsConfig;

/**
 * Decides in which form a PDF attachment reaches a model — the one place that
 * decision is made, so that later forms (ordered blocks, a card for a large
 * document) are one more branch here and one more dispatch case in
 * {@link AttachmentForwarder}, not another set of conditions spread through it.
 * <p>
 * Pure: no store, no memory, no I/O.
 * <table>
 * <caption>Forms, in the order they are tried</caption>
 * <tr>
 * <th>Attachment</th>
 * <th>Model reads PDFs</th>
 * <th>Form</th>
 * </tr>
 * <tr>
 * <td>whole file (no selector, or a selector without a part)</td>
 * <td>yes</td>
 * <td>{@link Form#WHOLE_NATIVE} — today's path</td>
 * </tr>
 * <tr>
 * <td>whole file</td>
 * <td>no</td>
 * <td>{@link Form#FLAT_TEXT} — today's path</td>
 * </tr>
 * <tr>
 * <td>a page range</td>
 * <td>yes, and the slice fits the per-file and the remaining per-request budget
 * (or is not built yet)</td>
 * <td>{@link Form#SLICE_NATIVE}</td>
 * </tr>
 * <tr>
 * <td>a page range</td>
 * <td>no, or the slice does not fit</td>
 * <td>{@link Form#RANGE_TEXT}</td>
 * </tr>
 * </table>
 * A part is never sent as the whole file: every outcome for a page range is the
 * range, as a PDF or as text.
 *
 * @since 6.8.0
 */
public final class DocumentForwardPlanner {

    private DocumentForwardPlanner() {
    }

    /** How a PDF attachment is sent. */
    public enum Form {
        /** The whole file as a native PDF part. */
        WHOLE_NATIVE,
        /** A PDF of the selected pages only, preceded by a text header. */
        SLICE_NATIVE,
        /** The selected pages' text, under the same header. */
        RANGE_TEXT,
        /** The whole file's text. */
        FLAT_TEXT
    }

    /**
     * What the model can take.
     *
     * @param documents
     *            whether it reads PDFs natively
     */
    public record Capabilities(boolean documents) {
    }

    /**
     * The sizes the decision depends on.
     *
     * @param sliceBytes
     *            the size of the slice when it is known (built or cached), else
     *            {@code null}
     * @param maxForwardBytes
     *            the per-file forward limit
     * @param aggregateRemaining
     *            what is left of the per-request budget for files
     */
    public record Sizes(Long sliceBytes, long maxForwardBytes, long aggregateRemaining) {

        /** Before a slice exists. */
        public static Sizes beforeSlice(long maxForwardBytes, long aggregateRemaining) {
            return new Sizes(null, maxForwardBytes, aggregateRemaining);
        }

        /** The same sizes once the slice is known. */
        public Sizes withSlice(long bytes) {
            return new Sizes(bytes, maxForwardBytes, aggregateRemaining);
        }

        /**
         * The most a slice may be: the per-file limit, or what is left of the request
         * budget when that is less.
         */
        public long sliceLimit() {
            return Math.max(0, Math.min(maxForwardBytes, aggregateRemaining));
        }
    }

    /**
     * @param selector
     *            the attachment's selector; {@code null} for an attachment that is
     *            not blob-backed (inline, URL), which is always a whole file
     * @param capabilities
     *            what the model can take
     * @param sizes
     *            the sizes known so far
     * @param documents
     *            the task's effective {@code documents} configuration, or
     *            {@code null} for the defaults. No key changes the outcome yet;
     *            {@code unitForm} will
     */
    public static Form plan(AttachmentSelector selector, Capabilities capabilities, Sizes sizes,
                            EffectiveDocumentsConfig documents) {
        boolean nativePdf = capabilities != null && capabilities.documents();
        if (!isPart(selector)) {
            return nativePdf ? Form.WHOLE_NATIVE : Form.FLAT_TEXT;
        }
        if (!nativePdf) {
            return Form.RANGE_TEXT;
        }
        if (sizes != null && sizes.sliceBytes() != null && sizes.sliceBytes() > sizes.sliceLimit()) {
            return Form.RANGE_TEXT;
        }
        return Form.SLICE_NATIVE;
    }

    /** Whether {@code selector} names part of a file rather than all of it. */
    public static boolean isPart(AttachmentSelector selector) {
        return selector != null && !selector.isWholeFile();
    }
}
