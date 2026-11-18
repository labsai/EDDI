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
    const samples = ["a*+b", "(?>atomic)x", "(?i)secret", String.raw`\Qa.b\E`, String.raw`\Aabc\z`, "[a-z&&[^aeiou]]"];
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
});
