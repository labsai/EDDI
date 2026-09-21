import { describe, it, expect } from "vitest";
import { pairToolTrace } from "../tool-trace";
import type { ToolTraceEntry } from "@/hooks/use-debug-events";

const call = (tool: string): ToolTraceEntry => ({ type: "tool_call", tool, arguments: "{}" });
const result = (tool: string, r = "ok"): ToolTraceEntry => ({ type: "tool_result", tool, result: r });
const error = (tool: string, e = "refused"): ToolTraceEntry => ({ type: "tool_error", tool, error: e });

/**
 * The defect this pins: the trace UI zipped calls to results by INDEX over two
 * filtered arrays, and the backend interleaves tool_error entries with no
 * matching tool_result. One refused call mid-turn shifted every later pairing —
 * the refused call showed the NEXT call's result under a green check, and the
 * final call spun forever.
 */
describe("pairToolTrace", () => {
  it("pairs plain call→result sequences one to one", () => {
    const pairs = pairToolTrace([call("a"), result("a"), call("b"), result("b")]);
    expect(pairs).toHaveLength(2);
    expect(pairs[0]!.call!.tool).toBe("a");
    expect(pairs[0]!.result!.result).toBe("ok");
    expect(pairs[1]!.call!.tool).toBe("b");
  });

  it("does NOT shift later pairings when a call is refused mid-turn", () => {
    const pairs = pairToolTrace([
      call("a"), error("a"),
      call("b"), result("b", "b-ok"),
    ]);
    expect(pairs).toHaveLength(2);
    // The refused call carries ITS error — not b's result.
    expect(pairs[0]!.error!.tool).toBe("a");
    expect(pairs[0]!.result).toBeUndefined();
    // ...and b keeps its own result rather than being orphaned to a spinner.
    expect(pairs[1]!.result!.result).toBe("b-ok");
  });

  it("surfaces a cap refusal that has NO preceding call as its own row", () => {
    // The pause-cap path emits tool_error without a tool_call. Dropping it
    // silently would hide the refusal entirely.
    const pairs = pairToolTrace([error("gated", "pause cap reached"), call("a"), result("a")]);
    expect(pairs).toHaveLength(2);
    expect(pairs[0]!.call).toBeUndefined();
    expect(pairs[0]!.error!.error).toBe("pause cap reached");
    expect(pairs[1]!.call!.tool).toBe("a");
  });

  it("leaves a still-running call unresolved rather than stealing the next result", () => {
    const pairs = pairToolTrace([call("a"), call("b"), result("b")]);
    expect(pairs).toHaveLength(2);
    // a never resolved — b's result belongs to b. This is lossy in the
    // pathological unordered case, but the trace is emitted in order.
    expect(pairs[0]!.result).toBeUndefined();
    expect(pairs[1]!.result).toBeDefined();
  });

  it("handles empty and undefined traces", () => {
    expect(pairToolTrace(undefined)).toEqual([]);
    expect(pairToolTrace([])).toEqual([]);
  });
});
