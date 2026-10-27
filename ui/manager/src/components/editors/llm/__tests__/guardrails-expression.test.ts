import { describe, it, expect } from "vitest";
import {
  EXPRESSION_FIX_MAX_CHARS,
  EXPRESSION_MAX_CHARS,
  expressionProblems,
  summarizeGuardrail,
  typeHasRewrite,
  typesForPhase,
  withType,
} from "../guardrails";

const valid = { expr: "sum(lines[*].amount) == total", issue: "LINE_SUM", fix: "Make total equal the sum." };

describe("expression guardrail helpers", () => {
  it("runs on the output side only and has no rewrite", () => {
    expect(typesForPhase("output")).toContain("expression");
    expect(typesForPhase("input")).not.toContain("expression");
    expect(typeHasRewrite("expression")).toBe(false);
  });

  it("mirrors the backend's caps: 2000 for expr, 1000 for fix", () => {
    expect(EXPRESSION_MAX_CHARS).toBe(2000);
    expect(EXPRESSION_FIX_MAX_CHARS).toBe(1000);
  });

  it("finds nothing wrong with a complete configuration", () => {
    expect(expressionProblems({ type: "expression", parameters: valid })).toEqual([]);
    expect(
      expressionProblems({
        type: "expression",
        parameters: { ...valid, expr: "x".repeat(2000), fix: "y".repeat(1000), issue: "A".repeat(40) },
      }),
    ).toEqual([]);
  });

  it("reports every missing parameter", () => {
    expect(expressionProblems({ type: "expression" })).toEqual(["exprRequired", "issueRequired", "fixRequired"]);
    expect(expressionProblems({ type: "expression", parameters: { expr: "  ", issue: "", fix: " " } })).toEqual([
      "exprRequired",
      "issueRequired",
      "fixRequired",
    ]);
  });

  it("checks the issue code against the backend's pattern", () => {
    for (const issue of ["line_sum", "1SUM", "LINE-SUM", "A".repeat(41), " LINE_SUM"]) {
      expect(expressionProblems({ type: "expression", parameters: { ...valid, issue } })).toEqual(["issueInvalid"]);
    }
    for (const issue of ["A", "LINE_SUM", "X9_"]) {
      expect(expressionProblems({ type: "expression", parameters: { ...valid, issue } })).toEqual([]);
    }
  });

  it("reports text over the caps", () => {
    expect(
      expressionProblems({ type: "expression", parameters: { ...valid, expr: "x".repeat(2001), fix: "y".repeat(1001) } }),
    ).toEqual(["exprTooLong", "fixTooLong"]);
  });

  it("does not judge other types", () => {
    expect(expressionProblems({ type: "json-object" })).toEqual([]);
  });

  it("summarises the issue code and a shortened expression", () => {
    expect(summarizeGuardrail({ type: "expression", parameters: valid })).toBe("LINE_SUM · sum(lines[*].amount) == total");
    const long = "a".repeat(80);
    expect(summarizeGuardrail({ type: "expression", parameters: { issue: "X", expr: long } })).toBe(`X · ${"a".repeat(60)}…`);
    expect(summarizeGuardrail({ type: "expression" })).toBe("");
  });

  it("switching away from expression drops its parameters but keeps failMode", () => {
    const next = withType({ type: "expression", parameters: { ...valid, failMode: "open" } }, "json-object");
    expect(next).toEqual({ type: "json-object", parameters: { failMode: "open" } });
  });
});
