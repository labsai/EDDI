import { describe, it, expect } from "vitest";
import { diffLines, summarizeDiff } from "../prompt-diff";

describe("diffLines", () => {
  it("reports an identical text as all unchanged", () => {
    const d = diffLines("a\nb\nc", "a\nb\nc");
    expect(d.every((l) => l.kind === "same")).toBe(true);
    expect(summarizeDiff(d)).toEqual({ added: 0, removed: 0 });
  });

  it("finds an added line", () => {
    const d = diffLines("a\nc", "a\nb\nc");
    expect(d).toEqual([
      { kind: "same", text: "a" },
      { kind: "added", text: "b" },
      { kind: "same", text: "c" },
    ]);
  });

  it("finds a removed line", () => {
    expect(summarizeDiff(diffLines("a\nb\nc", "a\nc"))).toEqual({ added: 0, removed: 1 });
  });

  it("shows a changed line as one removal and one addition", () => {
    const d = diffLines("keep\nold line\nkeep2", "keep\nnew line\nkeep2");
    expect(d.filter((l) => l.kind !== "same")).toEqual([
      { kind: "removed", text: "old line" },
      { kind: "added", text: "new line" },
    ]);
  });

  it("treats CRLF and LF as the same line ending", () => {
    expect(summarizeDiff(diffLines("a\r\nb", "a\nb"))).toEqual({ added: 0, removed: 0 });
  });

  it("handles an empty side", () => {
    expect(summarizeDiff(diffLines("", "x\ny"))).toEqual({ added: 2, removed: 1 }); // "" is one empty line
    expect(summarizeDiff(diffLines("x", ""))).toEqual({ added: 1, removed: 1 });
  });

  it("survives a very large input without building the table", () => {
    const big = Array.from({ length: 2500 }, (_, i) => `line ${i}`).join("\n");
    const d = diffLines(big, `${big}\nextra`);
    expect(d.length).toBeGreaterThan(2500);
    expect(d.some((l) => l.kind === "added" && l.text === "extra")).toBe(true);
  });
});
