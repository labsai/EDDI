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
    ];
    for (const javaOnly of samples) {
      const problems = regexPatternProblems(javaOnly);
      expect(problems, javaOnly).toHaveLength(1);
      expect(problems[0]?.state, javaOnly).toBe("unchecked");
    }
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

  it("reads a non-text patterns value the way the server does, without throwing", () => {
    expect(regexPatternProblems(42)).toEqual([]);
    expect(regexPatternProblems(null)).toEqual([]);
  });
});
