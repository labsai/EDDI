import { describe, it, expect } from "vitest";
import {
  JUDGE_MAX_FIX_CHARS,
  JUDGE_MAX_ISSUES,
  judgeIssuesProblem,
  parseJudgeIssues,
  serializeJudgeIssues,
  withAction,
  withOnExhausted,
} from "../guardrails";

describe("judge issues", () => {
  it("parses an object of strings in order, and blank as no rows", () => {
    expect(parseJudgeIssues('{"B": "fix b", "A": "fix a"}')).toEqual([
      { code: "B", fix: "fix b" },
      { code: "A", fix: "fix a" },
    ]);
    expect(parseJudgeIssues(undefined)).toEqual([]);
    expect(parseJudgeIssues("  ")).toEqual([]);
  });

  it("returns null for anything that is not an object of strings", () => {
    for (const raw of ["not json", "[]", '["A"]', '{"A": 1}', "null", '"A"']) {
      expect(parseJudgeIssues(raw)).toBeNull();
    }
  });

  it("serializes rows to a JSON object the backend parses, and no rows to absent", () => {
    const raw = serializeJudgeIssues([
      { code: "A", fix: 'say "why"' },
      { code: "B", fix: "x" },
    ]);
    expect(JSON.parse(raw)).toEqual({ A: 'say "why"', B: "x" });
    expect(serializeJudgeIssues([])).toBe("");
  });

  it("reports what the deploy check refuses, mirroring JudgeGuardrail.validate", () => {
    const ok = { code: "UNSUPPORTED_CLAIM", fix: "Remove it." };
    expect(judgeIssuesProblem([ok])).toBeNull();
    expect(judgeIssuesProblem([])).toBe("none");
    expect(judgeIssuesProblem(Array.from({ length: JUDGE_MAX_ISSUES + 1 }, (_, i) => ({ code: `C${i}`, fix: "x" })))).toBe("tooMany");
    expect(judgeIssuesProblem(Array.from({ length: JUDGE_MAX_ISSUES }, (_, i) => ({ code: `C${i}`, fix: "x" })))).toBeNull();
    for (const code of ["lower", "1ABC", "A-B", "", "A".repeat(41)]) {
      expect(judgeIssuesProblem([{ code, fix: "x" }])).toBe("badCode");
    }
    expect(judgeIssuesProblem([{ code: "A".repeat(40), fix: "x" }])).toBeNull();
    expect(judgeIssuesProblem([ok, ok])).toBe("duplicate");
    expect(judgeIssuesProblem([{ code: "A", fix: "  " }])).toBe("blankFix");
    expect(judgeIssuesProblem([{ code: "A", fix: "x".repeat(JUDGE_MAX_FIX_CHARS + 1) }])).toBe("longFix");
    expect(judgeIssuesProblem([{ code: "A", fix: "x".repeat(JUDGE_MAX_FIX_CHARS) }])).toBeNull();
  });
});

describe("onExhausted", () => {
  it("stores deliver-best, drops block, and is removed with a non-reprompt action", () => {
    const g = { type: "judge", action: "reprompt" as const };
    expect(withOnExhausted(g, "deliver-best")).toEqual({ ...g, onExhausted: "deliver-best" });
    expect(withOnExhausted({ ...g, onExhausted: "deliver-best" }, "block")).toEqual(g);
    expect(withAction({ ...g, maxRetries: 1, onExhausted: "deliver-best" }, "warn")).toEqual({ type: "judge", action: "warn" });
    expect(withAction({ ...g, onExhausted: "deliver-best" }, "reprompt")).toEqual({ ...g, onExhausted: "deliver-best" });
  });
});
