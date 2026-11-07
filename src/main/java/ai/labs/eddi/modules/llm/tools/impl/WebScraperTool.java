/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.modules.llm.tools.impl;

import ai.labs.eddi.engine.httpclient.outbound.BodyMode;
import ai.labs.eddi.engine.httpclient.outbound.OutboundHttp;
import ai.labs.eddi.engine.httpclient.outbound.OutboundRequest;
import ai.labs.eddi.engine.httpclient.outbound.OutboundResponse;
import ai.labs.eddi.engine.httpclient.outbound.Profile;
import ai.labs.eddi.engine.httpclient.outbound.SsrfPolicy;
import ai.labs.eddi.modules.ingestion.HtmlToMarkdownConverter;
import ai.labs.eddi.modules.llm.tools.ToolFailureException;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.Elements;
import org.jsoup.select.NodeFilter;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.Selector;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static ai.labs.eddi.modules.llm.tools.UrlValidationUtils.validateUrl;
import static ai.labs.eddi.utils.LogSanitizer.sanitize;

/**
 * Web content extraction tool for scraping and parsing HTML content.
 *
 * <p>
 * <strong>Parsing is CPU-bound and runs contained.</strong> Fetching is I/O and
 * stays on the calling thread, but turning up to {@code max-response-bytes} of
 * model-chosen HTML into a DOM and evaluating a model-written CSS selector
 * against it is pure CPU. On the virtual thread the tool call arrives on, that
 * work occupies a carrier thread for its whole duration, and there are only as
 * many carriers as cores: a few slow selectors starved every virtual thread in
 * the JVM. Parsing and selection therefore run on a small bounded pool of
 * platform threads ({@code eddi.tools.web-scraper.parse-threads}, default 2)
 * with a hard deadline ({@code eddi.tools.web-scraper.parse-timeout-ms},
 * default 10 s). When the pool is busy a call is refused immediately rather
 * than queued.
 * </p>
 *
 * <p>
 * A worker that outlives its deadline is interrupted, but jsoup does not check
 * the flag, so it keeps running until it finishes on its own. That is the
 * honest limit of containment in Java — there is no safe way to kill a thread —
 * and it is why the pool is bounded: a runaway can cost at most
 * {@code parse-threads} cores, never the server. It is also why the selector is
 * validated first: jsoup evaluates {@code :matches(…)} and friends as Java
 * regular expressions over page text, and a catastrophic pattern there does not
 * finish at all, so model-supplied regex selectors are refused outright.
 * </p>
 */
@ApplicationScoped
public class WebScraperTool {
    private static final Logger LOGGER = Logger.getLogger(WebScraperTool.class);

    /** Cap on what a single extraction hands back to the model. */
    private static final int MAX_EXTRACTED_CHARACTERS = 5000;

    /** Most elements a selector extraction lists. */
    static final int MAX_SELECTOR_ELEMENTS = 20;

    /**
     * Cap on the text of all listed elements together. Each element is also held to
     * {@link #MAX_EXTRACTED_CHARACTERS}, so one huge match cannot take it all.
     */
    static final int MAX_SELECTOR_OUTPUT_CHARACTERS = 20_000;

    /** Backstop response cap used when no configuration is supplied (5 MiB). */
    static final long DEFAULT_MAX_RESPONSE_BYTES = 5L * 1024 * 1024;

    /** Longest CSS selector a model may submit. */
    static final int MAX_SELECTOR_LENGTH = 256;

    /** Default deadline for parsing one page and evaluating one selector. */
    static final long DEFAULT_PARSE_TIMEOUT_MS = 10_000L;

    /** Default number of platform threads that may parse at the same time. */
    static final int DEFAULT_PARSE_THREADS = 2;

    /**
     * Pseudo-selectors whose parenthesised argument is text or an index, never a
     * selector — exactly the names jsoup's {@code QueryParser} reads that way.
     */
    private static final Set<String> LITERAL_ARGUMENT_PSEUDOS = Set.of("contains", "containsOwn", "containsData",
            "containsWholeText", "containsWholeOwnText", "nth-child", "nth-last-child", "nth-of-type", "nth-last-of-type", "eq",
            "lt", "gt");

    /**
     * jsoup pseudo-selectors that take a Java regular expression — or, for
     * {@code :matchText}, rewrite the document — plus the {@code ~=} attribute
     * operator, which jsoup also evaluates as a regex. Matched case-insensitively.
     */
    private static final Pattern REGEX_SELECTOR = Pattern.compile("(?i):match(es|esown|eswholetext|eswholeowntext|text)\\b|~=");

    /**
     * jsoup pseudo-selectors whose evaluation walks the candidate's siblings — an
     * index lookup or a same-type count, linear in the parent's width — once per
     * candidate, which makes them quadratic in it.
     */
    private static final Pattern POSITIONAL_PSEUDO = Pattern
            .compile("(?i):(nth-child|nth-last-child|nth-of-type|nth-last-of-type|first-of-type|last-of-type|only-of-type|only-child|eq|lt|gt)"
                    + "(?![\\w-])");

    /**
     * Ceiling on {@link #siblingScanWork} for a selector with a positional pseudo:
     * tens of milliseconds of sibling walking. A 3,000-row table is ~9 million; a
     * page that puts hundreds of thousands of nodes under one parent, built to make
     * the walk run for minutes on a parser worker no timeout can stop, is far
     * above.
     */
    static final long MAX_POSITIONAL_SCAN_WORK = 50_000_000L;

    private final OutboundHttp http;
    private final HtmlToMarkdownConverter htmlToMarkdownConverter;
    private final long maxResponseBytes;
    private final long parseTimeoutMs;
    private final ExecutorService parsePool;

    /**
     * Admission to {@link #parsePool}: one permit per worker, released by the task
     * itself when its parse actually ends — not when the caller stops waiting. A
     * {@code SynchronousQueue} hand-off alone also refused a call in the instant
     * between a worker finishing and returning to its queue, so a pool with a free
     * worker could answer "busy".
     */
    private final Semaphore parsePermits;

    @Inject
    public WebScraperTool(OutboundHttp http, HtmlToMarkdownConverter htmlToMarkdownConverter,
            @ConfigProperty(name = "eddi.tools.web-scraper.max-response-bytes",
                            defaultValue = "5242880") long maxResponseBytes,
            @ConfigProperty(name = "eddi.tools.web-scraper.parse-timeout-ms", defaultValue = "10000") long parseTimeoutMs,
            @ConfigProperty(name = "eddi.tools.web-scraper.parse-threads", defaultValue = "2") int parseThreads) {
        this.http = http;
        this.htmlToMarkdownConverter = htmlToMarkdownConverter;
        this.maxResponseBytes = maxResponseBytes > 0 ? maxResponseBytes : DEFAULT_MAX_RESPONSE_BYTES;
        this.parseTimeoutMs = parseTimeoutMs > 0 ? parseTimeoutMs : DEFAULT_PARSE_TIMEOUT_MS;
        int threads = parseThreads > 0 ? parseThreads : DEFAULT_PARSE_THREADS;
        this.parsePool = boundedParsePool(threads);
        this.parsePermits = new Semaphore(threads);
    }

    /** Convenience constructor for tests and callers with no configured cap. */
    public WebScraperTool(OutboundHttp http, HtmlToMarkdownConverter htmlToMarkdownConverter) {
        this(http, htmlToMarkdownConverter, DEFAULT_MAX_RESPONSE_BYTES, DEFAULT_PARSE_TIMEOUT_MS, DEFAULT_PARSE_THREADS);
    }

    /**
     * Platform threads, fixed in number. Admission is decided by
     * {@link #parsePermits}, which never admits more tasks than there are workers,
     * so the queue only ever bridges the moment a finished worker takes a moment to
     * pick up the next task; a call that finds every worker busy is refused at once
     * instead of waiting behind a runaway.
     */
    private static ExecutorService boundedParsePool(int threads) {
        AtomicInteger counter = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "eddi-webscraper-parse-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), factory,
                new ThreadPoolExecutor.AbortPolicy());
    }

    @PreDestroy
    void shutdown() {
        parsePool.shutdownNow();
    }

    @Tool("Extracts the readable content of a web page URL as Markdown, with navigation, scripts and other page chrome removed.")
    public String extractWebPageText(@P("Absolute http(s) URL of the page to read") String url) {

        try {
            LOGGER.debugf("Extracting text from URL: %s", sanitize(url));

            FetchedPage page = fetchUrl(url);

            // Delegated rather than re-implemented: this tool used to run its own
            // "script, style, nav, footer, header, aside" strip plus a flat text()
            // dump, which dropped page titles living in <article><header><h1> and
            // merged adjacent blocks into single words. The converter keeps the
            // structure an LLM can actually use — headings, lists, tables, code.
            return page.annotate(parseBounded(() -> htmlToMarkdownConverter.convert(page.html(), url, MAX_EXTRACTED_CHARACTERS)));

        } catch (ToolFailureException e) {
            throw e;
        } catch (Exception e) {
            LOGGER.warnf("Web page extraction failed for %s: %s", sanitize(url), sanitize(e.getMessage()));
            throw new ToolFailureException("Error: Could not extract content from web page - " + e.getMessage());
        }
    }

    @Tool("Extracts all links (URLs) from a web page")
    public String extractLinks(@P("Absolute http(s) URL of the page") String url,
                               @P(value = "Maximum number of links to return, 1-50 (default 20)", required = false) Integer maxLinks) {

        try {
            if (maxLinks == null || maxLinks < 1) {
                maxLinks = 20;
            }
            if (maxLinks > 50) {
                maxLinks = 50;
            }

            LOGGER.debugf("Extracting links from URL: %s", sanitize(url));

            FetchedPage page = fetchUrl(url);
            Elements links = parseBounded(() -> Jsoup.parse(page.html()).select("a[href]"));

            StringBuilder result = new StringBuilder();
            result.append("Links found on ").append(url).append(":\n\n");

            int count = 0;
            for (Element link : links) {
                if (count >= maxLinks)
                    break;

                String href = link.attr("abs:href"); // Get absolute URL
                String text = link.text();

                if (!href.isEmpty()) {
                    result.append(++count).append(". ");
                    if (!text.isEmpty()) {
                        result.append(text).append(" - ");
                    }
                    result.append(href).append("\n");
                }
            }

            if (count == 0) {
                result.append("No links found.");
            }

            LOGGER.debugf("Extracted %d links from %s", count, sanitize(url));
            return page.annotate(result.toString());

        } catch (ToolFailureException e) {
            throw e;
        } catch (Exception e) {
            LOGGER.warnf("Link extraction failed for %s: %s", sanitize(url), sanitize(e.getMessage()));
            throw new ToolFailureException("Error: Could not extract links - " + e.getMessage());
        }
    }

    @Tool("Extracts structured data from a web page using CSS selectors")
    public String extractWithSelector(@P("Absolute http(s) URL of the page") String url,
                                      @P("CSS selector, e.g. 'h2.title' or 'table tr td:eq(1)'. Regex pseudo-selectors "
                                              + "(:matches and friends), the ~= operator, sibling combinators (~ and +) and CSS "
                                              + "escapes are not supported; at most 256 characters") String cssSelector) {

        String rejection = rejectSelector(cssSelector);
        if (rejection != null) {
            return rejection;
        }

        try {
            LOGGER.debugf("Extracting elements matching '%s' from %s", sanitize(cssSelector), sanitize(url));

            FetchedPage page = fetchUrl(url);
            boolean positional = usesPositionalPseudo(cssSelector);
            Elements elements = parseBounded(() -> {
                Document doc = Jsoup.parse(page.html());
                return positional && siblingScanWork(doc) > MAX_POSITIONAL_SCAN_WORK ? null : doc.select(cssSelector);
            });
            if (elements == null) {
                return "Error: this page is too wide for positional selectors (:eq, :lt, :gt, :nth-*, :*-of-type, :only-child). "
                        + "Select by tag, class or attribute instead.";
            }

            if (elements.isEmpty()) {
                return "No elements found matching selector: " + cssSelector;
            }

            StringBuilder result = new StringBuilder();
            result.append("Found ").append(elements.size()).append(" element(s) matching '").append(cssSelector).append("':\n\n");

            // Element.text() copies an element's WHOLE descendant text, so a selector
            // matching nested ancestors (`div` on twenty nested divs) used to hand back
            // the same page text twenty times over. An element inside one already
            // listed is skipped — its text is part of that ancestor's — and every
            // element's text is gathered against what is left of one output budget,
            // so nothing past the budget is ever materialized.
            Set<Element> listed = Collections.newSetFromMap(new IdentityHashMap<>());
            int budget = MAX_SELECTOR_OUTPUT_CHARACTERS;
            int count = 0;
            for (Element element : elements) {
                if (hasListedAncestor(element, listed)) {
                    continue;
                }
                if (count >= MAX_SELECTOR_ELEMENTS || budget <= 0) {
                    result.append("\n[Additional elements truncated]");
                    break;
                }
                listed.add(element);
                String text = boundedText(element, Math.min(budget, MAX_EXTRACTED_CHARACTERS));
                budget -= text.length();
                result.append(++count).append(". ").append(text).append("\n");
            }

            LOGGER.debugf("Extracted %d elements from %s", count, sanitize(url));
            return page.annotate(result.toString());

        } catch (ToolFailureException e) {
            throw e;
        } catch (Selector.SelectorParseException e) {
            // Deterministic: the same selector fails the same way every time, so it is
            // an answer, not a transient failure.
            return "Error: Invalid CSS selector - " + e.getMessage();
        } catch (Exception e) {
            LOGGER.warnf("Selector extraction failed for %s: %s", sanitize(url), sanitize(e.getMessage()));
            throw new ToolFailureException("Error: Could not extract with selector - " + e.getMessage());
        }
    }

    /**
     * An upper bound on the sibling walking a positional pseudo can cost on
     * {@code doc}: the sum over every element of its child-node count squared —
     * each child a candidate, each candidate walking its siblings once. One linear
     * pass; package-private for tests.
     */
    static long siblingScanWork(Document doc) {
        long work = 0;
        for (Element element : doc.getAllElements()) {
            long width = element.childNodeSize();
            work += width * width;
            if (work > MAX_POSITIONAL_SCAN_WORK) {
                return work;
            }
        }
        return work;
    }

    /** Whether an ancestor of {@code element} is already in the result. */
    private static boolean hasListedAncestor(Element element, Set<Element> listed) {
        if (listed.isEmpty()) {
            return false;
        }
        for (Element parent = element.parent(); parent != null; parent = parent.parent()) {
            if (listed.contains(parent)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The element's text like {@link Element#text()} — descendant text with
     * whitespace collapsed and block boundaries read as a space — but gathered only
     * up to {@code maxChars}: the walk stops there, so an element holding the whole
     * page costs its budget, not its size. Package-private for tests.
     */
    static String boundedText(Element element, int maxChars) {
        StringBuilder text = new StringBuilder(Math.min(maxChars, 1024));
        NodeTraversor.filter(new NodeFilter() {
            @Override
            public FilterResult head(Node node, int depth) {
                if (text.length() >= maxChars) {
                    return FilterResult.STOP;
                }
                if (node instanceof TextNode textNode) {
                    appendCollapsed(text, textNode.getWholeText(), maxChars);
                } else if (node instanceof Element child && (child.isBlock() || child.nameIs("br"))) {
                    appendCollapsed(text, " ", maxChars);
                }
                return FilterResult.CONTINUE;
            }

            @Override
            public FilterResult tail(Node node, int depth) {
                // A block also ends with a boundary: "<p>next</p>tail" reads "next tail".
                if (node != element && node instanceof Element child && child.isBlock()) {
                    appendCollapsed(text, " ", maxChars);
                }
                return text.length() >= maxChars ? FilterResult.STOP : FilterResult.CONTINUE;
            }
        }, element);
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == ' ') {
            end--;
        }
        return text.substring(0, end);
    }

    /** Space, tab, line feed, form feed or carriage return: jsoup's whitespace. */
    private static boolean isJsoupWhitespace(char c) {
        return c == ' ' || c == 9 || c == 10 || c == 12 || c == 13;
    }

    /**
     * Appends {@code raw} with each whitespace run as one space, up to the cap. The
     * whitespace set is jsoup's own, so a no-break space survives as it does in
     * {@link Element#text()}.
     */
    private static void appendCollapsed(StringBuilder text, String raw, int maxChars) {
        for (int i = 0; i < raw.length() && text.length() < maxChars; i++) {
            char c = raw.charAt(i);
            if (isJsoupWhitespace(c)) {
                if (text.length() > 0 && text.charAt(text.length() - 1) != ' ') {
                    text.append(' ');
                }
            } else {
                text.append(c);
            }
        }
    }

    /**
     * Why a model-supplied selector is refused, or {@code null} when it may run.
     * Package-private for tests.
     */
    static String rejectSelector(String cssSelector) {
        if (cssSelector == null || cssSelector.isBlank()) {
            return "Error: cssSelector must not be empty.";
        }
        if (cssSelector.length() > MAX_SELECTOR_LENGTH) {
            return "Error: cssSelector is too long (max " + MAX_SELECTOR_LENGTH + " characters).";
        }
        // jsoup decodes CSS escapes before it decides which pseudo-selector to run, so
        // ":\6d atches(...)" reached the regex evaluator although the name check below
        // never saw ":matches". No legitimate scraping selector needs an escape.
        if (cssSelector.indexOf('\\') >= 0) {
            return "Error: CSS escapes (backslashes) are not supported in cssSelector.";
        }
        if (REGEX_SELECTOR.matcher(cssSelector).find()) {
            return "Error: regular-expression selectors (:matches, :matchesOwn, :matchText, :matchesWholeText, "
                    + ":matchesWholeOwnText, [attr~=regex]) are not supported. Use :contains(text) or :containsOwn(text) instead.";
        }
        // Sibling combinators make jsoup walk every preceding sibling of every
        // candidate: quadratic (or worse, chained) in a page's width, CPU work the
        // parse timeout cannot interrupt, holding a shared parser worker meanwhile.
        if (hasSiblingCombinator(cssSelector)) {
            return "Error: sibling combinators ('~' and '+') are not supported. Select the parent and its children instead "
                    + "(e.g. 'ul > li' or 'li:eq(2)').";
        }
        return null;
    }

    /**
     * Whether {@code selector} uses {@code ~} or {@code +} as a combinator. The two
     * characters are also ordinary text inside a quoted string, an attribute
     * selector ({@code [href*=a+b]}), a text pseudo's argument
     * ({@code :contains(C++)}) and an index formula ({@code :nth-child(2n+1)}) —
     * none of which is a combinator — so those spans are skipped. The argument of a
     * structural pseudo ({@code :has}, {@code :not}, {@code :is}) is a selector
     * itself and is scanned. Package-private for tests.
     */
    static boolean hasSiblingCombinator(String selector) {
        int i = 0;
        int n = selector.length();
        while (i < n) {
            char c = selector.charAt(i);
            if (c == '"' || c == '\'') {
                i = skipQuoted(selector, i);
            } else if (c == '[') {
                i = skipBalanced(selector, i, '[', ']');
            } else if (c == '(' && isLiteralArgumentPseudo(selector, i)) {
                i = skipBalanced(selector, i, '(', ')');
            } else if (c == '~' || c == '+') {
                return true;
            } else {
                i++;
            }
            if (i < 0) {
                // An unterminated quote, bracket or argument: fail closed rather than
                // trust that whatever follows is text.
                return true;
            }
        }
        return false;
    }

    /**
     * Whether {@code selector} uses one of the {@link #POSITIONAL_PSEUDO}s as
     * selector syntax. The same text inside a quoted string, an attribute selector
     * or a text pseudo's argument ({@code span:contains(:eq(1))}) is a literal
     * jsoup never evaluates, so those spans are skipped — the spans
     * {@link #hasSiblingCombinator} skips. An unterminated span fails closed (as if
     * positional). Package-private for tests.
     */
    static boolean usesPositionalPseudo(String selector) {
        StringBuilder syntax = new StringBuilder(selector.length());
        int i = 0;
        int n = selector.length();
        while (i < n) {
            char c = selector.charAt(i);
            int next;
            if (c == '"' || c == '\'') {
                next = skipQuoted(selector, i);
            } else if (c == '[') {
                next = skipBalanced(selector, i, '[', ']');
            } else if (c == '(' && isLiteralArgumentPseudo(selector, i)) {
                next = skipBalanced(selector, i, '(', ')');
            } else {
                syntax.append(c);
                i++;
                continue;
            }
            if (next < 0) {
                return true;
            }
            syntax.append(' ');
            i = next;
        }
        return POSITIONAL_PSEUDO.matcher(syntax).find();
    }

    /**
     * True when the pseudo-class directly before {@code openParen} takes text or an
     * index formula, not a selector — the pseudos jsoup's {@code QueryParser} reads
     * with a balanced-parenthesis chomp of raw text ({@code :contains*}) or an
     * index ({@code :nth-*}, {@code :eq}, {@code :lt}, {@code :gt}). Names compare
     * exactly, as jsoup's do ({@code :CONTAINS(} is a parse error there, so it is
     * not skipped here either). A structural pseudo ({@code :has}, {@code :not},
     * {@code :is}) is not literal: its argument is a selector and is scanned like
     * the rest.
     */
    private static boolean isLiteralArgumentPseudo(String selector, int openParen) {
        int start = openParen;
        while (start > 0 && (Character.isLetter(selector.charAt(start - 1)) || selector.charAt(start - 1) == '-')) {
            start--;
        }
        if (start == 0 || selector.charAt(start - 1) != ':') {
            return false;
        }
        return LITERAL_ARGUMENT_PSEUDOS.contains(selector.substring(start, openParen));
    }

    /**
     * Index just past the quoted string opening at {@code open}, or {@code -1} when
     * it is unterminated.
     */
    private static int skipQuoted(String s, int open) {
        int close = s.indexOf(s.charAt(open), open + 1);
        return close < 0 ? -1 : close + 1;
    }

    /**
     * Index just past the closer matching the opener at {@code open}, honouring
     * quotes, or {@code -1} when it is unbalanced or a quote inside is
     * unterminated.
     */
    private static int skipBalanced(String s, int open, char opener, char closer) {
        int depth = 0;
        int i = open;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '"' || c == '\'') {
                i = skipQuoted(s, i);
                if (i < 0) {
                    return -1;
                }
                continue;
            }
            if (c == opener) {
                depth++;
            } else if (c == closer && --depth == 0) {
                return i + 1;
            }
            i++;
        }
        return -1;
    }

    @Tool("Gets metadata from a web page (title, description, keywords)")
    public String extractMetadata(@P("Absolute http(s) URL of the page") String url) {

        try {
            LOGGER.debugf("Extracting metadata from URL: %s", sanitize(url));

            FetchedPage page = fetchUrl(url);
            Document doc = parseBounded(() -> Jsoup.parse(page.html()));

            StringBuilder result = new StringBuilder();
            result.append("Metadata for ").append(url).append(":\n\n");

            // Title
            String title = doc.title();
            if (!title.isEmpty()) {
                result.append("Title: ").append(title).append("\n");
            }

            // Description
            Element descMeta = doc.selectFirst("meta[name=description]");
            if (descMeta != null) {
                result.append("Description: ").append(descMeta.attr("content")).append("\n");
            }

            // Keywords
            Element keywordsMeta = doc.selectFirst("meta[name=keywords]");
            if (keywordsMeta != null) {
                result.append("Keywords: ").append(keywordsMeta.attr("content")).append("\n");
            }

            // Author
            Element authorMeta = doc.selectFirst("meta[name=author]");
            if (authorMeta != null) {
                result.append("Author: ").append(authorMeta.attr("content")).append("\n");
            }

            // Open Graph title
            Element ogTitle = doc.selectFirst("meta[property=og:title]");
            if (ogTitle != null) {
                result.append("OG Title: ").append(ogTitle.attr("content")).append("\n");
            }

            // Open Graph description
            Element ogDesc = doc.selectFirst("meta[property=og:description]");
            if (ogDesc != null) {
                result.append("OG Description: ").append(ogDesc.attr("content")).append("\n");
            }

            LOGGER.debugf("Metadata extracted from %s", sanitize(url));
            return page.annotate(result.toString());

        } catch (ToolFailureException e) {
            throw e;
        } catch (Exception e) {
            LOGGER.warnf("Metadata extraction failed for %s: %s", sanitize(url), sanitize(e.getMessage()));
            throw new ToolFailureException("Error: Could not extract metadata - " + e.getMessage());
        }
    }

    /**
     * Runs CPU-bound parsing on {@link #parsePool} under the parse deadline.
     *
     * @throws ToolFailureException
     *             when every parse worker is busy, or the deadline passes
     */
    private <T> T parseBounded(Callable<T> parse) throws Exception {
        if (!parsePermits.tryAcquire()) {
            LOGGER.warn("Web scraper parse pool is saturated; refusing the call");
            throw new ToolFailureException("Error: the web scraper is busy parsing other pages. Try again shortly.");
        }
        // Exactly one side returns the permit: the task when it ran (in its finally,
        // so a runaway keeps its permit for as long as it really occupies a worker),
        // or the caller when it gave up on a task that never started.
        AtomicBoolean claimed = new AtomicBoolean();
        Callable<T> admitted = () -> {
            if (!claimed.compareAndSet(false, true)) {
                throw new CancellationException("abandoned before it started");
            }
            try {
                return parse.call();
            } finally {
                parsePermits.release();
            }
        };
        Future<T> future;
        try {
            future = parsePool.submit(admitted);
        } catch (RejectedExecutionException shutDown) {
            parsePermits.release();
            throw new ToolFailureException("Error: the web scraper is not available.");
        }
        try {
            return future.get(parseTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException expired) {
            abandon(future, claimed);
            LOGGER.warnf("Web scraper parse exceeded %dms and was abandoned", parseTimeoutMs);
            throw new ToolFailureException("Error: parsing the page took longer than " + parseTimeoutMs
                    + "ms and was stopped. Use a simpler selector or a smaller page.");
        } catch (InterruptedException interrupted) {
            abandon(future, claimed);
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof Exception e) {
                throw e;
            }
            throw failed;
        }
    }

    /** Stops waiting for a parse; returns its permit if it never started. */
    private void abandon(Future<?> future, AtomicBoolean claimed) {
        future.cancel(true);
        if (claimed.compareAndSet(false, true)) {
            parsePermits.release();
        }
    }

    /**
     * Fetches the content of a URL through {@link OutboundHttp}. The URL is
     * SSRF-checked here, and by the facade under {@code STRICT}: the model chose
     * it, so the initial hop gets the facade's post-connect re-check too (DNS
     * rebinding), not only the redirect hops {@code HOPS_ONLY} covered.
     */
    private FetchedPage fetchUrl(String url) throws IOException, InterruptedException {
        validateUrl(url);

        OutboundRequest request = OutboundRequest.get(Profile.DEFAULT, URI.create(url))
                .ssrf(SsrfPolicy.STRICT)
                .timeout(Duration.ofSeconds(15))
                .header("User-Agent", "Mozilla/5.0 (EDDI-Agent/1.0)")
                .build();

        // Bounded read: an LLM-chosen URL must not be able to stream an unbounded
        // body into memory. The body is capped as it arrives rather than pulled in
        // full and measured afterwards.
        OutboundResponse<byte[]> response = http.send(request, BodyMode.bytes(maxResponseBytes, BodyMode.OnOverflow.TRUNCATE));

        if (response.status() != 200) {
            throw new IOException("HTTP " + response.status() + " for URL: " + url);
        }

        if (!response.truncated()) {
            return new FetchedPage(new String(response.body(), StandardCharsets.UTF_8), null);
        }
        // A truncated body is either over the size cap or cut off by the read
        // deadline; the flag does not say which. Both leave a partial page, so the
        // caller's output carries an explicit note instead of passing the fragment
        // off as the whole page, and a multi-byte character split by the cut is
        // dropped rather than decoded to a replacement character.
        LOGGER.warnf("Response from %s was truncated (size cap of %d bytes or read deadline); returning partial content", sanitize(url),
                maxResponseBytes);
        byte[] body = response.body();
        String html = new String(body, 0, completeUtf8Length(body), StandardCharsets.UTF_8);
        return new FetchedPage(html, "\n\n[Note: the page response was truncated (it exceeded " + maxResponseBytes
                + " bytes or the read deadline expired); the content above is partial.]");
    }

    /**
     * Length of {@code bytes} without a trailing, incomplete UTF-8 sequence — the
     * part a byte-count cut can leave dangling.
     */
    static int completeUtf8Length(byte[] bytes) {
        int end = bytes.length;
        int start = end - 1;
        // Walk back over at most three continuation bytes (10xxxxxx) to the lead byte.
        while (start >= 0 && end - start <= 4 && (bytes[start] & 0xC0) == 0x80) {
            start--;
        }
        if (start < 0 || end - start > 4) {
            return end; // no lead byte in reach: not UTF-8 we can repair, leave it
        }
        int lead = bytes[start] & 0xFF;
        int expected = lead < 0x80 ? 1 : lead >= 0xF0 ? 4 : lead >= 0xE0 ? 3 : lead >= 0xC0 ? 2 : 1;
        return end - start < expected ? start : end;
    }

    /**
     * A fetched page and, when the body was truncated, the note to append to the
     * tool's output.
     */
    private record FetchedPage(String html, String truncationNote) {
        String annotate(String output) {
            return truncationNote == null ? output : output + truncationNote;
        }
    }

}
