import { describe, it, expect } from "vitest";
import { parseSseFrame } from "../sse-utils";

/**
 * Regression tests for the three framing rules the chat stream previously got
 * wrong: it assigned instead of appending `data:` lines, `.trim()`-ed token
 * payloads, and never stripped the "\r" of CRLF framing.
 */
describe("parseSseFrame", () => {
  it("concatenates multiple data: lines with a newline", () => {
    const frame = ["event: message", "data: line one", "data: line two"].join("\n");
    expect(parseSseFrame(frame)).toEqual({
      type: "message",
      data: "line one\nline two",
    });
  });

  it("preserves leading and trailing whitespace in a token payload", () => {
    // A lone space is a meaningful chunk in a token stream.
    expect(parseSseFrame("event: token\ndata:  hello ")?.data).toBe(" hello ");
    expect(parseSseFrame("event: token\ndata: ")?.data).toBe("");
  });

  it("strips exactly one optional space after the colon, no more", () => {
    expect(parseSseFrame("data:   three-leading-spaces")?.data).toBe(
      "  three-leading-spaces"
    );
    expect(parseSseFrame("data:no-space")?.data).toBe("no-space");
  });

  it("strips the trailing CR of CRLF framing", () => {
    const frame = "event: token\r\ndata: payload\r";
    expect(parseSseFrame(frame)).toEqual({ type: "token", data: "payload" });
  });

  it("uses the supplied default event type when no event: line is present", () => {
    expect(parseSseFrame("data: x", "token")).toEqual({ type: "token", data: "x" });
    expect(parseSseFrame("data: x")).toEqual({ type: "message", data: "x" });
  });

  it("returns null for a heartbeat comment or blank padding", () => {
    expect(parseSseFrame(": keep-alive")).toBeNull();
    expect(parseSseFrame("")).toBeNull();
    expect(parseSseFrame("\n\n")).toBeNull();
  });

  it("keeps an empty data payload distinct from no data field", () => {
    expect(parseSseFrame("event: done")).toEqual({ type: "done", data: "" });
  });

  it("reassembles a multi-line JSON payload split across data: lines", () => {
    const frame = ['data: {"a":1,', 'data:  "b":2}'].join("\n");
    const parsed = parseSseFrame(frame);
    expect(parsed).not.toBeNull();
    expect(JSON.parse(parsed!.data)).toEqual({ a: 1, b: 2 });
  });
});
