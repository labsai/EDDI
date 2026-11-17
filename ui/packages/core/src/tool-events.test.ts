import { describe, it, expect, vi } from "vitest";
import { readFileSync } from "node:fs";
import { resolve } from "node:path";
import {
  MAX_TOOL_RESULT_SUMMARY_LENGTH,
  parseToolResult,
  toolResultListener,
} from "./tool-events";

describe("parseToolResult", () => {
  it("reads tool, ok and summary", () => {
    expect(parseToolResult('{"tool":"weather","ok":true,"summary":"18C, clear"}')).toEqual({
      tool: "weather",
      ok: true,
      summary: "18C, clear",
    });
  });

  it("reports a failure as not ok", () => {
    expect(parseToolResult('{"tool":"fetch","ok":false,"summary":"Budget exceeded"}')?.ok).toBe(false);
  });

  it("treats anything but a literal true as not ok", () => {
    expect(parseToolResult('{"tool":"fetch","ok":"true","summary":""}')?.ok).toBe(false);
    expect(parseToolResult('{"tool":"fetch","summary":""}')?.ok).toBe(false);
  });

  it("defaults a missing or non-string summary to empty", () => {
    expect(parseToolResult('{"tool":"calc","ok":true}')?.summary).toBe("");
    expect(parseToolResult('{"tool":"calc","ok":true,"summary":42}')?.summary).toBe("");
  });

  it("is null for a frame without a usable tool name, or not JSON", () => {
    expect(parseToolResult('{"ok":true,"summary":"x"}')).toBeNull();
    expect(parseToolResult('{"tool":"  ","ok":true}')).toBeNull();
    expect(parseToolResult("not json")).toBeNull();
    expect(parseToolResult("")).toBeNull();
    expect(parseToolResult("[]")).toBeNull();
  });

  it("caps an overlong summary and tool name", () => {
    const result = parseToolResult(
      JSON.stringify({ tool: "t".repeat(80), ok: true, summary: "s".repeat(500) }),
    );
    expect(result?.summary.length).toBe(MAX_TOOL_RESULT_SUMMARY_LENGTH);
    expect(result?.summary.endsWith("…")).toBe(true);
    expect(result?.tool.length).toBe(48);
  });

  it("matches the backend's summary bound", () => {
    // Read from the Java, so the two cannot drift apart unnoticed.
    const java = readFileSync(
      resolve(process.cwd(), "../../../src/main/java/ai/labs/eddi/engine/lifecycle/ToolTraceRedaction.java"),
      "utf8",
    );
    const bound = /MAX_SUMMARY_LENGTH = (\d+);/.exec(java)?.[1];
    expect(Number(bound)).toBe(MAX_TOOL_RESULT_SUMMARY_LENGTH);
  });
});

describe("toolResultListener", () => {
  it("calls back for tool_result frames only", () => {
    const onToolResult = vi.fn();
    const listener = toolResultListener(onToolResult);

    listener({ type: "tool_call", data: '{"tool":"weather"}' });
    listener({ type: "token", data: '{"tool":"weather","ok":true}' });
    listener({ type: "tool_result", data: "garbage" });
    listener({ type: "tool_result", data: '{"tool":"weather","ok":true,"summary":"18C"}' });

    expect(onToolResult).toHaveBeenCalledTimes(1);
    expect(onToolResult).toHaveBeenCalledWith({ tool: "weather", ok: true, summary: "18C" });
  });
});
