import { describe, it, expect, beforeAll, afterAll, afterEach } from "vitest";
import { renderHook, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { server } from "@/test/mocks/server";
import { type ReactNode } from "react";
import { useLatestVersions, getVersionInfo } from "@/hooks/use-latest-versions";

beforeAll(() => server.listen({ onUnhandledRequest: "error" }));
afterAll(() => server.close());
afterEach(() => server.resetHandlers());

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false, gcTime: 0 } },
  });
  return function Wrapper({ children }: { children: ReactNode }) {
    return (
      <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
    );
  };
}

describe("useLatestVersions", () => {
  it("is disabled when no URIs provided", () => {
    const { result } = renderHook(() => useLatestVersions([]), {
      wrapper: createWrapper(),
    });
    expect(result.current.fetchStatus).toBe("idle");
  });

  it("fetches latest versions for resource URIs", async () => {
    const uris = [
      "eddi://ai.labs.resource/rulestore/rulesets/res1?version=1",
      "eddi://ai.labs.resource/llmstore/llms/res2?version=1",
    ];

    const { result } = renderHook(() => useLatestVersions(uris), {
      wrapper: createWrapper(),
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBeDefined();
    expect(typeof result.current.data).toBe("object");
  });

  it("deduplicates URIs with the same resource ID", async () => {
    const uris = [
      "eddi://ai.labs.resource/rulestore/rulesets/res1?version=1",
      "eddi://ai.labs.resource/rulestore/rulesets/res1?version=2",
    ];

    const { result } = renderHook(() => useLatestVersions(uris), {
      wrapper: createWrapper(),
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    // Should only have one entry since both URIs have the same ID
    expect(Object.keys(result.current.data!).length).toBe(1);
  });

  it("skips invalid URIs gracefully", async () => {
    const uris = [
      "not-a-valid-uri",
      "",
      "eddi://ai.labs.resource/rulestore/rulesets/res1?version=1",
    ];

    const { result } = renderHook(() => useLatestVersions(uris), {
      wrapper: createWrapper(),
    });

    await waitFor(() => expect(result.current.isSuccess).toBe(true));
    expect(result.current.data).toBeDefined();
  });
});

describe("getVersionInfo", () => {
  it("parses valid eddi resource URI", () => {
    const info = getVersionInfo(
      "eddi://ai.labs.resource/rulestore/rulesets/abc123?version=5",
    );
    expect(info).not.toBeNull();
    expect(info!.id).toBe("abc123");
    expect(info!.version).toBe(5);
  });

  it("returns result even for empty URI (parseResourceUri fallback)", () => {
    // parseResourceUri doesn't throw on empty strings — it returns defaults
    const info = getVersionInfo("");
    expect(info).toBeDefined();
    expect(info!.version).toBe(1);
  });

  it("returns result for non-standard URI (parseResourceUri fallback)", () => {
    const info = getVersionInfo("not-a-uri");
    expect(info).toBeDefined();
  });

  it("returns result for short URI (parseResourceUri fallback)", () => {
    const info = getVersionInfo("eddi://missing-path");
    expect(info).toBeDefined();
  });
});
