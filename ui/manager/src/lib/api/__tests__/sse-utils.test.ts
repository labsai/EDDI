import { describe, it, expect, vi, beforeEach } from "vitest";
import { createAuthEventSource } from "../sse-utils";

// ─── Helpers ──────────────────────────────────────────────────────────────────

function createMockStream(chunks: string[]) {
  const encoder = new TextEncoder();
  let index = 0;
  return new ReadableStream<Uint8Array>({
    pull(controller) {
      if (index < chunks.length) {
        controller.enqueue(encoder.encode(chunks[index]!));
        index++;
      } else {
        controller.close();
      }
    },
  });
}

function mockFetchResponse(chunks: string[], status = 200) {
  vi.stubGlobal(
    "fetch",
    vi.fn().mockResolvedValue({
      ok: status >= 200 && status < 300,
      status,
      body: createMockStream(chunks),
    }),
  );
}

// ─── Tests ────────────────────────────────────────────────────────────────────

describe("createAuthEventSource", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    // Mock the api module used by sse-utils
    vi.mock("../../api-client", () => ({
      api: {
        getBaseUrl: () => "http://test",
        getAuthHeader: () => ({}),
      },
    }));
  });

  it("parses 'data: value' (with space) correctly", async () => {
    const onMessage = vi.fn();
    mockFetchResponse(["data: hello\n\n"]);

    createAuthEventSource("/test", { onMessage });

    // Allow the async IIFE to process
    await vi.waitFor(() => {
      expect(onMessage).toHaveBeenCalledWith({ type: "message", data: "hello" });
    });
  });

  it("parses 'data:value' (without space) correctly", async () => {
    const onMessage = vi.fn();
    mockFetchResponse(["data:hello\n\n"]);

    createAuthEventSource("/test", { onMessage });

    await vi.waitFor(() => {
      expect(onMessage).toHaveBeenCalledWith({ type: "message", data: "hello" });
    });
  });

  it("parses 'event: custom' (with space) correctly", async () => {
    const onMessage = vi.fn();
    mockFetchResponse(["event: custom\ndata: payload\n\n"]);

    createAuthEventSource("/test", { onMessage });

    await vi.waitFor(() => {
      expect(onMessage).toHaveBeenCalledWith({ type: "custom", data: "payload" });
    });
  });

  it("parses 'event:custom' (without space) correctly", async () => {
    const onMessage = vi.fn();
    mockFetchResponse(["event:custom\ndata:payload\n\n"]);

    createAuthEventSource("/test", { onMessage });

    await vi.waitFor(() => {
      expect(onMessage).toHaveBeenCalledWith({ type: "custom", data: "payload" });
    });
  });

  it("joins multi-line data with newlines", async () => {
    const onMessage = vi.fn();
    mockFetchResponse(["data: line1\ndata: line2\ndata: line3\n\n"]);

    createAuthEventSource("/test", { onMessage });

    await vi.waitFor(() => {
      expect(onMessage).toHaveBeenCalledWith({
        type: "message",
        data: "line1\nline2\nline3",
      });
    });
  });

  it("dispatches event only on empty line", async () => {
    const onMessage = vi.fn();
    // First chunk has data but no empty line yet; second chunk closes the event
    mockFetchResponse(["data: partial\n", "\n"]);

    createAuthEventSource("/test", { onMessage });

    await vi.waitFor(() => {
      expect(onMessage).toHaveBeenCalledTimes(1);
      expect(onMessage).toHaveBeenCalledWith({ type: "message", data: "partial" });
    });
  });

  it("calls onOpen on successful connection", async () => {
    const onOpen = vi.fn();
    mockFetchResponse(["data: test\n\n"]);

    createAuthEventSource("/test", { onOpen });

    await vi.waitFor(() => {
      expect(onOpen).toHaveBeenCalledTimes(1);
    });
  });

  it("calls onError on non-ok response", async () => {
    const onError = vi.fn();
    mockFetchResponse([], 500);

    createAuthEventSource("/test", { onError });

    await vi.waitFor(() => {
      expect(onError).toHaveBeenCalledTimes(1);
      expect(onError).toHaveBeenCalledWith(
        expect.objectContaining({ message: expect.stringContaining("500") }),
      );
    });
  });

  it("silently ignores AbortError", async () => {
    const onError = vi.fn();
    const abortError = new DOMException("Aborted", "AbortError");
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(abortError));

    createAuthEventSource("/test", { onError });

    // Give it time to process
    await new Promise((r) => setTimeout(r, 50));
    expect(onError).not.toHaveBeenCalled();
  });
});
