/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.ingestion.extract;

import ai.labs.eddi.modules.ingestion.extract.PageLayout.Line;
import ai.labs.eddi.modules.ingestion.extract.document.Block;
import ai.labs.eddi.modules.ingestion.extract.document.BlockOptions;
import ai.labs.eddi.modules.ingestion.extract.document.DocumentManifest;
import ai.labs.eddi.modules.ingestion.extract.document.DocumentUnit;
import ai.labs.eddi.modules.ingestion.extract.document.SplitConfig;
import ai.labs.eddi.modules.ingestion.extract.document.SplitStrategy;
import ai.labs.eddi.modules.ingestion.extract.document.TextBlock;
import ai.labs.eddi.modules.ingestion.extract.document.UnitBudgetFitter;
import ai.labs.eddi.modules.ingestion.extract.document.UnitBudgetFitter.Section;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.io.MemoryUsageSetting;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Reads a PDF's structure — its pages, its outline, roughly how much text each
 * page holds — and cuts it into units: a {@link DocumentManifest}. Stateless;
 * nothing is stored, and no text leaves it.
 *
 * <h2>Bounded like the text extractor</h2>
 * <p>
 * The same defences as {@link PdfTextExtractor}, in the same order: the
 * {@link PdfStreamBudget} scan of the raw bytes, then a {@link PdfDecodeBudget}
 * armed for the whole read, checked again when the read is done however it
 * ended; the parser's scratch space capped; every page's program checked
 * against the deadline and its glyphs capped. <b>The decode budget lives on the
 * calling thread</b>: nothing here hands work to another thread, and whatever
 * later does must open a budget there.
 *
 * <p>
 * PDFBox's own outline and name-tree helpers are not used. They follow
 * {@code /Parent} chains and recurse into {@code /Kids} without remembering
 * where they have been, so a file whose tree refers back to itself loops or
 * overflows the stack. Both are walked here instead, iteratively, each object
 * visited once, with a cap on how many, and the deadline checked every
 * {@value #OBJECTS_PER_DEADLINE_CHECK} objects.
 *
 * <p>
 * The page tree cannot be avoided the same way: PDFBox's page iterator, which
 * text extraction uses, recurses once per branch it enters and builds every
 * page object up front. So the tree is first walked here exactly as the
 * iterator will walk it — kid by kid, with its rule for branches seen before —
 * but with an explicit stack, and refused when the iterator would recurse
 * deeper than {@value #MAX_PAGE_TREE_DEPTH} levels or build more than
 * {@value #MAX_PAGES} pages. A tree that lists one page twice is refused too:
 * the iterator would yield that page twice, and the page numbers here and those
 * the text engine counts would disagree. One recursion is out of reach: when
 * the cross-reference table is broken, the parser rebuilds it and walks the
 * page tree recursively before this code has the document. A
 * {@link StackOverflowError} from there — or from anywhere else in the read —
 * is caught at the boundary and reported as an unreadable file.
 *
 * <h2>The cut</h2>
 * <ul>
 * <li>{@link SplitStrategy#OUTLINE}: the outline's top-level entries, and the
 * entries one level below them as their parts. Deeper levels are not units.
 * When the outline has a single top-level entry, its children are taken as the
 * top level. Entries are ordered by the page they point to; entries pointing to
 * the same page as an earlier one, or to no page, are skipped. A chapter runs
 * to the page before the next one starts — a chapter that starts mid-page owns
 * that page — and pages before the first chapter form a unit of their own. The
 * first part of a chapter starts where the chapter starts. An outline is usable
 * when two or more top-level entries point to different pages of the document —
 * the front-matter unit does not count; otherwise the document is cut by
 * pages.</li>
 * <li>{@link SplitStrategy#HEADINGS}: headings found by font size
 * ({@link PageLayout}): on each page, a short line set at least
 * {@value PageLayout#HEADING_RATIO} times the page's body size. Heading sizes
 * are grouped into levels, a level holding sizes within 5 % of its largest; the
 * first level — largest first — with headings on two or more pages makes the
 * chapters, and the level below it their parts. A larger heading that occurs on
 * one page only, a title page's title say, starts nothing. The chapters are
 * then placed exactly as outline entries are. Fewer than two chapters: cut by
 * pages.</li>
 * <li>{@link SplitStrategy#PAGES}: windows of
 * {@link SplitConfig#pagesPerUnit()} pages.</li>
 * <li>{@link SplitStrategy#AUTO}: the outline when usable, else the headings,
 * else pages. A document with no text in the pages measured is cut by pages: a
 * scan has no estimates to fit, and pages are what its reader can name.</li>
 * </ul>
 * Then {@link UnitBudgetFitter} fits the units to the budget. The estimate for
 * a page is its laid-out characters divided by four; pages past
 * {@link ExtractionLimits#maxParts()} are not read, get the average of those
 * that were, and are not searched for headings.
 *
 * <p>
 * A page whose body text stands in columns is read top to bottom all the same,
 * so lines of different columns alternate; the manifest names such pages in a
 * warning.
 *
 * <h2>Blocks</h2>
 * <p>
 * {@link #blocks} reads one unit's pages again, under the same bounds, as
 * {@link TextBlock}s in reading order — page, then top to bottom, then left to
 * right — a heading a block of its own, consecutive body lines one block. When
 * a top-level unit's title heading stands below other text on its first page,
 * the unit started mid-page: a note block first says that the text above the
 * heading belongs to the unit before. Parts get no such note.
 *
 * <h2>Files too large for memory</h2>
 * <p>
 * {@link #manifest(Path, String, SplitConfig, ExtractionLimits, PdfWindowReader.Options, int)}
 * and
 * {@link #blocks(Path, DocumentUnit, BlockOptions, ExtractionLimits, PdfWindowReader.Options, int)}
 * read a file on disk through {@link PdfWindowReader}, a window of pages at a
 * time, the document opened afresh for each: the same scan, the same cut and
 * the same blocks as the array overloads, from shared code, but with the decode
 * budget and the deadline applying <b>per window</b> rather than to the whole
 * read. The raw-byte scan runs over the file through
 * {@link PdfStreamBudgetFile}; the SHA-256 is computed as the file streams
 * through it. No byte array of the whole file is made.
 *
 * <p>
 * Images, tables and audio are not counted yet; their counts are zero. Warnings
 * never quote the document: outline titles and headings are data and go only
 * into the units' titles and the blocks, cleaned of control characters and cut
 * to length.
 */
@ApplicationScoped
public class PdfStructureExtractor {

    /** Outline entries read at most; the rest are ignored, with a warning. */
    static final int MAX_OUTLINE_ITEMS = 2_000;

    /** Named-destination entries and name-tree nodes visited at most. */
    static final int MAX_NAMED_DESTINATIONS = 50_000;

    /**
     * Levels of {@code /Pages} nodes a page tree may have. Real files have a
     * handful; PDFBox's page iterator recurses once per level.
     */
    static final int MAX_PAGE_TREE_DEPTH = 256;

    /**
     * Pages a document may have. PDFBox builds an object per page, and a page can
     * be a {@code null} in a {@code /Kids} array — a few decoded bytes that become
     * a dictionary, a queue entry and a map entry here — so the decode budget,
     * which bounds the bytes, does not bound the heap. Fifty times what the large
     * document work is planned for.
     */
    static final int MAX_PAGES = 250_000;

    /** How many objects a walk visits between deadline checks. */
    private static final int OBJECTS_PER_DEADLINE_CHECK = 64;

    private static final COSName GO_TO = COSName.getPDFName("GoTo");

    /** Bytes of a file the raw-byte scan is given one window's deadline for. */
    static final long SCAN_BYTES_PER_WINDOW_DEADLINE = 64L * 1024 * 1024;

    /** Headings collected at most; later ones are ignored, with a warning. */
    static final int MAX_HEADINGS = MAX_OUTLINE_ITEMS;

    /** Sizes within this fraction of a level's largest belong to that level. */
    private static final float SAME_LEVEL = 0.95f;

    /** Pages a warning lists by number at most. */
    private static final int MAX_PAGES_NAMED = 20;

    /**
     * Glyphs one page may lay out beyond the characters still wanted, as in
     * {@link PdfTextExtractor}.
     */
    private static final int GLYPH_SLACK = 20_000;

    /**
     * Two body lines belong to one block when the second's top is at most this many
     * font sizes below the first's: ordinary leading, not a blank line.
     */
    private static final float PARAGRAPH_LEADING = 2f;

    static final String WARNING_NO_OUTLINE = "This PDF has no usable outline, so it was cut into page windows.";
    static final String WARNING_NO_HEADINGS = "No headings were found on two or more pages of this PDF, so it was "
            + "cut into page windows.";
    static final String WARNING_NO_OUTLINE_OR_HEADINGS = "This PDF has no usable outline and no headings were "
            + "found on two or more pages, so it was cut into page windows.";
    static final String WARNING_CUT_BY_HEADINGS = "This PDF has no usable outline, so it was cut at headings found "
            + "by their font size; a heading set like body text is not found.";
    static final String WARNING_HEADINGS_TRUNCATED = "More than " + MAX_HEADINGS + " headings were found; only the "
            + "first " + MAX_HEADINGS + " were used.";
    static final String WARNING_OUTLINE_LOOP = "The outline refers back to itself; entries were read once each.";
    static final String WARNING_OUTLINE_TRUNCATED = "The outline has more than " + MAX_OUTLINE_ITEMS
            + " entries; only the first " + MAX_OUTLINE_ITEMS + " were read.";
    static final String WARNING_NAMES_TRUNCATED = "The PDF names more destinations than are read; outline "
            + "entries pointing past them were skipped.";

    static final String FRONT_MATTER = "Front matter";

    /**
     * The warning for a document with no text in the pages measured — all of them,
     * or the first {@link ExtractionLimits#maxParts()}.
     */
    static String noTextWarning(int measured, int pageCount) {
        String where = measured < pageCount ? "in the first " + measured + " of its " + pageCount + " pages" : "";
        return "This PDF has no text layer" + (where.isEmpty() ? "" : " " + where)
                + " (a scan, perhaps), so it was cut into page windows and its estimates are zero.";
    }

    /**
     * The warning for pages whose text stands in columns, naming at most
     * {@value #MAX_PAGES_NAMED} pages, runs of them as ranges.
     */
    static String multiColumnWarning(List<Integer> pages) {
        List<Integer> shown = pages.subList(0, Math.min(pages.size(), MAX_PAGES_NAMED));
        var named = new StringBuilder();
        for (int i = 0; i < shown.size(); i++) {
            int first = shown.get(i);
            while (i + 1 < shown.size() && shown.get(i + 1) == shown.get(i) + 1) {
                i++;
            }
            int last = shown.get(i);
            if (!named.isEmpty()) {
                named.append(", ");
            }
            named.append(first == last ? String.valueOf(first) : first + "–" + last);
        }
        int more = pages.size() - shown.size();
        return "Text in columns was found on " + (pages.size() == 1 ? "page " : "pages ") + named
                + (more > 0 ? " and " + more + " more" : "") + "; it is read top to bottom across the columns, "
                + "so their lines alternate.";
    }

    /**
     * @param pdf
     *            the file, whole
     * @param rootRef
     *            the storage reference the manifest describes; carried, not used
     * @param split
     *            how to cut it; null for {@link SplitConfig#defaults()}
     * @param limits
     *            what reading it may cost; null for
     *            {@link ExtractionLimits#defaults()}
     * @throws UnreadableDocumentException
     *             when the file is encrypted, corrupt, has no pages, or costs more
     *             than {@code limits} allow — the caller then handles it as it
     *             handles any PDF today
     */
    public DocumentManifest manifest(byte[] pdf, String rootRef, SplitConfig split, ExtractionLimits limits) {
        return manifest(pdf, rootRef, split, limits, true);
    }

    /**
     * @param preFilter
     *            whether to run the raw-byte scan first. Off only in tests that
     *            show the decode budget holds on its own
     */
    DocumentManifest manifest(byte[] pdf, String rootRef, SplitConfig split, ExtractionLimits limits,
                              boolean preFilter) {
        if (pdf == null) {
            throw new UnreadableDocumentException("This file could not be read as a PDF.");
        }
        SplitConfig config = split == null ? SplitConfig.defaults() : split;
        ExtractionLimits bounds = limits == null ? ExtractionLimits.defaults() : limits;
        Structure structure = guarded(pdf, bounds, preFilter, (document, pageNumbers, deadline) -> {
            int pageCount = pageNumbers.size();
            var scanner = new PageScanner(pageCount);
            scanner.scan(document, 1, scanner.measured(bounds), bounds, deadline);
            Set<String> warnings = new LinkedHashSet<>();
            PageScan scan = scanner.finish(bounds, warnings);
            return structure(scan, pageCount, config, bounds, warnings,
                    found -> new OutlineReader(document, pageNumbers, pageCount, deadline, found).sections());
        });
        return fitted(structure, rootRef, sha256(pdf), config);
    }

    /**
     * {@link #manifest(byte[], String, SplitConfig, ExtractionLimits)} for a file
     * on disk, read a window of pages at a time: the same manifest from the same
     * bytes, without the file or the whole document on the heap.
     *
     * <p>
     * Three kinds of pass, each its own open of the document: one numbers the
     * pages; one per window of {@code windowPages} measures its pages; one reads
     * the outline, when the cut needs it. Each runs under {@code reading}'s decode
     * budget and deadline <b>per window</b>; {@code limits} decides what is
     * measured and the per-stream bound of the raw-byte scan, as it does for an
     * array, and its {@link ExtractionLimits#maxDuration()} is not used.
     *
     * @param windowPages
     *            pages per window, at least 1
     * @throws UnreadableDocumentException
     *             as the array overload does, and when a window exceeds its bounds
     */
    public DocumentManifest manifest(Path pdf, String rootRef, SplitConfig split, ExtractionLimits limits,
                                     PdfWindowReader.Options reading, int windowPages) {
        SplitConfig config = split == null ? SplitConfig.defaults() : split;
        ExtractionLimits bounds = limits == null ? ExtractionLimits.defaults() : limits;
        var reader = new PdfWindowReader(reading);
        int pageCount = prepare(pdf, bounds, reader, windowPages, true);
        var scanner = new PageScanner(pageCount);
        int measured = scanner.measured(bounds);
        for (long first = 1; first <= measured; first += windowPages) {
            int last = (int) Math.min(measured, first + windowPages - 1);
            reader.withWindow(pdf, (int) first, last, (document, window) -> {
                scanner.scan(document, window.firstPage(), window.lastPage(), bounds, window.deadline());
                return null;
            });
        }
        Set<String> warnings = new LinkedHashSet<>();
        PageScan scan = scanner.finish(bounds, warnings);
        Structure structure = structure(scan, pageCount, config, bounds, warnings,
                found -> reader.withDocument(pdf, (document, window) -> {
                    Map<COSDictionary, Integer> pageNumbers = numberPages(document, window.deadline());
                    if (pageNumbers.size() != pageCount) {
                        throw new UnreadableDocumentException("This PDF changed while it was read.");
                    }
                    return new OutlineReader(document, pageNumbers, pageCount, window.deadline(), found).sections();
                }));
        return fitted(structure, rootRef, sha256(pdf), config);
    }

    /**
     * Fits the sections to the budget, outside any read: the fitting is pure, and a
     * fault in it is this code's, not the file's — it must not be reported as an
     * unreadable PDF.
     */
    private static DocumentManifest fitted(Structure structure, String rootRef, String sha256, SplitConfig config) {
        UnitBudgetFitter.Result fitted = UnitBudgetFitter.fit(structure.sections(), structure.tokens(), config);
        Set<String> warnings = new LinkedHashSet<>(structure.warnings());
        warnings.addAll(fitted.warnings());
        return new DocumentManifest(rootRef, sha256, structure.tokens().length, structure.used(), fitted.units(),
                List.of(), List.copyOf(warnings));
    }

    /**
     * What every read of a file begins with: the raw-byte scan over the file, then
     * a pass that numbers the pages — refusing a tree that lists a page twice or
     * none — and returns how many there are.
     */
    private static int prepare(Path pdf, ExtractionLimits bounds, PdfWindowReader reader, int windowPages,
                               boolean preScan) {
        if (pdf == null) {
            throw new UnreadableDocumentException("This file could not be read as a PDF.");
        }
        if (windowPages < 1) {
            throw new IllegalArgumentException("windowPages must be at least 1");
        }
        if (preScan) {
            preScan(pdf, bounds, reader.options());
        }
        return reader.withDocument(pdf, (document, window) -> {
            int pages = numberPages(document, window.deadline()).size();
            if (pages == 0) {
                throw new UnreadableDocumentException("This PDF has no pages.");
            }
            return pages;
        });
    }

    /**
     * The raw-byte scan over a file, as
     * {@link #manifest(Path, String, SplitConfig, ExtractionLimits, PdfWindowReader.Options, int)}
     * runs it first: once per file is enough.
     *
     * @throws UnreadableDocumentException
     *             when a compressed stream expands past
     *             {@link ExtractionLimits#maxUncompressedBytes()}
     */
    public static void preScan(Path pdf, ExtractionLimits limits, PdfWindowReader.Options reading) {
        ExtractionLimits bounds = limits == null ? ExtractionLimits.defaults() : limits;
        PdfStreamBudgetFile.requireWithin(pdf, bounds.maxUncompressedBytes(), scanDeadline(pdf, reading));
    }

    /**
     * The raw-byte scan's deadline: one window's for every
     * {@value #SCAN_BYTES_PER_WINDOW_DEADLINE} bytes of the file, at least one.
     */
    private static Instant scanDeadline(Path pdf, PdfWindowReader.Options reading) {
        long size;
        try {
            size = Files.size(pdf);
        } catch (IOException e) {
            throw new UnreadableDocumentException("This file could not be read as a PDF.", e);
        }
        long windows = Math.max(1, (size + SCAN_BYTES_PER_WINDOW_DEADLINE - 1) / SCAN_BYTES_PER_WINDOW_DEADLINE);
        return Instant.now().plus(reading.windowDeadline().multipliedBy(windows));
    }

    /**
     * One unit's content in reading order: its pages' text as {@link TextBlock}s,
     * headings as blocks of their own. Read under the same bounds as
     * {@link #manifest}.
     *
     * <p>
     * At most {@link ExtractionLimits#maxParts()} pages and about
     * {@link ExtractionLimits#maxCharacters()} characters are read — a page is kept
     * whole, so the last one may run a little past it; when either stops the read
     * before the unit's last page, a note block at the end says so. A note block is
     * not a heading and quotes nothing but the document's own headings.
     *
     * @param unit
     *            a unit of this file's manifest; its pages are what is read
     * @param options
     *            what to extract beyond text; null for
     *            {@link BlockOptions#textOnly()}
     * @param limits
     *            what reading it may cost; null for
     *            {@link ExtractionLimits#defaults()}
     * @throws UnreadableDocumentException
     *             as {@link #manifest} does, and when the unit's pages are not in
     *             this file
     */
    public List<Block> blocks(byte[] pdf, DocumentUnit unit, BlockOptions options, ExtractionLimits limits) {
        return blocks(pdf, unit, options, limits, true);
    }

    /**
     * @param preFilter
     *            whether to run the raw-byte scan first. Off only in tests that
     *            show the decode budget holds on its own
     */
    List<Block> blocks(byte[] pdf, DocumentUnit unit, BlockOptions options, ExtractionLimits limits,
                       boolean preFilter) {
        Objects.requireNonNull(unit, "unit");
        if (unit.firstPage() < 1 || unit.lastPage() < unit.firstPage()) {
            throw new IllegalArgumentException("A unit covers pages first..last, first >= 1");
        }
        if (pdf == null) {
            throw new UnreadableDocumentException("This file could not be read as a PDF.");
        }
        ExtractionLimits bounds = limits == null ? ExtractionLimits.defaults() : limits;
        return guarded(pdf, bounds, preFilter, (document, pageNumbers, deadline) -> {
            var read = new BlockRead(unit, pageNumbers.size(), bounds);
            read.pages(document, unit.firstPage(), read.last(), deadline);
            return read.finish();
        });
    }

    /**
     * {@link #blocks(byte[], DocumentUnit, BlockOptions, ExtractionLimits)} for a
     * file on disk, read a window of {@code windowPages} pages at a time under
     * {@code reading}'s per-window bounds: the same blocks from the same bytes. The
     * windows stop once the unit's character allowance is spent.
     *
     * @throws UnreadableDocumentException
     *             as the array overload does, and when a window exceeds its bounds
     */
    public List<Block> blocks(Path pdf, DocumentUnit unit, BlockOptions options, ExtractionLimits limits,
                              PdfWindowReader.Options reading, int windowPages) {
        return blocks(pdf, unit, options, limits, reading, windowPages, true);
    }

    /**
     * @param preScan
     *            whether to run the raw-byte scan over the file first. A caller
     *            that reads many units of one file whose scan it has already run —
     *            its manifest was just built from it, or {@link #preScan} passed —
     *            passes {@code false}, rather than reading the whole file again per
     *            unit. The decode budget still bounds every window.
     */
    public List<Block> blocks(Path pdf, DocumentUnit unit, BlockOptions options, ExtractionLimits limits,
                              PdfWindowReader.Options reading, int windowPages, boolean preScan) {
        Objects.requireNonNull(unit, "unit");
        if (unit.firstPage() < 1 || unit.lastPage() < unit.firstPage()) {
            throw new IllegalArgumentException("A unit covers pages first..last, first >= 1");
        }
        ExtractionLimits bounds = limits == null ? ExtractionLimits.defaults() : limits;
        var reader = new PdfWindowReader(reading);
        int pageCount = prepare(pdf, bounds, reader, windowPages, preScan);
        var read = new BlockRead(unit, pageCount, bounds);
        for (long first = unit.firstPage(); first <= read.last() && !read.stopped(); first += windowPages) {
            int last = (int) Math.min(read.last(), first + windowPages - 1);
            reader.withWindow(pdf, (int) first, last, (document, window) -> {
                read.pages(document, window.firstPage(), window.lastPage(), window.deadline());
                return null;
            });
        }
        return read.finish();
    }

    /**
     * What reading the document found: the sections to fit and the page estimates.
     */
    private record Structure(List<Section> sections, int[] tokens, SplitStrategy used, Set<String> warnings) {
    }

    /** Work on an opened, checked document. */
    @FunctionalInterface
    private interface DocumentWork<T> {
        T run(PDDocument document, Map<COSDictionary, Integer> pageNumbers, Instant deadline) throws IOException;
    }

    /**
     * The defences every read goes through, in order: the raw-byte scan, the decode
     * budget armed on this thread for the whole read and checked again however it
     * ended, the parser's scratch space capped, encryption refused, the page tree
     * walked ahead of PDFBox and its pages numbered.
     */
    private static <T> T guarded(byte[] pdf, ExtractionLimits bounds, boolean preFilter, DocumentWork<T> work) {
        Instant deadline = Instant.now().plus(bounds.maxDuration());
        if (preFilter) {
            PdfStreamBudget.requireWithin(pdf, bounds.maxUncompressedBytes(), deadline);
        }
        T result;
        try (var budget = PdfDecodeBudget.open(bounds.maxDecodedBytes())) {
            try {
                result = open(pdf, bounds, deadline, work);
            } catch (StackOverflowError e) {
                // Last resort, for the one recursion this code cannot walk ahead of: the
                // parser's own page-tree check while it rebuilds a broken cross-reference
                // table. The overflow has unwound by here; nothing is left half-built.
                throw new UnreadableDocumentException("This PDF's structure is nested too deeply to be read.");
            } catch (UnreadableDocumentException e) {
                if (budget.exceeded()) {
                    throw tooMuchToDecode(budget);
                }
                throw e;
            }
            if (budget.exceeded()) {
                throw tooMuchToDecode(budget);
            }
        }
        return result;
    }

    private static UnreadableDocumentException tooMuchToDecode(PdfDecodeBudget.Budget budget) {
        return new UnreadableDocumentException("This PDF expands to more than " + budget.max() / (1024 * 1024)
                + " MB while it is read, which is more than one document may take. If the file is genuine, "
                + "split it, or save a copy without embedded attachments.");
    }

    private static <T> T open(byte[] pdf, ExtractionLimits limits, Instant deadline, DocumentWork<T> work) {
        try (PDDocument document = Loader.loadPDF(pdf, "", null, null,
                MemoryUsageSetting.setupMainMemoryOnly(limits.maxUncompressedBytes()).streamCache)) {
            if (document.isEncrypted()) {
                throw new UnreadableDocumentException("This PDF is encrypted. Upload a copy without a password.");
            }
            requireReadablePageTree(document, deadline);
            Map<COSDictionary, Integer> pageNumbers = numberPages(document, deadline);
            if (pageNumbers.isEmpty()) {
                throw new UnreadableDocumentException("This PDF has no pages.");
            }
            return work.run(document, pageNumbers, deadline);
        } catch (InvalidPasswordException e) {
            throw new UnreadableDocumentException("This PDF is encrypted. Upload a copy without a password.", e);
        } catch (UnreadableDocumentException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new UnreadableDocumentException("This file could not be read as a PDF.", e);
        }
    }

    /**
     * The outline's sections, read only when the cut needs them; warnings go into
     * the set given.
     */
    @FunctionalInterface
    private interface OutlineSource {
        List<Section> sections(Set<String> warnings);
    }

    /**
     * Decides the cut from a finished scan. Pure but for {@code outline}, which is
     * asked only when the strategy and the scan call for the outline.
     *
     * @param warnings
     *            the scan's warnings; the cut's are added
     */
    private static Structure structure(PageScan scan, int pageCount, SplitConfig split, ExtractionLimits limits,
                                       Set<String> warnings, OutlineSource outline) {
        SplitStrategy wanted = split.strategy();
        List<Section> sections = null;
        SplitStrategy used = SplitStrategy.PAGES;
        if (wanted == SplitStrategy.AUTO && hasNoText(scan.tokens())) {
            warnings.add(noTextWarning(Math.min(pageCount, limits.maxParts()), pageCount));
        } else if (wanted != SplitStrategy.PAGES) {
            if (wanted != SplitStrategy.HEADINGS) {
                sections = outline.sections(warnings);
                used = sections == null ? used : SplitStrategy.OUTLINE;
            }
            if (sections == null && wanted != SplitStrategy.OUTLINE) {
                sections = headingSections(scan.headings(), pageCount);
                if (sections != null) {
                    used = SplitStrategy.HEADINGS;
                    if (scan.headingsTruncated()) {
                        warnings.add(WARNING_HEADINGS_TRUNCATED);
                    }
                }
            }
            if (sections == null) {
                warnings.add(switch (wanted) {
                    case OUTLINE -> WARNING_NO_OUTLINE;
                    case HEADINGS -> WARNING_NO_HEADINGS;
                    default -> WARNING_NO_OUTLINE_OR_HEADINGS;
                });
            } else if (wanted == SplitStrategy.AUTO && used == SplitStrategy.HEADINGS) {
                warnings.add(WARNING_CUT_BY_HEADINGS);
            }
        }
        if (!scan.columnPages().isEmpty()) {
            warnings.add(multiColumnWarning(scan.columnPages()));
        }
        if (sections == null) {
            sections = pageWindows(pageCount, split.pagesPerUnit());
        }
        return new Structure(sections, scan.tokens(), used, warnings);
    }

    /**
     * Each page's dictionary to its 1-based number, by identity: the pages as the
     * page tree really holds them, whatever its {@code /Count} claims, in the order
     * the text engine counts them.
     *
     * @throws UnreadableDocumentException
     *             when the tree lists one page twice: the text engine would count
     *             it twice, and a page number here would name a different page
     *             there
     */
    private static Map<COSDictionary, Integer> numberPages(PDDocument document, Instant deadline) {
        Map<COSDictionary, Integer> numbers = new IdentityHashMap<>();
        int position = 0;
        for (PDPage page : document.getPages()) {
            if (position++ % OBJECTS_PER_DEADLINE_CHECK == 0) {
                requireBefore(deadline);
            }
            if (numbers.putIfAbsent(page.getCOSObject(), position) != null) {
                throw new UnreadableDocumentException("This PDF lists the same page more than once in its page "
                        + "tree, so its pages cannot be numbered reliably.");
            }
        }
        return numbers;
    }

    /**
     * Walks the page tree exactly as PDFBox's page iterator ({@code PDPageTree}'s
     * {@code enqueueKids}) will: depth first in kid order, a node with
     * {@code /Kids} or of type {@code /Pages} a branch, a kid skipped when it is a
     * branch-with-kids already entered, a {@code null} kid counted as the empty
     * page PDFBox makes of it. The iterator recurses where this keeps a stack of
     * positions, so the stack's height is the recursion it would need.
     *
     * @throws UnreadableDocumentException
     *             when that recursion would be deeper than
     *             {@link #MAX_PAGE_TREE_DEPTH}, or the tree holds more than
     *             {@link #MAX_PAGES} pages — before the iterator runs on it
     */
    static void requireReadablePageTree(PDDocument document, Instant deadline) {
        requireReadablePageTree(document, deadline, MAX_PAGES);
    }

    /**
     * The same, refusing more than {@code maxPages} pages (at most
     * {@link #MAX_PAGES}).
     */
    static void requireReadablePageTree(PDDocument document, Instant deadline, int maxPages) {
        int pageCap = Math.min(maxPages, MAX_PAGES);
        COSDictionary root = document.getDocumentCatalog().getCOSObject().getCOSDictionary(COSName.PAGES);
        if (root == null || !isBranch(root)) {
            return;
        }
        final class Position {
            final COSArray kids;
            int next;

            Position(COSDictionary branch) {
                COSArray array = branch.getCOSArray(COSName.KIDS);
                this.kids = array == null ? new COSArray() : array;
            }
        }
        Set<COSDictionary> entered = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Position> stack = new ArrayDeque<>();
        stack.push(new Position(root));
        long pages = 0;
        long steps = 0;
        while (!stack.isEmpty()) {
            Position position = stack.peek();
            if (position.next >= position.kids.size()) {
                stack.pop();
                continue;
            }
            if (++steps % OBJECTS_PER_DEADLINE_CHECK == 0) {
                requireBefore(deadline);
            }
            COSBase kid = position.kids.getObject(position.next++);
            if (kid instanceof COSDictionary dictionary && isBranch(dictionary)) {
                if (dictionary.containsKey(COSName.KIDS) && !entered.add(dictionary)) {
                    continue;
                }
                if (stack.size() >= MAX_PAGE_TREE_DEPTH) {
                    throw new UnreadableDocumentException("This PDF's page tree is nested more than "
                            + MAX_PAGE_TREE_DEPTH + " levels deep, which no genuine document needs.");
                }
                stack.push(new Position(dictionary));
            } else if ((kid == null || kid instanceof COSDictionary) && ++pages > pageCap) {
                throw new UnreadableDocumentException("This PDF has more than " + pageCap
                        + " pages, which is more than one document may have.");
            }
        }
    }

    private static boolean isBranch(COSDictionary node) {
        return COSName.PAGES.equals(node.getCOSName(COSName.TYPE)) || node.containsKey(COSName.KIDS);
    }

    /**
     * A heading as the page scan found it: its page, cleaned text and font size.
     */
    private record Heading(int page, String title, float fontSize) {
    }

    /**
     * One pass over the measured pages: the estimate for every page, the headings
     * in reading order, and the pages whose text stands in columns.
     */
    private record PageScan(int[] tokens, List<Heading> headings, boolean headingsTruncated,
            List<Integer> columnPages) {
    }

    /**
     * Characters laid out per page, divided by four, and — from the same layout, so
     * no page is read twice — each page's headings and columns. Bounded as text
     * extraction is: the page's content charged to the decode budget, its glyphs
     * capped at {@link ExtractionLimits#maxCharacters()}, the deadline checked.
     * Only one page's lines are held at a time.
     *
     * <p>
     * Pages are scanned in order, all at once from one open document or a window at
     * a time from several; the result is the same. Holds plain data only, so it
     * outlives the documents it scanned.
     */
    private static final class PageScanner {
        private final int[] tokens;
        private final List<Heading> headings = new ArrayList<>();
        private final List<Integer> columnPages = new ArrayList<>();
        private boolean headingsTruncated;
        private long total;
        private int scannedTo;

        PageScanner(int pageCount) {
            this.tokens = new int[pageCount];
        }

        /**
         * How many pages are measured: the first {@link ExtractionLimits#maxParts()}.
         */
        int measured(ExtractionLimits limits) {
            return Math.min(tokens.length, limits.maxParts());
        }

        /** Scans pages {@code from..to}, which must follow the pages scanned so far. */
        void scan(PDDocument document, int from, int to, ExtractionLimits limits, Instant deadline)
                throws IOException {
            if (from != scannedTo + 1 || to > tokens.length) {
                throw new IllegalStateException("Pages " + from + ".." + to + " do not follow page " + scannedTo);
            }
            var stripper = new BoundedTextStripper(deadline);
            List<Line> lines = new ArrayList<>();
            stripper.setLineListener((page, text, x, xEnd, top, fontSize) -> lines.add(new Line(text, x, xEnd, top,
                    fontSize)));
            for (int page = from; page <= to; page++) {
                requireBefore(deadline);
                lines.clear();
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                stripper.allowGlyphs(limits.maxCharacters());
                int characters = stripper.getText(document).strip().length();
                tokens[page - 1] = (characters + 3) / 4;
                total += tokens[page - 1];

                float bodySize = PageLayout.bodySize(lines);
                if (PageLayout.multiColumn(lines, bodySize)) {
                    columnPages.add(page);
                }
                for (Line line : PageLayout.readingOrder(lines)) {
                    if (headingsTruncated || !PageLayout.isHeading(line, bodySize)) {
                        continue;
                    }
                    String title = cleanTitle(line.text());
                    if (title == null) {
                        continue;
                    }
                    if (headings.size() >= MAX_HEADINGS) {
                        headingsTruncated = true;
                        continue;
                    }
                    headings.add(new Heading(page, title, line.fontSize()));
                }
                scannedTo = page;
            }
        }

        /**
         * The scan, with the pages past the measured ones given the average of those,
         * and a warning saying so.
         */
        PageScan finish(ExtractionLimits limits, Set<String> warnings) {
            int pageCount = tokens.length;
            int measured = measured(limits);
            if (scannedTo != measured) {
                throw new IllegalStateException("Scanned " + scannedTo + " of " + measured + " pages");
            }
            if (measured < pageCount) {
                int average = (int) Math.round((double) total / measured);
                for (int page = measured + 1; page <= pageCount; page++) {
                    tokens[page - 1] = average;
                }
                warnings.add("Only the first " + measured + " of " + pageCount + " pages were measured; the "
                        + "estimates for the rest are the average of those.");
            }
            return new PageScan(tokens, List.copyOf(headings), headingsTruncated, List.copyOf(columnPages));
        }
    }

    /**
     * The chapters the headings make, placed as outline entries are, or null when
     * no level of them is found on two or more pages.
     */
    private static List<Section> headingSections(List<Heading> headings, int pageCount) {
        // Levels, largest size first; a level holds the sizes within 5 % of its own
        // largest.
        List<Float> sizes = headings.stream().map(Heading::fontSize).distinct()
                .sorted(Comparator.reverseOrder()).toList();
        List<Float> levels = new ArrayList<>();
        for (float size : sizes) {
            if (levels.isEmpty() || size < levels.getLast() * SAME_LEVEL) {
                levels.add(size);
            }
        }
        // Each heading's level once, and per level whether its headings stand on two
        // or more pages: linear in the headings, whatever the number of sizes.
        int[] levelOf = new int[headings.size()];
        int[] firstPage = new int[levels.size()];
        boolean[] onTwoPages = new boolean[levels.size()];
        for (int i = 0; i < headings.size(); i++) {
            Heading heading = headings.get(i);
            int level = levelOf(heading, levels);
            levelOf[i] = level;
            if (firstPage[level] == 0) {
                firstPage[level] = heading.page();
            } else if (firstPage[level] != heading.page()) {
                onTwoPages[level] = true;
            }
        }
        int chapterLevel = -1;
        for (int level = 0; level < levels.size() && chapterLevel < 0; level++) {
            if (onTwoPages[level]) {
                chapterLevel = level;
            }
        }
        if (chapterLevel < 0) {
            return null;
        }
        List<Entry> chapters = new ArrayList<>();
        for (int i = 0; i < headings.size(); i++) {
            Heading heading = headings.get(i);
            int level = levelOf[i];
            if (level == chapterLevel) {
                chapters.add(new Entry(heading.title(), heading.page()));
            } else if (level == chapterLevel + 1 && !chapters.isEmpty()) {
                chapters.getLast().children.add(new Entry(heading.title(), heading.page()));
            }
        }
        return OutlineReader.place(chapters, 1, pageCount, true);
    }

    private static int levelOf(Heading heading, List<Float> levels) {
        for (int level = 0; level < levels.size(); level++) {
            if (heading.fontSize() >= levels.get(level) * SAME_LEVEL) {
                return level;
            }
        }
        return levels.size() - 1;
    }

    /**
     * One unit's blocks, read page by page in order — from one open document or a
     * window at a time from several, with the same result: the character allowance
     * and the notes carry across windows. Holds plain data only.
     */
    private static final class BlockRead {
        private final DocumentUnit unit;
        private final ExtractionLimits limits;
        private final int last;
        private final List<Block> blocks = new ArrayList<>();
        private long characters;
        private int read;
        private boolean cut;
        private boolean stopped;

        /**
         * @throws UnreadableDocumentException
         *             when the unit runs past the document's last page
         */
        BlockRead(DocumentUnit unit, int pageCount, ExtractionLimits limits) {
            if (unit.lastPage() > pageCount) {
                throw new UnreadableDocumentException("This part runs to page " + unit.lastPage() + ", but the PDF "
                        + "has " + pageCount + (pageCount == 1 ? " page" : " pages") + ": it belongs to another file.");
            }
            this.unit = unit;
            this.limits = limits;
            this.last = (int) Math.min(unit.lastPage(), (long) unit.firstPage() + limits.maxParts() - 1);
            this.read = unit.firstPage() - 1;
        }

        /** The last page that may be read: the unit's, or the page cap's. */
        int last() {
            return last;
        }

        /**
         * Whether the character allowance stopped the read: no page after it is read.
         */
        boolean stopped() {
            return stopped;
        }

        /** Reads pages {@code from..to}, which must follow the pages read so far. */
        void pages(PDDocument document, int from, int to, Instant deadline) throws IOException {
            if (stopped) {
                return;
            }
            if (from != read + 1 || to > last) {
                throw new IllegalStateException("Pages " + from + ".." + to + " do not follow page " + read);
            }
            var stripper = new BoundedTextStripper(deadline);
            List<Line> lines = new ArrayList<>();
            stripper.setLineListener((page, text, x, xEnd, top, fontSize) -> lines.add(new Line(text, x, xEnd, top,
                    fontSize)));
            for (int page = from; page <= to; page++) {
                requireBefore(deadline);
                lines.clear();
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                stripper.allowGlyphs(limits.maxCharacters() - characters + GLYPH_SLACK);
                stripper.getText(document);
                // Only a top-level unit gets the mid-page note: the first part of a
                // chapter starts where its chapter starts, above its own heading, and this
                // read cannot see the chapter's title to say so.
                String noteTitle = page == unit.firstPage() && unit.parentId() == null ? unit.title() : null;
                List<TextBlock> pageBlocks = pageBlocks(page, lines, noteTitle);
                for (TextBlock block : pageBlocks) {
                    characters += block.text().length();
                }
                blocks.addAll(pageBlocks);
                read = page;
                if (stripper.pageWasCut() || characters >= limits.maxCharacters()) {
                    cut = stripper.pageWasCut() || page < unit.lastPage();
                    stopped = true;
                    return;
                }
            }
        }

        /** The blocks read, with a note at the end when the read stopped early. */
        List<Block> finish() {
            List<Block> result = new ArrayList<>(blocks);
            if (cut) {
                result.add(endNote(result, read, "[The text of this part stops here: it is longer than the "
                        + limits.maxCharacters() + " characters one read may take. Ask for fewer pages to read the "
                        + "rest.]"));
            } else if (read < unit.lastPage()) {
                result.add(endNote(result, read, "[Only the first " + (read - unit.firstPage() + 1) + " pages of "
                        + "this part were read; pages " + (read + 1) + "–" + unit.lastPage() + " are not included.]"));
            }
            return List.copyOf(result);
        }
    }

    /** A note after every block read, on the last page read. */
    private static TextBlock endNote(List<Block> blocks, int page, String text) {
        if (blocks.isEmpty() || blocks.getLast().page() != page) {
            return new TextBlock(page, 0f, 0, text, false);
        }
        Block last = blocks.getLast();
        return new TextBlock(page, last.y(), last.order() + 1, text, false);
    }

    /**
     * One page's lines as blocks in reading order: a heading alone, consecutive
     * body lines together, {@code order} counting up from 0. On a unit's first page
     * ({@code unitTitle} set), a note goes first when the unit's heading stands
     * below other text.
     */
    private static List<TextBlock> pageBlocks(int page, List<Line> lines, String unitTitle) {
        float bodySize = PageLayout.bodySize(lines);
        List<TextBlock> blocks = new ArrayList<>();
        StringBuilder paragraph = null;
        float paragraphTop = 0f;
        Line previous = null;
        for (Line line : PageLayout.readingOrder(lines)) {
            String text = line.text().strip();
            if (text.isEmpty()) {
                continue;
            }
            if (PageLayout.isHeading(line, bodySize)) {
                if (paragraph != null) {
                    blocks.add(new TextBlock(page, paragraphTop, blocks.size(), paragraph.toString(), false));
                    paragraph = null;
                }
                blocks.add(new TextBlock(page, line.top(), blocks.size(), text, true));
                previous = null;
                continue;
            }
            if (paragraph != null && previous != null
                    && line.top() - previous.top() <= PARAGRAPH_LEADING * Math.max(previous.fontSize(), line.fontSize())) {
                paragraph.append('\n').append(text);
            } else {
                if (paragraph != null) {
                    blocks.add(new TextBlock(page, paragraphTop, blocks.size(), paragraph.toString(), false));
                }
                paragraph = new StringBuilder(text);
                paragraphTop = line.top();
            }
            previous = line;
        }
        if (paragraph != null) {
            blocks.add(new TextBlock(page, paragraphTop, blocks.size(), paragraph.toString(), false));
        }
        // Page 1 has no part before it: text above the first heading there is the
        // unit's own.
        TextBlock note = unitTitle == null || page == 1 ? null : midPageNote(page, blocks, unitTitle);
        if (note != null) {
            blocks.addFirst(note);
        }
        return blocks;
    }

    /**
     * The note for a unit that starts below other text on its first page, or null:
     * which heading the unit starts at, and which heading on that page — if any —
     * the text above belongs to.
     */
    private static TextBlock midPageNote(int page, List<TextBlock> blocks, String unitTitle) {
        for (int i = 1; i < blocks.size(); i++) {
            TextBlock block = blocks.get(i);
            if (block.heading() && namesUnit(block.text(), unitTitle)) {
                String before = null;
                for (int j = i - 1; j >= 0 && before == null; j--) {
                    before = blocks.get(j).heading() ? blocks.get(j).text() : null;
                }
                String text = "[Page " + page + " begins with the end of the part before"
                        + (before == null ? "" : " (under the heading \"" + before + "\")")
                        + "; this part starts at the heading \"" + block.text() + "\".]";
                TextBlock first = blocks.getFirst();
                return new TextBlock(page, first.y(), first.order() - 1, text, false);
            }
        }
        return null;
    }

    /**
     * Whether a heading's text is the start of a unit with this title: the title
     * itself, the first of two merged titles ({@code "A – B"}), or the start of a
     * title cut to length ({@code "A long titl…"}).
     */
    static boolean namesUnit(String headingText, String unitTitle) {
        String heading = cleanTitle(headingText);
        if (heading == null || unitTitle == null) {
            return false;
        }
        if (unitTitle.equals(heading) || unitTitle.startsWith(heading + " – ")) {
            return true;
        }
        return unitTitle.endsWith("…") && heading.startsWith(unitTitle.substring(0, unitTitle.length() - 1));
    }

    private static boolean hasNoText(int[] tokens) {
        for (int estimate : tokens) {
            if (estimate > 0) {
                return false;
            }
        }
        return true;
    }

    private static List<Section> pageWindows(int pageCount, int pagesPerUnit) {
        List<Section> windows = new ArrayList<>();
        // In long arithmetic: a window of nearly Integer.MAX_VALUE pages would wrap.
        for (long first = 1; first <= pageCount; first += pagesPerUnit) {
            windows.add(Section.of(null, (int) first, (int) Math.min(pageCount, first + pagesPerUnit - 1)));
        }
        return windows;
    }

    private static void requireBefore(Instant deadline) {
        if (Instant.now().isAfter(deadline) || Thread.currentThread().isInterrupted()) {
            throw new UnreadableDocumentException(BoundedTextStripper.TOO_LONG);
        }
    }

    /** The file's SHA-256, computed as it streams through a small buffer. */
    private static String sha256(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            for (int n; (n = in.read(buffer)) > 0;) {
                digest.update(buffer, 0, n);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException e) {
            throw new UnreadableDocumentException("This file could not be read as a PDF.", e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every Java runtime", e);
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every Java runtime", e);
        }
    }

    /**
     * An outline title as a unit title: control and formatting characters (line
     * breaks, bidirectional overrides) become spaces, runs of space one, and no
     * more than 256 code points are kept — a little above the 200
     * {@link UnitBudgetFitter} shows, so that its cut, and the ellipsis that marks
     * it, is the one a reader sees. Null when nothing is left.
     */
    static String cleanTitle(String raw) {
        if (raw == null) {
            return null;
        }
        var cleaned = new StringBuilder(Math.min(raw.length(), 1024));
        boolean space = false;
        int kept = 0;
        for (int i = 0; i < raw.length() && kept < 256;) {
            int codePoint = raw.codePointAt(i);
            i += Character.charCount(codePoint);
            int type = Character.getType(codePoint);
            boolean blank = Character.isWhitespace(codePoint) || Character.isSpaceChar(codePoint)
                    || Character.isISOControl(codePoint) || type == Character.FORMAT
                    || type == Character.SURROGATE || type == Character.UNASSIGNED;
            if (blank) {
                space = !cleaned.isEmpty();
                continue;
            }
            if (space) {
                cleaned.append(' ');
                space = false;
            }
            cleaned.appendCodePoint(codePoint);
            kept++;
        }
        return cleaned.isEmpty() ? null : cleaned.toString();
    }

    /** One outline entry or heading as read, before it is placed. */
    private static final class Entry {
        final String title;
        final int page;
        final List<Entry> children = new ArrayList<>();

        Entry(String title, int page) {
            this.title = title;
            this.page = page;
        }
    }

    /** Walks one document's outline into sections. One use. */
    private static final class OutlineReader {
        private final PDDocument document;
        private final Map<COSDictionary, Integer> pageNumbers;
        private final int pageCount;
        private final Instant deadline;
        private final Set<String> warnings;
        private Map<String, COSBase> namedDestinations;
        private int unresolved;

        OutlineReader(PDDocument document, Map<COSDictionary, Integer> pageNumbers, int pageCount,
                Instant deadline, Set<String> warnings) {
            this.document = document;
            this.pageNumbers = pageNumbers;
            this.pageCount = pageCount;
            this.deadline = deadline;
            this.warnings = warnings;
        }

        /** The top-level sections, or null when the outline is not usable. */
        List<Section> sections() {
            COSDictionary outlines = document.getDocumentCatalog().getCOSObject().getCOSDictionary(COSName.OUTLINES);
            if (outlines == null) {
                return null;
            }
            List<Entry> top = resolved(read(outlines));
            if (unresolved > 0) {
                warnings.add(unresolved + (unresolved == 1 ? " outline entry points" : " outline entries point")
                        + " to no page of this PDF and " + (unresolved == 1 ? "was" : "were") + " skipped.");
            }
            while (top.size() == 1 && !top.get(0).children.isEmpty()) {
                top = top.get(0).children;
            }
            // Chapters, not placed sections: the front-matter section placed before a
            // single chapter makes two sections out of an outline with one entry.
            long chapters = top.stream().mapToInt(entry -> entry.page).distinct().count();
            return chapters >= 2 ? place(top, 1, pageCount, true) : null;
        }

        /**
         * The outline as a tree of entries, depth first, each item once. An item seen
         * before — a {@code /Next} or {@code /First} pointing back — ends that branch.
         */
        private List<Entry> read(COSDictionary outlines) {
            record Visit(COSDictionary item, Entry parent) {
            }
            var root = new Entry(null, 0);
            Set<COSDictionary> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            seen.add(outlines);
            Deque<Visit> pending = new ArrayDeque<>();
            pending.push(new Visit(outlines.getCOSDictionary(COSName.FIRST), root));
            int items = 0;
            while (!pending.isEmpty()) {
                Visit visit = pending.pop();
                if (visit.item() == null) {
                    continue;
                }
                if (!seen.add(visit.item())) {
                    warnings.add(WARNING_OUTLINE_LOOP);
                    continue;
                }
                if (++items > MAX_OUTLINE_ITEMS) {
                    warnings.add(WARNING_OUTLINE_TRUNCATED);
                    break;
                }
                if (items % OBJECTS_PER_DEADLINE_CHECK == 0) {
                    requireBefore(deadline);
                }
                var entry = new Entry(cleanTitle(visit.item().getString(COSName.TITLE)), pageOf(visit.item()));
                visit.parent().children.add(entry);
                pending.push(new Visit(visit.item().getCOSDictionary(COSName.NEXT), visit.parent()));
                pending.push(new Visit(visit.item().getCOSDictionary(COSName.FIRST), entry));
            }
            return root.children;
        }

        /** Entries that point nowhere are replaced by their own children. */
        private List<Entry> resolved(List<Entry> entries) {
            List<Entry> out = new ArrayList<>();
            for (Entry entry : entries) {
                List<Entry> children = resolved(entry.children);
                if (entry.page > 0) {
                    entry.children.clear();
                    entry.children.addAll(children);
                    out.add(entry);
                } else {
                    unresolved++;
                    out.addAll(children);
                }
            }
            return out;
        }

        /**
         * Sections for the entries whose page lies in {@code from..to}, covering those
         * pages once: ordered by page, one per starting page, each running to the page
         * before the next. At the top level the pages before the first entry are a
         * section of their own; below it they join the first part.
         */
        static List<Section> place(List<Entry> entries, int from, int to, boolean topLevel) {
            List<Entry> starts = new ArrayList<>();
            int lastStart = 0;
            List<Entry> ordered = new ArrayList<>(entries);
            ordered.sort(Comparator.comparingInt(entry -> entry.page));
            for (Entry entry : ordered) {
                if (entry.page >= from && entry.page <= to && entry.page != lastStart) {
                    starts.add(entry);
                    lastStart = entry.page;
                }
            }
            List<Section> sections = new ArrayList<>();
            if (starts.isEmpty()) {
                return sections;
            }
            if (topLevel && starts.get(0).page > from) {
                sections.add(Section.of(FRONT_MATTER, from, starts.get(0).page - 1));
            }
            for (int i = 0; i < starts.size(); i++) {
                Entry entry = starts.get(i);
                int first = i == 0 && !topLevel ? from : entry.page;
                int last = i + 1 < starts.size() ? starts.get(i + 1).page - 1 : to;
                List<Section> parts = topLevel ? place(entry.children, first, last, false) : List.of();
                sections.add(new Section(entry.title, first, last, parts.size() >= 2 ? parts : List.of()));
            }
            return sections;
        }

        /** The 1-based page an outline item leads to, or 0. */
        private int pageOf(COSDictionary item) {
            COSBase destination = item.getDictionaryObject(COSName.DEST);
            if (destination == null) {
                COSDictionary action = item.getCOSDictionary(COSName.A);
                if (action != null && GO_TO.equals(action.getCOSName(COSName.S))) {
                    destination = action.getDictionaryObject(COSName.D);
                }
            }
            return pageOf(destination, 0);
        }

        /**
         * An explicit destination's page; a name looked up once; a dictionary's
         * {@code /D} followed once. Depth-limited, so names naming names end.
         */
        private int pageOf(COSBase destination, int depth) {
            if (destination instanceof COSArray array && array.size() > 0) {
                COSBase target = array.getObject(0);
                if (target instanceof COSDictionary page) {
                    return pageNumbers.getOrDefault(page, 0);
                }
                if (target instanceof COSNumber number) {
                    // Malformed: a page index where a page belongs. PDFBox reads it as 0-based.
                    int index = number.intValue();
                    return index >= 0 && index < pageCount ? index + 1 : 0;
                }
                return 0;
            }
            if (depth >= 2) {
                return 0;
            }
            if (destination instanceof COSDictionary dictionary) {
                return pageOf(dictionary.getDictionaryObject(COSName.D), depth + 1);
            }
            String name = destination instanceof COSName cosName
                    ? cosName.getName()
                    : destination instanceof COSString string ? string.getString() : null;
            return name == null ? 0 : pageOf(namedDestinations().get(name), depth + 1);
        }

        /**
         * Every named destination: the {@code /Dests} name tree, then the older catalog
         * {@code /Dests} dictionary. Read once, iteratively, each node once, up to
         * {@link #MAX_NAMED_DESTINATIONS}.
         */
        private Map<String, COSBase> namedDestinations() {
            if (namedDestinations != null) {
                return namedDestinations;
            }
            namedDestinations = new HashMap<>();
            COSDictionary catalog = document.getDocumentCatalog().getCOSObject();
            int visited = 0;
            COSDictionary names = catalog.getCOSDictionary(COSName.NAMES);
            COSDictionary tree = names == null ? null : names.getCOSDictionary(COSName.DESTS);
            Set<COSDictionary> seen = Collections.newSetFromMap(new IdentityHashMap<>());
            Deque<COSDictionary> pending = new ArrayDeque<>();
            if (tree != null) {
                pending.push(tree);
            }
            while (!pending.isEmpty()) {
                COSDictionary node = pending.pop();
                if (!seen.add(node)) {
                    continue;
                }
                if (++visited > MAX_NAMED_DESTINATIONS) {
                    warnings.add(WARNING_NAMES_TRUNCATED);
                    return namedDestinations;
                }
                if (visited % OBJECTS_PER_DEADLINE_CHECK == 0) {
                    requireBefore(deadline);
                }
                COSArray pairs = node.getCOSArray(COSName.NAMES);
                if (pairs != null) {
                    for (int i = 0; i + 1 < pairs.size(); i += 2) {
                        if (++visited > MAX_NAMED_DESTINATIONS) {
                            warnings.add(WARNING_NAMES_TRUNCATED);
                            return namedDestinations;
                        }
                        if (pairs.getObject(i) instanceof COSString key) {
                            namedDestinations.putIfAbsent(key.getString(), pairs.getObject(i + 1));
                        }
                    }
                }
                COSArray kids = node.getCOSArray(COSName.KIDS);
                if (kids != null) {
                    for (int i = kids.size() - 1; i >= 0; i--) {
                        if (kids.getObject(i) instanceof COSDictionary kid) {
                            pending.push(kid);
                        }
                    }
                }
            }
            COSDictionary legacy = catalog.getCOSDictionary(COSName.DESTS);
            if (legacy != null) {
                for (COSName key : legacy.keySet()) {
                    if (++visited > MAX_NAMED_DESTINATIONS) {
                        warnings.add(WARNING_NAMES_TRUNCATED);
                        return namedDestinations;
                    }
                    namedDestinations.putIfAbsent(key.getName(), legacy.getDictionaryObject(key));
                }
            }
            return namedDestinations;
        }
    }
}
