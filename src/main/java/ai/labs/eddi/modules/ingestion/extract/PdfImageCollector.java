/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.extract;

import org.apache.pdfbox.contentstream.PDFStreamEngine;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.contentstream.operator.state.Concatenate;
import org.apache.pdfbox.contentstream.operator.state.Restore;
import org.apache.pdfbox.contentstream.operator.state.Save;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.util.Matrix;

import java.awt.geom.Point2D;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Objects;

/**
 * Lists the images a range of pages draws, where it draws them, and nothing
 * more: <b>no image is decoded here</b>. Each draw is recorded with its page,
 * position and the handle of the stream that holds its pixels, for
 * {@link ImageNormalizer} to decode within bounds later, while the document is
 * still open.
 *
 * <p>
 * It interprets only what places an image: {@code q}, {@code Q}, {@code cm},
 * {@code Do} and inline images ({@code BI … EI}). Text, paths, colours and
 * fonts are never touched, so no font is loaded and no Type 3 glyph runs. An
 * image XObject is recognised at the COS level, by its {@code /Subtype}, so
 * PDFBox builds no image object for it; an inline image is kept as its
 * dictionary and raw bytes, because PDFBox decodes an inline image the moment
 * one is built.
 *
 * <p>
 * The page program is the file's, so every way it can run long is bounded:
 * <ul>
 * <li>a page's content is charged to the decode budget before it is parsed, as
 * the text path charges it, and an unfiltered form on every draw: PDFBox reads
 * those past its counted filters;</li>
 * <li>form XObjects are followed {@value #MAX_FORM_DEPTH} deep at most, the
 * deadline is checked on each, one PDFBox cannot read is skipped, and a form
 * that draws itself, directly or through others, is not followed again;</li>
 * <li>every operator counts against {@link ImageLimits#maxOperators()} across
 * the whole collection, forms included however often they are drawn — so a
 * shallow tree of forms that each draw the next a hundred times cannot multiply
 * its way past the bound — and the deadline is checked every
 * {@value #OPERATORS_PER_DEADLINE_CHECK} operators;</li>
 * <li>at most {@link ImageLimits#maxImages()} draws are recorded;</li>
 * <li>{@code q} nests at most {@value #MAX_SAVE_DEPTH} deep within a page or a
 * form, each keeping its own count: a saved graphics state is a copy, and a
 * stream of {@code q}s would otherwise copy one per two bytes. A deeper
 * {@code q} and the {@code Q} closing it are ignored, which keeps the read
 * bounded at the price of positions in such a file;</li>
 * <li>an inline image keeps at most {@value #MAX_INLINE_BYTES} bytes, and one
 * collection at most {@value #MAX_INLINE_TOTAL_BYTES} bytes of inline images in
 * all; past that the draw is still recorded, without its bytes.</li>
 * </ul>
 * Past the operator or image cap the collection stops and
 * {@link Collection#truncated()} says so; past the deadline it throws, as every
 * PDF read does.
 *
 * <p>
 * Stateless apart from the one collection it runs, and single-threaded: the
 * decode budget that bounds the form streams it parses is armed on the calling
 * thread. Never hand it to an executor.
 */
public final class PdfImageCollector {

    /** How deep form XObjects are followed. */
    static final int MAX_FORM_DEPTH = 8;

    /** How deep {@code q} may nest. */
    static final int MAX_SAVE_DEPTH = 64;

    /** How many operators run between deadline checks. */
    static final int OPERATORS_PER_DEADLINE_CHECK = 1024;

    /** Most bytes one inline image keeps. */
    static final int MAX_INLINE_BYTES = 1024 * 1024;

    /** Most bytes of inline images one collection keeps in all. */
    static final long MAX_INLINE_TOTAL_BYTES = 16L * 1024 * 1024;

    private static final String DO = "Do";
    private static final String BEGIN_INLINE_IMAGE = "BI";
    private static final String SAVE = "q";
    private static final String RESTORE = "Q";

    private PdfImageCollector() {
    }

    /**
     * What a collection found.
     *
     * @param images
     *            every recorded draw, by page, then in drawing order
     * @param truncated
     *            whether the operator or image cap stopped the collection before
     *            the last page was finished
     */
    public record Collection(List<Placement> images, boolean truncated) {
        public Collection {
            images = Collections.unmodifiableList(images);
        }
    }

    /**
     * One draw of one image: where it lands on the page, and what holds its pixels.
     * The same image drawn twice is two placements over the same stream.
     *
     * <p>
     * Positions are in PDF user units relative to the page's crop box, measured
     * from its left and its <b>top</b> edge, as text positions are; a rotated or
     * skewed draw gives its bounding box. The page's own {@code /Rotate} is not
     * applied.
     */
    public static final class Placement {
        private final int page;
        private final String imageId;
        private final float x;
        private final float y;
        private final float width;
        private final float height;
        private final COSDictionary dictionary;
        private final COSStream stream;
        private final byte[] inlineData;
        private final PDResources resources;

        private Placement(int page, String imageId, float[] box, COSDictionary dictionary, COSStream stream,
                byte[] inlineData, PDResources resources) {
            this.page = page;
            this.imageId = imageId;
            this.x = box[0];
            this.y = box[1];
            this.width = box[2];
            this.height = box[3];
            this.dictionary = dictionary;
            this.stream = stream;
            this.inlineData = inlineData;
            this.resources = resources;
        }

        /** 1-based page number. */
        public int page() {
            return page;
        }

        /**
         * {@code i-<page>-<n>}, {@code n} counting this page's draws from 1 in drawing
         * order.
         */
        public String imageId() {
            return imageId;
        }

        /** Left edge, from the crop box's left. */
        public float x() {
            return x;
        }

        /** Top edge, from the crop box's top. */
        public float y() {
            return y;
        }

        /** Width on the page, in user units. */
        public float width() {
            return width;
        }

        /** Height on the page, in user units. */
        public float height() {
            return height;
        }

        /** Whether this is an inline image ({@code BI … EI}) rather than an XObject. */
        public boolean inline() {
            return stream == null;
        }

        /**
         * The width the image declares, in pixels, or {@code -1} when it declares none
         * or not a number. What the file <i>says</i>: nothing has been decoded.
         */
        public long declaredWidth() {
            return declared(COSName.WIDTH, COSName.W);
        }

        /** The height the image declares, as {@link #declaredWidth()}. */
        public long declaredHeight() {
            return declared(COSName.HEIGHT, COSName.H);
        }

        private long declared(COSName name, COSName abbreviation) {
            COSBase value = dictionary.getDictionaryObject(name, abbreviation);
            return value instanceof COSNumber number ? number.longValue() : -1;
        }

        /** The image dictionary (an inline image's parameters). */
        COSDictionary dictionary() {
            return dictionary;
        }

        /** The XObject stream, or null for an inline image. */
        COSStream stream() {
            return stream;
        }

        /**
         * An inline image's raw (still encoded) bytes; null for an XObject, and for an
         * inline image over the inline caps.
         */
        byte[] inlineData() {
            return inlineData;
        }

        /** The resources in force where the image was drawn. */
        PDResources resources() {
            return resources;
        }
    }

    /**
     * Lists the images pages {@code firstPage..lastPage} draw. The document must be
     * open, and the decode budget armed on this thread, for as long as the
     * placements are used.
     *
     * @param firstPage
     *            1-based, inclusive
     * @param lastPage
     *            1-based, inclusive, at most the document's page count
     * @throws IllegalArgumentException
     *             when the range is not within the document
     * @throws UnreadableDocumentException
     *             when the deadline passes
     */
    public static Collection collect(PDDocument document, int firstPage, int lastPage, ImageLimits limits,
                                     Instant deadline)
            throws IOException {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(deadline, "deadline");
        ImageLimits bounds = limits == null ? ImageLimits.defaults() : limits;
        int pageCount = document.getNumberOfPages();
        if (firstPage < 1 || lastPage < firstPage || lastPage > pageCount) {
            throw new IllegalArgumentException(
                    "Pages " + firstPage + ".." + lastPage + " are not within 1.." + pageCount);
        }
        var engine = new Engine(bounds, deadline);
        for (int page = firstPage; page <= lastPage; page++) {
            requireBefore(deadline);
            if (!engine.process(document.getPage(page - 1), page)) {
                break;
            }
        }
        PdfDecodeBudget.requireNotExceeded();
        return new Collection(engine.images, engine.truncated);
    }

    private static void requireBefore(Instant deadline) {
        if (Instant.now().isAfter(deadline) || Thread.currentThread().isInterrupted()) {
            throw new UnreadableDocumentException(BoundedTextStripper.TOO_LONG);
        }
    }

    /** Thrown to unwind one page's program once a cap is reached. */
    private static final class Stop extends RuntimeException {
        Stop() {
            super("image collection cap reached", null, false, false);
        }
    }

    /**
     * One collection's interpreter: the operators that place an image, nothing
     * else.
     */
    private static final class Engine extends PDFStreamEngine {
        private final ImageLimits limits;
        private final Instant deadline;
        private final List<Placement> images = new ArrayList<>();
        /** The forms being drawn, innermost last: the depth and the cycle guard. */
        private final Deque<COSStream> forms = new ArrayDeque<>();
        private long operators;
        private long inlineBytes;
        private int ignoredSaves;
        private int pageNumber;
        private int drawsOnPage;
        private boolean truncated;

        Engine(ImageLimits limits, Instant deadline) {
            this.limits = limits;
            this.deadline = deadline;
            addOperator(new Save(this));
            addOperator(new Restore(this));
            addOperator(new Concatenate(this));
        }

        /** @return false once a cap stopped the collection */
        boolean process(PDPage page, int number) throws IOException {
            pageNumber = number;
            drawsOnPage = 0;
            ignoredSaves = 0;
            forms.clear();
            try {
                // Page content PDFBox reads without a counted filter (unfiltered, or a
                // single Flate stream it inflates on the fly) is charged up front, as the
                // text path charges it: no page program is parsed outside the budget
                BoundedTextStripper.chargePageContents(page);
                processPage(page);
                return true;
            } catch (Stop stop) {
                truncated = true;
                return false;
            }
        }

        @Override
        protected void processOperator(Operator operator, List<COSBase> operands) throws IOException {
            operators++;
            if (operators % OPERATORS_PER_DEADLINE_CHECK == 0 || Thread.currentThread().isInterrupted()) {
                requireBefore(deadline);
            }
            if (operators > limits.maxOperators()) {
                throw new Stop();
            }
            switch (operator.getName()) {
                case DO -> drawObject(operands);
                case BEGIN_INLINE_IMAGE -> inlineImage(operator);
                case SAVE -> save(operator, operands);
                case RESTORE -> restore(operator, operands);
                default -> super.processOperator(operator, operands);
            }
        }

        private void save(Operator operator, List<COSBase> operands) throws IOException {
            if (getGraphicsStackSize() >= MAX_SAVE_DEPTH) {
                ignoredSaves++;
                return;
            }
            super.processOperator(operator, operands);
        }

        private void restore(Operator operator, List<COSBase> operands) throws IOException {
            if (ignoredSaves > 0) {
                ignoredSaves--;
                return;
            }
            super.processOperator(operator, operands);
        }

        private void drawObject(List<COSBase> operands) throws IOException {
            if (operands.isEmpty() || !(operands.getFirst() instanceof COSName name)) {
                return;
            }
            PDResources resources = getResources();
            if (resources == null) {
                return;
            }
            COSDictionary xObjects = resources.getCOSObject().getCOSDictionary(COSName.XOBJECT);
            if (xObjects == null || !(xObjects.getDictionaryObject(name) instanceof COSStream stream)) {
                return;
            }
            COSName subtype = stream.getCOSName(COSName.SUBTYPE);
            if (COSName.IMAGE.equals(subtype)) {
                record(stream, stream, null, resources);
            } else if (COSName.FORM.equals(subtype)) {
                drawForm(stream);
            }
        }

        private void drawForm(COSStream stream) throws IOException {
            if (forms.size() >= MAX_FORM_DEPTH || containsSame(forms, stream)) {
                return;
            }
            // Every draw of a form parses it again: the time is checked and an unfiltered
            // form charged on each one (a filtered form is counted as its filter decodes)
            requireBefore(deadline);
            if (isUnfiltered(stream)) {
                PdfDecodeBudget.charge(stream.getLength());
            }
            forms.addLast(stream);
            // A form starts with a graphics stack of its own, so it gets its own count of
            // ignored saves: one of the page's must not swallow the form's Q
            int outerIgnoredSaves = ignoredSaves;
            ignoredSaves = 0;
            try {
                showForm(new PDFormXObject(new PDStream(stream)));
            } catch (IOException malformed) {
                // As PDFBox does for Do: a form that cannot be read is skipped, not the page.
                // The budget and the deadline are unchecked exceptions and pass through; a
                // budget
                // passed inside PDFBox and reported as an IOException still refuses the
                // document
                PdfDecodeBudget.requireNotExceeded();
            } finally {
                ignoredSaves = outerIgnoredSaves;
                forms.removeLast();
            }
        }

        /**
         * Whether PDFBox reads the stream as stored, which no counted filter sees: a
         * {@code /Filter} that is neither a name nor a non-empty array counts as none.
         */
        private static boolean isUnfiltered(COSStream stream) {
            COSBase filters = stream.getFilters();
            return !(filters instanceof COSName || (filters instanceof COSArray list && list.size() > 0));
        }

        private void inlineImage(Operator operator) {
            COSDictionary parameters = operator.getImageParameters();
            if (parameters == null) {
                return;
            }
            byte[] data = operator.getImageData();
            byte[] kept = null;
            if (data != null && data.length <= MAX_INLINE_BYTES && inlineBytes + data.length <= MAX_INLINE_TOTAL_BYTES) {
                kept = data;
                inlineBytes += data.length;
            }
            record(parameters, null, kept, getResources());
        }

        private void record(COSDictionary dictionary, COSStream stream, byte[] inlineData, PDResources resources) {
            if (images.size() >= limits.maxImages()) {
                throw new Stop();
            }
            drawsOnPage++;
            images.add(new Placement(pageNumber, "i-" + pageNumber + "-" + drawsOnPage, box(), dictionary, stream,
                    inlineData, resources));
        }

        /**
         * Where the unit square lands under the current transformation: x, y from the
         * crop box's top-left, width, height.
         */
        private float[] box() {
            Matrix ctm = getGraphicsState().getCurrentTransformationMatrix();
            float minX = Float.POSITIVE_INFINITY;
            float minY = Float.POSITIVE_INFINITY;
            float maxX = Float.NEGATIVE_INFINITY;
            float maxY = Float.NEGATIVE_INFINITY;
            for (int corner = 0; corner < 4; corner++) {
                Point2D.Float point = ctm.transformPoint(corner & 1, corner >> 1);
                minX = Math.min(minX, point.x);
                maxX = Math.max(maxX, point.x);
                minY = Math.min(minY, point.y);
                maxY = Math.max(maxY, point.y);
            }
            PDRectangle crop = getCurrentPage().getCropBox();
            return new float[]{minX - crop.getLowerLeftX(), crop.getUpperRightY() - maxY, maxX - minX, maxY - minY};
        }

        private static boolean containsSame(Deque<COSStream> forms, COSStream stream) {
            for (COSStream form : forms) {
                if (form == stream) {
                    return true;
                }
            }
            return false;
        }
    }
}
