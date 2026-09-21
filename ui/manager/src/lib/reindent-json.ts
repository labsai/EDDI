/**
 * Re-indent JSON-shaped text without requiring it to be valid JSON.
 *
 * The diff viewer pretty-prints both sides so that only content differences
 * show. A side that does not parse used to be compared as it came — and a
 * request body arrives compact, on one line — so a single stray brace turned
 * the whole comparison into "every stored line deleted, one line added": the
 * approver could not see what changed, nor where the document was broken.
 *
 * This only ever changes whitespace OUTSIDE string literals; every other
 * character is copied through in order. Nothing is repaired or dropped — an
 * approver must see the document as written, and a closing bracket too many
 * shows up as exactly that: an extra line at the outer indentation. On valid
 * JSON the layout matches `JSON.stringify(value, null, 2)` — but literals stay
 * as written, so `1.50` or `"é"` are not normalised the way a re-printed
 * parse would be.
 */
export function reindentJsonText(text: string, indent = "  "): string {
  let out = "";
  let depth = 0;
  // Whitespace between two values (`true false`, `"a" "b"`) is not layout, it
  // is the only thing keeping them apart — dropping it would print one token
  // that is in neither document. Such a run collapses to a single space.
  let skippedWhitespace = false;
  let afterValue = false;
  const newline = () => "\n" + indent.repeat(depth);
  const value = (token: string) => {
    out += (skippedWhitespace && afterValue ? " " : "") + token;
    skippedWhitespace = false;
    afterValue = true;
  };
  const punctuation = (printed: string) => {
    out += printed;
    skippedWhitespace = false;
    afterValue = false;
  };

  let i = 0;
  while (i < text.length) {
    const c = text[i]!;

    if (c === '"') {
      // Copied verbatim up to the unescaped closing quote — or to the end, for
      // a string that never closes.
      let end = i + 1;
      while (end < text.length && text[end] !== '"') end += text[end] === "\\" ? 2 : 1;
      value(text.slice(i, end + 1));
      i = end + 1;
      continue;
    }

    if (/\s/.test(c)) {
      skippedWhitespace = true;
      i++;
      continue;
    }

    if (c === "{" || c === "[") {
      // An empty container stays on one line, as JSON.stringify prints it.
      let next = i + 1;
      while (next < text.length && /\s/.test(text[next]!)) next++;
      if (text[next] === (c === "{" ? "}" : "]")) {
        value(c + text[next]);
        i = next + 1;
        continue;
      }
      depth++;
      punctuation(c + newline());
    } else if (c === "}" || c === "]") {
      // Clamped, not just clamped when printed: a stray closer must not leave a
      // debt that the next opener silently pays off, printing whatever follows
      // it one level too far left.
      depth = Math.max(0, depth - 1);
      // A trailing comma (`{"a":1,}`) has already opened an empty indented
      // line; the closer takes that line instead of leaving it blank.
      out = out.replace(/\n[^\S\n]*$/, "");
      punctuation((out === "" ? "" : newline()) + c);
      // A closed container is a finished value: text after it (`{…} true`)
      // must stay apart from it, not run into the bracket.
      afterValue = true;
    } else if (c === ",") {
      punctuation("," + newline());
    } else if (c === ":") {
      punctuation(": ");
    } else {
      value(c);
    }
    i++;
  }
  return out;
}

/**
 * Every object key in JSON-shaped text, numbered in the order each first
 * appears — the text need not parse.
 *
 * For lining a parsed document up against one that could not be parsed: the
 * parsed side is then printed in the order the broken side was written in, so a
 * key the writer merely put somewhere else does not read as removed and added.
 * Keys are matched by name alone, whatever object they sit in; that is enough to
 * order by, which is all this is for.
 *
 * The redaction filter's mangled field (`"apiKey=<REDACTED>"`, see
 * `redacted-json.ts`) counts as the key it was.
 */
export function jsonKeyOrder(text: string): Map<string, number> {
  const order = new Map<string, number>();
  let i = 0;
  while (i < text.length) {
    if (text[i] !== '"') {
      i++;
      continue;
    }
    let end = i + 1;
    while (end < text.length && text[end] !== '"') end += text[end] === "\\" ? 2 : 1;
    const literal = text.slice(i, end + 1);

    let next = end + 1;
    while (next < text.length && /\s/.test(text[next]!)) next++;
    const name = text[next] === ":" ? decodeStringLiteral(literal) : /^"([^"\\]*?)=<REDACTED>/.exec(literal)?.[1];
    if (name !== undefined && !order.has(name)) order.set(name, order.size);

    i = end + 1;
  }
  return order;
}

function decodeStringLiteral(literal: string): string | undefined {
  try {
    const value: unknown = JSON.parse(literal);
    return typeof value === "string" ? value : undefined;
  } catch {
    return undefined;
  }
}
