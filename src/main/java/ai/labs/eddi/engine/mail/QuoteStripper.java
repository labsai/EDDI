/*
 * Copyright EDDI contributors
 * SPDX-License-Identifier: Apache-2.0
 */
package ai.labs.eddi.engine.mail;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.select.NodeFilter;
import org.jsoup.select.NodeTraversor;
import org.jsoup.select.NodeVisitor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Removes the quoted history a reply repeats below the new text — only where it
 * is recognised, and never the sender's own words (email plan §14 item 3).
 * <p>
 * Why bother: every reply carries the thread under it, so an agent would read
 * each earlier message again on every turn, and a RAG index would embed the
 * first message of a thread once per reply. Why so careful: an inline answer
 * ("see my answers below") lives <em>inside</em> the quote, and deleting it
 * deletes the only thing the sender said. So the rules are:
 * <ol>
 * <li><b>Recognised blocks only.</b> Text: {@code >} lines with their
 * attribution line, and Outlook's header block ({@code From: … Sent: … To: …},
 * in English, German and French) or "Original Message" separator. HTML: Gmail's
 * {@code gmail_quote}, Apple Mail's and Thunderbird's
 * {@code blockquote type=cite} (with {@code moz-cite-prefix}), and Outlook's
 * reply markers and header block. Anything else stays.</li>
 * <li><b>Trailing only.</b> A {@code >} or cite block is removed only when
 * nothing the sender wrote follows it. A message where quotes and new text
 * alternate is an inline reply and is kept whole: the quotes are the questions
 * the answers refer to.</li>
 * <li><b>Never everything.</b> If removing the history would leave nothing, the
 * message is a forward or a bare quote and is kept as it was.</li>
 * </ol>
 * Outlook's history has no marker that tells an inline answer from the quote,
 * so an inline answer below an Outlook header block is removed with it; that is
 * what the channel's {@code stripQuotedHistory: false} is for.
 */
public final class QuoteStripper {

    /** The result: the remaining content, and whether anything was removed. */
    public record Result(String content, boolean removed) {
    }

    /** "On … wrote:" in the languages the major clients localise it into. */
    private static final List<Pattern> ATTRIBUTIONS = List.of(
            Pattern.compile("^on\\s.{3,300}\\s(wrote|writes)\\s?:$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^am\\s.{3,300}\\sschrieb.{0,300}:$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^le\\s.{3,300}\\sa\\s[ée]crit\\s?:$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
            Pattern.compile("^el\\s.{3,300}\\sescribi[óo]\\s?:$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE),
            Pattern.compile("^il\\s.{3,300}\\sha\\sscritto\\s?:$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^op\\s.{3,300}\\sschreef.{0,300}:$", Pattern.CASE_INSENSITIVE));

    private static final String LABEL_PREFIX = "^\\**\\s*";
    private static final String LABEL_SUFFIX = "\\s*\\**\\s*:";

    private static final Pattern FROM_LABEL = Pattern.compile(LABEL_PREFIX + "(from|von|de)" + LABEL_SUFFIX + "\\s*\\S.*",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern SENT_LABEL = Pattern.compile(LABEL_PREFIX + "(sent|date|gesendet|datum|envoy[ée])" + LABEL_SUFFIX + ".*",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final Pattern TO_OR_SUBJECT_LABEL = Pattern.compile(
            LABEL_PREFIX + "(to|subject|an|betreff|[àa]|objet|cc)" + LABEL_SUFFIX + ".*", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** Outlook's header block flattened into one paragraph, as in its HTML. */
    private static final Pattern HEADER_BLOCK_TEXT = Pattern.compile(
            "^\\s*(from|von|de)\\s*:.{1,400}?\\b(sent|date|gesendet|datum|envoy[ée])\\s*:.{1,400}?\\b(to|subject|an|betreff|objet|[àa])\\s*:.*",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL);

    private static final Pattern ORIGINAL_MESSAGE = Pattern.compile(
            "^\\s*-{2,}\\s*(original message|ursprüngliche nachricht|message d'origine|mensaje original|messaggio originale)\\s*-{2,}\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /**
     * Longer than any Outlook header block, with room for long names and subjects.
     */
    private static final int MAX_HEADER_BLOCK_CHARS = 4000;

    /** Characters of candidate text the Outlook search may read in one document. */
    private static final long HEADER_SEARCH_BUDGET = 2_000_000;

    private static final Pattern UNDERSCORE_RULE = Pattern.compile("^\\s*_{10,}\\s*$");

    /**
     * Gmail's, Apple Mail's and Thunderbird's forward separators, in the same
     * languages.
     */
    private static final Pattern FORWARD_SEPARATOR = Pattern.compile(
            "^\\s*(-{2,}\\s*(forwarded message|weitergeleitete nachricht|message transf[ée]r[ée]|mensaje reenviado|messaggio inoltrato)\\s*-{2,}"
                    + "|-{2,}\\s*original message\\s*-{2,}\\s*forwarded"
                    + "|(begin forwarded message|anfang der weitergeleiteten nachricht|d[ée]but du message r[ée]exp[ée]di[ée])\\s*:)\\s*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /**
     * A subject that marks the message as a forward: Fwd, FW, WG, TR, RV, I, Enc,
     * VS.
     */
    private static final Pattern FORWARD_SUBJECT = Pattern.compile("^\\s*(fwd?|wg|tr|rv|i|enc|vb)\\s*:", Pattern.CASE_INSENSITIVE);

    /** How many lines after the From label Outlook's Sent/To lines may appear. */
    private static final int HEADER_BLOCK_WINDOW = 6;

    private QuoteStripper() {
    }

    // ----------------------------------------------------------------- text

    /** Strips recognised quoted history from plain text. */
    public static Result stripText(String text) {
        if (text == null || text.isBlank()) {
            return new Result(text == null ? "" : text, false);
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        List<String> lines = new ArrayList<>(Arrays.asList(normalized.split("\n", -1)));
        boolean removed = false;

        int outlook = outlookHeaderStart(lines);
        if (outlook >= 0 && hasContent(lines.subList(0, outlook))) {
            lines = new ArrayList<>(lines.subList(0, outlook));
            removed = true;
        }

        int quoteStart = trailingQuoteStart(lines);
        if (quoteStart >= 0) {
            int cut = attributionStart(lines, quoteStart);
            if (hasContent(lines.subList(0, cut))) {
                lines = new ArrayList<>(lines.subList(0, cut));
                removed = true;
            }
        }
        if (!removed) {
            return new Result(text, false);
        }
        return new Result(stripTrailingBlankLines(String.join("\n", lines)), true);
    }

    /**
     * Index of the first line of an Outlook-style history block, or -1: an
     * "Original Message" separator, or a From label followed within a few lines by
     * a Sent/Date label and a To/Subject label. A preceding underscore rule
     * (Outlook web) is included.
     */
    static int outlookHeaderStart(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (ORIGINAL_MESSAGE.matcher(line).matches()) {
                return i;
            }
            if (isQuoted(lines.get(i)) || !FROM_LABEL.matcher(line).matches()) {
                continue;
            }
            boolean sent = false;
            boolean toOrSubject = false;
            for (int j = i + 1; j < Math.min(lines.size(), i + 1 + HEADER_BLOCK_WINDOW); j++) {
                String next = lines.get(j).strip();
                sent |= SENT_LABEL.matcher(next).matches();
                toOrSubject |= TO_OR_SUBJECT_LABEL.matcher(next).matches();
            }
            if (sent && toOrSubject) {
                if (isForwardHeader(lines, i)) {
                    // A forwarded message's header block: what follows is the content
                    // being passed on, not history.
                    continue;
                }
                int start = i;
                while (start > 0 && (lines.get(start - 1).isBlank() || UNDERSCORE_RULE.matcher(lines.get(start - 1)).matches())) {
                    start--;
                }
                return start;
            }
        }
        return -1;
    }

    /**
     * Index of the first line of the trailing {@code >} block, or -1 when the
     * message does not end in one, or when quoted lines also appear earlier — an
     * inline reply, which is kept whole.
     */
    static int trailingQuoteStart(List<String> lines) {
        int end = lines.size() - 1;
        while (end >= 0 && lines.get(end).isBlank()) {
            end--;
        }
        if (end < 0 || !isQuoted(lines.get(end))) {
            return -1;
        }
        int start = end;
        while (start > 0 && (isQuoted(lines.get(start - 1)) || lines.get(start - 1).isBlank())) {
            start--;
        }
        while (start < end && lines.get(start).isBlank()) {
            start++;
        }
        for (int i = 0; i < start; i++) {
            if (isQuoted(lines.get(i))) {
                return -1;
            }
        }
        return start;
    }

    /**
     * Where to cut: above the attribution line(s) right before the quote, if any.
     */
    private static int attributionStart(List<String> lines, int quoteStart) {
        return attributionLine(lines, quoteStart);
    }

    /**
     * Whether the line above a header block (blank lines skipped) is a forward
     * separator.
     */
    private static boolean isForwardHeader(List<String> lines, int headerLine) {
        int i = headerLine - 1;
        while (i >= 0 && lines.get(i).isBlank()) {
            i--;
        }
        return i >= 0 && FORWARD_SEPARATOR.matcher(lines.get(i).strip()).matches();
    }

    /**
     * Whether a subject says the message is a forward. A forward's quoted part is
     * the content — an invoice passed on from a colleague — and Outlook writes the
     * same {@code From:/Sent:} block for a forward as for a reply, so the parser
     * skips stripping for these.
     */
    public static boolean isForwardSubject(String subject) {
        return subject != null && FORWARD_SUBJECT.matcher(subject).find();
    }

    private static int attributionLine(List<String> lines, int quoteStart) {
        int i = quoteStart - 1;
        while (i >= 0 && lines.get(i).isBlank()) {
            i--;
        }
        if (i < 0) {
            return quoteStart;
        }
        if (isAttribution(lines.get(i).strip())) {
            return i;
        }
        // Clients wrap a long attribution ("On Tue, 24 Oct 2026 at 10:15, Alice
        // Example <alice@example.com>\nwrote:") over two lines.
        if (i > 0 && isAttribution((lines.get(i - 1).strip() + " " + lines.get(i).strip()).strip())) {
            return i - 1;
        }
        return quoteStart;
    }

    static boolean isAttribution(String line) {
        if (line.length() > 400) {
            return false;
        }
        for (Pattern pattern : ATTRIBUTIONS) {
            if (pattern.matcher(line).matches()) {
                return true;
            }
        }
        return false;
    }

    private static boolean isQuoted(String line) {
        return line.stripLeading().startsWith(">");
    }

    private static boolean hasContent(List<String> lines) {
        for (String line : lines) {
            if (!line.isBlank()) {
                return true;
            }
        }
        return false;
    }

    private static String stripTrailingBlankLines(String text) {
        return text.stripTrailing();
    }

    // ----------------------------------------------------------------- HTML

    /**
     * Strips recognised quoted history from HTML and returns the remaining HTML.
     * The caller converts it afterwards; the hidden-content pass runs then.
     */
    public static Result stripHtml(String html) {
        if (html == null || html.isBlank()) {
            return new Result(html == null ? "" : html, false);
        }
        Document doc = Jsoup.parse(html);
        boolean removed = stripHtml(doc);
        return removed ? new Result(doc.outerHtml(), true) : new Result(html, false);
    }

    /**
     * Strips recognised quoted history from a parsed document, in place.
     *
     * @return whether anything was removed; when the removal would have left an
     *         empty body nothing is removed
     */
    public static boolean stripHtml(Document doc) {
        if (doc.body() == null) {
            return false;
        }
        Document work = doc.clone();
        boolean removed = stripOutlook(work) | stripGmail(work) | stripCiteBlockquote(work);
        if (!removed || work.body().text().isBlank()) {
            return false;
        }
        doc.body().replaceWith(work.body());
        return true;
    }

    private static boolean stripOutlook(Document doc) {
        Element marker = doc.selectFirst("#appendonsend, #divRplyFwdMsg, #mail-editor-reference-message-container");
        if (marker == null) {
            marker = outlookHeaderElement(doc);
        }
        if (marker == null) {
            return false;
        }
        Element previous = marker.previousElementSibling();
        if (previous != null && "hr".equals(previous.tagName())) {
            // Outlook web draws a rule above #divRplyFwdMsg.
            marker = previous;
        }
        removeFromHereToEnd(marker);
        return true;
    }

    /**
     * The deepest {@code p} or {@code div} whose text starts with Outlook's header
     * block (From, then Sent/Date, then To/Subject).
     * <p>
     * Linear in the document: an element's text includes all of its descendants, so
     * asking every candidate for it costs size times depth on deeply nested markup.
     * Subtree text sizes are computed once, bottom-up; only candidates small enough
     * to be a header block are materialised, deepest first, within a total budget.
     * Running out of budget means "not recognised" — the history is kept, which
     * costs tokens, never content.
     */
    static Element outlookHeaderElement(Document doc) {
        Map<Element, Integer> textLength = new IdentityHashMap<>();
        List<Element> postOrder = new ArrayList<>();
        NodeTraversor.traverse(new NodeVisitor() {
            @Override
            public void head(Node node, int depth) {
            }

            @Override
            public void tail(Node node, int depth) {
                if (node instanceof Element element) {
                    long length = 0;
                    for (Node child : element.childNodes()) {
                        if (child instanceof TextNode text) {
                            length += text.getWholeText().length();
                        } else if (child instanceof Element childElement) {
                            length += textLength.getOrDefault(childElement, 0);
                        }
                    }
                    textLength.put(element, (int) Math.min(Integer.MAX_VALUE, length));
                    if ("p".equals(element.normalName()) || "div".equals(element.normalName())) {
                        postOrder.add(element);
                    }
                }
            }
        }, doc.body());
        long budget = HEADER_SEARCH_BUDGET;
        for (Element candidate : postOrder) {
            int length = textLength.get(candidate);
            if (length > MAX_HEADER_BLOCK_CHARS || length == 0) {
                continue;
            }
            budget -= length;
            if (budget < 0) {
                return null;
            }
            // Post-order: a candidate's descendants were tried first, so the first
            // match is the deepest one.
            if (HEADER_BLOCK_TEXT.matcher(candidate.text()).matches()) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean stripGmail(Document doc) {
        List<Element> candidates = topLevel(doc.body(), QuoteStripper::isGmailQuote);
        if (candidates.size() != 1) {
            return false;
        }
        Element quote = candidates.get(0);
        if (hasOwnText(quote) || hasContentAfter(quote)) {
            return false;
        }
        Element attribution = quote.previousElementSibling();
        quote.remove();
        if (attribution != null && attribution.hasClass("gmail_attr")) {
            attribution.remove();
        }
        return true;
    }

    private static boolean stripCiteBlockquote(Document doc) {
        // iOS and recent macOS Mail put the "On … wrote:" line in a cite blockquote
        // of its own, right before the one holding the quote. Those are part of the
        // history, not a second quote that would make the reply look inline.
        List<Element> attributions = new ArrayList<>();
        List<Element> candidates = new ArrayList<>();
        for (Element candidate : topLevel(doc.body(), QuoteStripper::isCiteBlockquote)) {
            (isAttribution(candidate.text().strip()) ? attributions : candidates).add(candidate);
        }
        if (candidates.size() != 1) {
            return false;
        }
        Element quote = candidates.get(0);
        if (hasContentAfter(quote)) {
            return false;
        }
        attributions.forEach(Element::remove);
        Element previous = quote.previousElementSibling();
        while (previous != null && "br".equals(previous.tagName())) {
            // Apple Mail puts a <br class="Apple-interchange-newline"> between the
            // attribution and the quote.
            previous = previous.previousElementSibling();
        }
        quote.remove();
        if (previous != null && (previous.hasClass("moz-cite-prefix") || isAttribution(previous.text().strip()))) {
            previous.remove();
        }
        return true;
    }

    /**
     * The elements matching {@code predicate} that are not nested in another match
     * — one pass that does not descend into a match, so it is linear however deeply
     * matches nest.
     */
    private static List<Element> topLevel(Element root, Predicate<Element> predicate) {
        List<Element> top = new ArrayList<>();
        NodeTraversor.filter(new NodeFilter() {
            @Override
            public FilterResult head(Node node, int depth) {
                if (node instanceof Element element && predicate.test(element)) {
                    top.add(element);
                    return FilterResult.SKIP_ENTIRELY;
                }
                return FilterResult.CONTINUE;
            }

            @Override
            public FilterResult tail(Node node, int depth) {
                return FilterResult.CONTINUE;
            }
        }, root);
        return top;
    }

    private static boolean isGmailQuote(Element element) {
        String tag = element.normalName();
        return ("div".equals(tag) || "blockquote".equals(tag)) && element.hasClass("gmail_quote");
    }

    private static boolean isCiteBlockquote(Element element) {
        return "blockquote".equals(element.normalName()) && "cite".equalsIgnoreCase(element.attr("type").strip());
    }

    /**
     * Whether a {@code gmail_quote} container holds text of its own — outside its
     * blockquotes and attribution. Gmail keeps an inline reply's answers inside the
     * container, between the blockquotes.
     */
    private static boolean hasOwnText(Element container) {
        if ("blockquote".equals(container.tagName())) {
            return false;
        }
        Element copy = container.clone();
        copy.select("blockquote, .gmail_attr").remove();
        // Older Gmail writes "On … wrote:" as a bare text node in the container.
        String rest = copy.text().strip();
        return !rest.isEmpty() && !isAttribution(rest);
    }

    /**
     * Whether any visible text or image follows {@code element} in document order.
     */
    private static boolean hasContentAfter(Element element) {
        for (Node node = element; node != null && !(node instanceof Document); node = node.parent()) {
            if (node instanceof Element current && "body".equals(current.tagName())) {
                return false;
            }
            for (Node sibling = node.nextSibling(); sibling != null; sibling = sibling.nextSibling()) {
                if (isContent(sibling)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isContent(Node node) {
        if (node instanceof TextNode text) {
            return !text.isBlank();
        }
        if (node instanceof Element element) {
            return !element.text().isBlank() || !element.select("img").isEmpty();
        }
        return false;
    }

    /** Removes {@code element} and everything after it in document order. */
    private static void removeFromHereToEnd(Element element) {
        Element current = element;
        while (current != null && !"body".equals(current.tagName())) {
            Element parent = current.parent();
            List<Node> following = new ArrayList<>();
            for (Node sibling = current.nextSibling(); sibling != null; sibling = sibling.nextSibling()) {
                following.add(sibling);
            }
            following.forEach(Node::remove);
            if (current == element) {
                current.remove();
            }
            current = parent;
        }
    }
}
