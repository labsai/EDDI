import { describe, it, expect, vi, beforeEach } from "vitest";
import {
  seg,
  nameSeg,
  isSafePathSegment,
  pathSegmentProblem,
  InvalidPathSegmentError,
} from "../path-segment";

describe("seg", () => {
  it("passes ordinary EDDI ids through untouched", () => {
    expect(seg("5f9d1b3c2a")).toBe("5f9d1b3c2a");
    expect(seg("agent-1_v2")).toBe("agent-1_v2");
    expect(seg(42)).toBe("42");
  });

  it("percent-encodes characters that are legal in an id but not in a path", () => {
    expect(seg("slack:T1:U2")).toBe("slack%3AT1%3AU2");
    expect(seg("a@b.example")).toBe("a%40b.example");
    expect(seg("a?b#c")).toBe("a%3Fb%23c");
    expect(seg("100%")).toBe("100%25");
  });

  it.each([
    ["slash", "a/b"],
    ["leading slash", "/administration"],
    ["backslash", "a\\b"],
    ["dot-dot", ".."],
    ["embedded dot-dot", "a..b"],
    ["traversal", "x/../y"],
    ["space", "a b"],
    ["tab", "a\tb"],
    ["newline", "a\nb"],
    ["NUL", "a\u0000b"],
    ["empty", ""],
  ])("refuses %s", (_name, value) => {
    expect(() => seg(value)).toThrow(InvalidPathSegmentError);
    expect(isSafePathSegment(value)).toBe(false);
  });

  it("refuses null and undefined that slipped past the types", () => {
    expect(() => seg(undefined as unknown as string)).toThrow(InvalidPathSegmentError);
    expect(() => seg(null as unknown as string)).toThrow(InvalidPathSegmentError);
  });

  it("explains why", () => {
    expect(pathSegmentProblem("a/b")).toMatch(/slash/);
    expect(pathSegmentProblem("..")).toMatch(/\.\./);
    expect(pathSegmentProblem("ok")).toBeNull();
  });
});

describe("nameSeg", () => {
  it("encodes a free-form name, slashes and spaces included", () => {
    expect(nameSeg("acme prod")).toBe("acme%20prod");
    expect(nameSeg("a/b?c")).toBe("a%2Fb%3Fc");
  });

  it.each([[""], ["."], [".."]])("refuses %j, which fetch would collapse or drop", (value) => {
    expect(() => nameSeg(value)).toThrow(InvalidPathSegmentError);
  });
});

describe("request paths built from ids", () => {
  beforeEach(() => vi.resetModules());

  it("the API wrappers refuse a traversal id instead of sending the request", async () => {
    const get = vi.fn();
    const del = vi.fn();
    vi.doMock("../../api-client", () => ({
      api: { get, delete: del, post: vi.fn(), put: vi.fn(), getBaseUrl: () => "" },
    }));
    const agents = await import("../agents");
    const conversations = await import("../conversations");
    const chat = await import("../chat");

    expect(() => agents.deleteAgent("a/../b", 1, { cascade: true })).toThrow(/Refusing to use an id/);
    expect(() => agents.getAgent("..")).toThrow(/Refusing to use an id/);
    expect(() => agents.getAgent("abc123", 1)).not.toThrow();
    expect(get).toHaveBeenCalledWith("/agentstore/agents/abc123?version=1");
    expect(() => conversations.deleteConversation("../administration", true)).toThrow(/Refusing to use an id/);
    expect(() => chat.endConversation("x/y")).toThrow(/Refusing to use an id/);
    expect(del).not.toHaveBeenCalled();
    vi.doUnmock("../../api-client");
  });
});
