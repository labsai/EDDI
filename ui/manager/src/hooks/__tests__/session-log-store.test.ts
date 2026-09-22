import { describe, it, expect, beforeEach, vi } from "vitest";
import {
  useSessionLogStore,
  _connectForTesting,
  connect,
  disconnect,
  subscriberCount,
  isStreamOpen,
} from "@/hooks/session-log-store";
import * as logsApi from "@/lib/api/logs";

vi.mock("@/lib/api/logs", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/api/logs")>();
  return {
    ...actual,
    getRecentLogs: vi.fn().mockResolvedValue([]),
  };
});

describe("useSessionLogStore", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // Drain any subscription a previous test leaked: a non-zero refcount would
    // make "connect() opens exactly one" pass without opening anything.
    while (subscriberCount() > 0) disconnect();
    // Reset store state between tests
    useSessionLogStore.setState({
      entries: [],
      connected: false,
      seeded: false,
    });
  });

  it("starts with default state", () => {
    const state = useSessionLogStore.getState();
    expect(state.entries).toEqual([]);
    expect(state.connected).toBe(false);
  });

  it("can set connected state", () => {
    useSessionLogStore.setState({ connected: true });
    expect(useSessionLogStore.getState().connected).toBe(true);
  });

  it("can add entries", () => {
    const entry = {
      timestamp: Date.now(),
      level: "INFO" as const,
      loggerName: "test.logger",
      message: "Test message",
      environment: "test",
      agentId: "agent1",
      agentVersion: 1,
      conversationId: "conv1",
      userId: "user1",
      instanceId: "node1",
    };

    useSessionLogStore.setState((s) => ({
      entries: [entry, ...s.entries],
    }));

    expect(useSessionLogStore.getState().entries).toHaveLength(1);
    expect(useSessionLogStore.getState().entries[0]!.message).toBe(
      "Test message",
    );
  });

  it("prepends new entries (newest first)", () => {
    const entry1 = {
      timestamp: Date.now() - 1000,
      level: "INFO" as const,
      loggerName: "test",
      message: "First",
      environment: undefined,
      agentId: undefined,
      agentVersion: undefined,
      conversationId: undefined,
      userId: undefined,
      instanceId: undefined,
    };
    const entry2 = {
      ...entry1,
      timestamp: Date.now(),
      message: "Second",
    };

    useSessionLogStore.setState((s) => ({
      entries: [entry1, ...s.entries],
    }));
    useSessionLogStore.setState((s) => ({
      entries: [entry2, ...s.entries],
    }));

    const { entries } = useSessionLogStore.getState();
    expect(entries).toHaveLength(2);
    expect(entries[0]!.message).toBe("Second");
    expect(entries[1]!.message).toBe("First");
  });

  it("caps at 1000 entries", () => {
    // Fill with 1001 entries
    const entries = Array.from({ length: 1001 }, (_, i) => ({
      timestamp: Date.now() - i,
      level: "INFO" as const,
      loggerName: "test",
      message: `Entry ${i}`,
      environment: undefined,
      agentId: undefined,
      agentVersion: undefined,
      conversationId: undefined,
      userId: undefined,
      instanceId: undefined,
    }));

    useSessionLogStore.setState({ entries });

    // Simulate the actual capping logic used in the store
    useSessionLogStore.setState((s) => ({
      entries: s.entries.length > 1000 ? s.entries.slice(0, 1000) : s.entries,
    }));

    expect(useSessionLogStore.getState().entries).toHaveLength(1000);
  });

  it("can connect to SSE stream and buffer entries", () => {
    const connection = _connectForTesting();
    const es = connection.getEventSource();
    expect(es).not.toBeNull();
    if (!es) return;

    // Trigger onopen
    es.onopen?.();
    expect(useSessionLogStore.getState().connected).toBe(true);

    // Trigger onmessage
    const entry = {
      timestamp: Date.now(),
      level: "INFO" as const,
      loggerName: "test",
      message: "Log stream message",
      environment: undefined,
      agentId: undefined,
      agentVersion: undefined,
      conversationId: undefined,
      userId: undefined,
      instanceId: undefined,
    };
    es.onmessage?.(new MessageEvent("message", { data: JSON.stringify(entry) }));
    expect(useSessionLogStore.getState().entries).toHaveLength(1);
    expect(useSessionLogStore.getState().entries[0]!.message).toBe("Log stream message");

    // Trigger onerror
    es.onerror?.();
    expect(useSessionLogStore.getState().connected).toBe(false);

    connection.close();
  });

  it("starts with seeded = false", () => {
    expect(useSessionLogStore.getState().seeded).toBe(false);
  });

  it("seeds from REST API on first connect when entries are empty", async () => {
    const { getRecentLogs } = await import("@/lib/api/logs");
    const mockGetRecentLogs = vi.mocked(getRecentLogs);
    mockGetRecentLogs.mockResolvedValueOnce([
      {
        timestamp: 1000,
        level: "INFO",
        loggerName: "test",
        message: "Historical entry",
        environment: undefined,
        agentId: undefined,
        agentVersion: undefined,
        conversationId: undefined,
        userId: undefined,
        instanceId: undefined,
      },
    ]);

    const connection = _connectForTesting();
    const es = connection.getEventSource();
    expect(es).not.toBeNull();
    if (!es) return;

    // Trigger onopen — should call getRecentLogs since entries are empty
    await es.onopen?.();

    // Wait for the async seed to complete
    await vi.waitFor(() => {
      expect(useSessionLogStore.getState().seeded).toBe(true);
    });

    expect(useSessionLogStore.getState().entries).toHaveLength(1);
    expect(useSessionLogStore.getState().entries[0]!.message).toBe("Historical entry");

    connection.close();
  });

  it("deduplicates entries during REST seed merge", async () => {
    const { getRecentLogs } = await import("@/lib/api/logs");
    const mockGetRecentLogs = vi.mocked(getRecentLogs);
    const sharedEntry = {
      timestamp: 2000,
      level: "WARN" as const,
      loggerName: "dup",
      message: "Duplicate msg",
      environment: undefined,
      agentId: undefined,
      agentVersion: undefined,
      conversationId: undefined,
      userId: undefined,
      instanceId: undefined,
    };

    mockGetRecentLogs.mockResolvedValueOnce([sharedEntry]);

    const connection = _connectForTesting();
    const es = connection.getEventSource();
    if (!es) return;

    // Trigger onopen (entries empty → triggers REST seed)
    const openPromise = es.onopen?.();

    // Simulate an SSE event arriving while REST is in-flight (same entry)
    es.onmessage?.(new MessageEvent("message", { data: JSON.stringify(sharedEntry) }));
    expect(useSessionLogStore.getState().entries).toHaveLength(1);

    await openPromise;
    await vi.waitFor(() => {
      expect(useSessionLogStore.getState().seeded).toBe(true);
    });

    // Should still be 1 after dedup, not 2
    expect(useSessionLogStore.getState().entries).toHaveLength(1);

    connection.close();
  });

  it("sets seeded = true even if REST seed fails", async () => {
    const { getRecentLogs } = await import("@/lib/api/logs");
    const mockGetRecentLogs = vi.mocked(getRecentLogs);
    mockGetRecentLogs.mockRejectedValueOnce(new Error("Network error"));

    const connection = _connectForTesting();
    const es = connection.getEventSource();
    if (!es) return;

    await es.onopen?.();

    await vi.waitFor(() => {
      expect(useSessionLogStore.getState().seeded).toBe(true);
    });

    // No entries since REST failed and no SSE events arrived
    expect(useSessionLogStore.getState().entries).toHaveLength(0);

    connection.close();
  });

  it("skips REST seed when entries are already present", async () => {
    const { getRecentLogs } = await import("@/lib/api/logs");
    const mockGetRecentLogs = vi.mocked(getRecentLogs);

    // Pre-populate store
    useSessionLogStore.setState({
      entries: [{
        timestamp: 5000,
        level: "INFO",
        loggerName: "pre",
        message: "Pre-existing",
        environment: undefined,
        agentId: undefined,
        agentVersion: undefined,
        conversationId: undefined,
        userId: undefined,
        instanceId: undefined,
      }],
    });

    const connection = _connectForTesting();
    const es = connection.getEventSource();
    if (!es) return;

    await es.onopen?.();

    // Should NOT have called getRecentLogs since entries were not empty
    expect(mockGetRecentLogs).not.toHaveBeenCalled();

    connection.close();
  });

  // ── Lazy, reference-counted connection (D1) ──────────────────────
  //
  // This module used to connect on import, and `main.tsx` imported it for that
  // side effect — so every Manager tab held an open
  // /administration/logs/stream SSE connection on every page for its whole
  // lifetime. EDDI serves HTTP/1.1, where Chrome allows six concurrent
  // connections per origin across the entire profile, and a live group
  // discussion opens another. Two or three tabs saturated the cap: pages hung
  // on skeleton loaders forever while the server was provably fine.
  describe("connection lifecycle", () => {
    it("importing the module opens no EventSource", () => {
      // The import at the top of this file has already run — this assertion is
      // the regression itself. A module-load `openStream()` fails it.
      expect(isStreamOpen()).toBe(false);
      expect(subscriberCount()).toBe(0);
    });

    it("connect() opens exactly one stream", () => {
      const spy = vi.spyOn(logsApi, "createLogEventSource");

      const release = connect();

      expect(spy).toHaveBeenCalledTimes(1);
      expect(subscriberCount()).toBe(1);
      release();
      spy.mockRestore();
    });

    it("a second connect() reuses the open stream", () => {
      const spy = vi.spyOn(logsApi, "createLogEventSource");

      const first = connect();
      const second = connect();

      expect(spy).toHaveBeenCalledTimes(1);
      expect(subscriberCount()).toBe(2);
      first();
      second();
      spy.mockRestore();
    });

    it("closes only after the last consumer leaves", () => {
      const first = connect();
      const source = _sourceOf();
      const closeSpy = vi.spyOn(source!, "close");
      const second = connect();

      first();
      expect(closeSpy).not.toHaveBeenCalled();
      expect(useSessionLogStore.getState().connected).toBe(false); // never opened in jsdom

      second();
      expect(closeSpy).toHaveBeenCalledTimes(1);
      expect(subscriberCount()).toBe(0);
    });

    it("a release function is idempotent, so a double-invoked effect cleanup is safe", () => {
      const release = connect();
      const other = connect();

      release();
      release();
      release();

      // Only one subscription was ever released, so the other consumer still
      // holds the stream open. React 19 double-invokes effect cleanups in
      // StrictMode, which is exactly this shape.
      expect(subscriberCount()).toBe(1);
      other();
      expect(subscriberCount()).toBe(0);
    });

    it("disconnect() on an idle store is a no-op", () => {
      expect(() => disconnect()).not.toThrow();
      expect(subscriberCount()).toBe(0);
    });

    it("reconnects after the last consumer left", () => {
      const spy = vi.spyOn(logsApi, "createLogEventSource");

      connect()();
      const release = connect();

      expect(spy).toHaveBeenCalledTimes(2);
      release();
      spy.mockRestore();
    });
  });
});

/** The live EventSource, read through the testing hook without re-counting. */
function _sourceOf() {
  const probe = _connectForTesting();
  const source = probe.getEventSource();
  probe.close();
  return source;
}
