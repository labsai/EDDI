/* looksLikeMath and looksLikeCode run on every streamed token, so they must
   stay linear. These pin that they answer exactly what the regexes they
   replaced answered, and that adversarial input does not make them crawl. */

import { describe, expect, it } from "vitest";

import { looksLikeCode, looksLikeMath } from "./markdown-plugins";

// The previous patterns, kept here only as the reference answer.
const OLD_MATH = /\$\$[\s\S]*?\S[\s\S]*?\$\$/;
const OLD_CODE = /(^|\n)\s*(```|~~~)/;

const SAMPLES = [
  "",
  "$",
  "$$",
  "$$$",
  "$$$$",
  "$$$$$",
  "$$ $$",
  "$$\n$$",
  "$$x$$",
  "$$x^2$$",
  "inline $$a+b$$ here",
  "It costs $5 and $10 today",
  "$x$",
  "$$ x",
  "x $$",
  "$$ $ $$",
  "a $$ b $$ c $$",
  "$$\t\n$$ and $$y$$",
  "```js\nconst a = 1;\n```",
  "text\n```\ncode\n```",
  "text ```inline``` text",
  "  ~~~\nx\n~~~",
  "\n\n\t ```",
  "a\r```",
  "\r```",
  "a\n \r```",
  "a ```",
  "a\n ~~~",
  "~~",
  "``",
  "no fence here",
];

describe("looksLikeMath", () => {
  it.each(SAMPLES)("answers what the regex answered: %j", (text) => {
    expect(looksLikeMath(text)).toBe(OLD_MATH.test(text));
  });

  it("stays fast on a long run of dollars and whitespace", () => {
    const adversarial = "$$" + " ".repeat(50_000) + "$".repeat(50_001);
    const start = performance.now();
    expect(looksLikeMath(adversarial)).toBe(true);
    expect(looksLikeMath("$$" + " ".repeat(100_000))).toBe(false);
    expect(looksLikeMath("$".repeat(100_001))).toBe(true);
    expect(performance.now() - start).toBeLessThan(500);
  });
});

describe("looksLikeCode", () => {
  it.each(SAMPLES)("answers what the regex answered: %j", (text) => {
    expect(looksLikeCode(text)).toBe(OLD_CODE.test(text));
  });

  it("stays fast on a long run of newlines and blanks", () => {
    const adversarial = "x" + "\n \t".repeat(50_000) + "``";
    const start = performance.now();
    expect(looksLikeCode(adversarial)).toBe(false);
    expect(looksLikeCode(adversarial + "`")).toBe(true);
    expect(performance.now() - start).toBeLessThan(500);
  });
});
