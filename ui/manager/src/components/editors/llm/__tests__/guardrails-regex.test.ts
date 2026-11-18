import { describe, it, expect } from "vitest";
import { blockingRegexProblem, regexPatternProblems } from "../guardrails";

describe("regexPatternProblems", () => {
  it("accepts ordinary patterns and ignores blank lines", () => {
    expect(regexPatternProblems(String.raw`foo` + "\n\n  bar+  \r\n" + String.raw`\d{3}-\d{4}`)).toEqual([]);
    expect(regexPatternProblems(undefined)).toEqual([]);
    expect(regexPatternProblems("   ")).toEqual([]);
  });

  it("reports an invalid pattern with its 1-based line number", () => {
    const problems = regexPatternProblems("ok\n(unclosed\n[a-");
    expect(problems.map((p) => [p.line, p.state])).toEqual([
      [2, "invalid"],
      [3, "invalid"],
    ]);
    expect(problems[0]?.detail).toBeTruthy();
  });

  it("does not reject syntax only Java understands — it says it cannot check it", () => {
    const samples = [
      "a*+b",
      "a++",
      "x{2}+",
      "(?>atomic)x",
      "(?i)secret",
      "(?x) a b # comment",
      "(?-i:Case)",
      String.raw`\Qa.b\E`,
      String.raw`\Q[\E`,
      String.raw`\Aabc\z`,
      String.raw`end\Z`,
      String.raw`\Gx`,
      "[a-z&&[^aeiou]]",
      // A nested class is a union in Java; JavaScript reads `[-+` as a backwards range.
      "[a-z[-+]]",
      "[^[0-9]x]",
      "[]a]",
      String.raw`\p{javaLowerCase}+`,
      String.raw`\p{L}`,
      String.raw`\pL`,
      String.raw`\h+`,
      String.raw`\R`,
      String.raw`\X`,
      String.raw`\x{1F600}`,
      // A control escape takes any character in Java; JavaScript reads `\c[` as `\`, `c`, an open class.
      String.raw`\c[`,
      String.raw`\c(`,
      String.raw`\c)`,
      String.raw`[\c]]`,
      // Java quantifies anchors and lookbehinds; JavaScript says "nothing to repeat".
      "^*abc",
      "abc$?",
      String.raw`\B+x`,
      "(?<=a)*b",
      "(?<!a){2}b",
      // Java stacks a `{n}` on a quantifier; JavaScript does not.
      "a{2}{3}",
      "a*{2}",
      "a+?{2}",
    ];
    for (const javaOnly of samples) {
      const problems = regexPatternProblems(javaOnly);
      expect(problems, javaOnly).toHaveLength(1);
      expect(problems[0]?.state, javaOnly).toBe("unchecked");
    }
  });

  it("still checks what both engines read alike next to that syntax", () => {
    // Control letters, quantified lookaheads and non-capturing groups, escaped
    // quantifier characters: ordinary in both, so no note.
    for (const ordinary of [String.raw`\cM`, "(?=a)*b", "(?:^)*x", String.raw`\*{2}`, String.raw`^\d+$`, "a{2}?b"]) {
      expect(regexPatternProblems(ordinary), ordinary).toEqual([]);
    }
    // An escaped backslash before `c` is not a control escape: `\\c[` is an unclosed class in Java too.
    expect(regexPatternProblems(String.raw`\\c[`).map((p) => p.state)).toEqual(["invalid"]);
    expect(regexPatternProblems("a**").map((p) => p.state)).toEqual(["invalid"]);
  });
});

describe("blockingRegexProblem", () => {
  it("blocks only a regex-filter with an invalid line", () => {
    expect(blockingRegexProblem({ type: "regex-filter", parameters: { patterns: "(" } })?.line).toBe(1);
    expect(blockingRegexProblem({ type: "regex-filter", parameters: { patterns: "a*+" } })).toBeUndefined();
    expect(blockingRegexProblem({ type: "prompt-injection", parameters: { patterns: "(" } })).toBeUndefined();
    expect(blockingRegexProblem({ type: "regex-filter" })).toBeUndefined();
  });

  it("never blocks a Java nested character class, and still blocks a plain malformed class", () => {
    expect(blockingRegexProblem({ type: "regex-filter", parameters: { patterns: "[a-z[-+]]" } })).toBeUndefined();
    expect(blockingRegexProblem({ type: "regex-filter", parameters: { patterns: "[z-a]" } })?.line).toBe(1);
    expect(blockingRegexProblem({ type: "regex-filter", parameters: { patterns: String.raw`\[(` } })?.line).toBe(1);
  });

  it("never blocks a Java control escape or quantifier JavaScript rejects, and still blocks `(`", () => {
    for (const javaValid of [String.raw`\c[`, "^*a", "(?<=a)*b", "a{2}{3}"]) {
      expect(blockingRegexProblem({ type: "regex-filter", parameters: { patterns: javaValid } }), javaValid).toBeUndefined();
    }
    expect(blockingRegexProblem({ type: "regex-filter", parameters: { patterns: "(" } })?.line).toBe(1);
  });

  it("reads a non-text patterns value the way the server does, without throwing", () => {
    expect(regexPatternProblems(42)).toEqual([]);
    expect(regexPatternProblems(null)).toEqual([]);
  });
});
